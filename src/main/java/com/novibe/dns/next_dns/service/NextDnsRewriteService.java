package com.novibe.dns.next_dns.service;

import com.novibe.common.base_structures.BypassRoute;
import com.novibe.common.service.ExcludeRedirectCheckService;
import com.novibe.common.util.Log;
import com.novibe.dns.next_dns.http.NextDnsRewriteClient;
import com.novibe.dns.next_dns.http.dto.request.CreateRewriteDto;
import com.novibe.dns.next_dns.http.dto.response.rewrite.RewriteDto;
import com.novibe.dns.next_dns.sync.RewritePlanner;
import com.novibe.dns.next_dns.sync.RewritePlanner.*;
import com.novibe.dns.next_dns.sync.SyncFiles;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Service
@RequiredArgsConstructor
public class NextDnsRewriteService {
    private final NextDnsRewriteClient client;
    private final ExcludeRedirectCheckService exclusions;

    public Plan prepare(String profile, List<BypassRoute> routes, Path dir) {
        try { Files.deleteIfExists(dir.resolve("plan.json")); }
        catch (java.io.IOException e) { throw new IllegalStateException("Cannot invalidate old plan", e); }
        Path stateFile = dir.resolve("state.json");
        State state = Files.exists(stateFile) ? SyncFiles.read(stateFile, State.class)
                : new State(1, profile, List.of());
        List<RewriteDto> current = client.fetchRewrites();
        List<CreateRewriteDto> source = routes.stream().map(r -> new CreateRewriteDto(r.website(), r.ip())).toList();
        Plan plan = RewritePlanner.plan(profile, state, current, source, exclusions::shouldExclude);
        // Backups are written and uploaded by the workflow BEFORE the apply phase is possible.
        SyncFiles.write(dir.resolve("backup.json"), client.getLastSnapshot());
        SyncFiles.write(dir.resolve("state-before.json"), state);
        SyncFiles.write(dir.resolve("plan.json"), plan);
        Log.common("DRY RUN: create=%s delete=%s preserved manual conflicts=%s"
                .formatted(plan.create().size(), plan.delete().size(), plan.conflicts().size()));
        return plan;
    }

    public void apply(String profile, Path dir, int maxDeletes) {
        Plan plan = SyncFiles.read(dir.resolve("plan.json"), Plan.class);
        if (plan == null || plan.version() != 1 || !profile.equals(plan.profile()))
            throw new IllegalStateException("Wrong profile or invalid plan");
        State state = Files.exists(dir.resolve("state.json")) ? SyncFiles.read(dir.resolve("state.json"), State.class)
                : new State(1, profile, List.of());
        RewritePlanner.validate(state, profile);
        if (!state.equals(plan.state())) throw new IllegalStateException("Ownership changed; prepare a new plan");
        // Recompute all destructive operations from the reviewed target and ownership state.
        Plan checked = RewritePlanner.plan(profile, state, plan.before(), plan.desired(), name -> false);
        if (!checked.equals(plan)) throw new IllegalStateException("Invalid plan; prepare again");
        if (maxDeletes < 0 || plan.delete().size() > maxDeletes)
            throw new IllegalStateException("Deletion budget exceeded: " + plan.delete().size());
        if (!Files.isRegularFile(dir.resolve("backup.json")) || !Files.isRegularFile(dir.resolve("state-before.json")))
            throw new IllegalStateException("Backup required before apply");
        if (!RewritePlanner.index(client.fetchRewrites()).equals(RewritePlanner.index(plan.before())))
            throw new IllegalStateException("NextDNS changed after dry run; prepare a new plan");
        // Check writable durable storage before the first remote mutation.
        Map<String, RewriteDto> owned = new LinkedHashMap<>(RewritePlanner.index(state.managed()));
        Map<String, RewriteDto> live = RewritePlanner.index(plan.before());
        owned.entrySet().removeIf(e -> !e.getValue().equals(live.get(e.getKey())));
        saveState(dir, profile, owned);
        // Add replacements first: a failed POST must never remove the working old route.
        for (CreateRewriteDto request : plan.create()) {
            RewriteDto created = client.createVerified(request);
            if (live.containsKey(created.id())) throw new IllegalStateException("API returned an existing ID");
            owned.put(created.id(), created);
            live.put(created.id(), created);
            saveState(dir, profile, owned);
        }
        // Detect manual edits during creation before starting the deletion phase.
        if (!RewritePlanner.index(client.fetchRewrites()).equals(live))
            throw new IllegalStateException("Concurrent profile edit; stopping before deletions");
        for (RewriteDto old : plan.delete()) {
            if (!RewritePlanner.index(client.fetchRewrites()).equals(live))
                throw new IllegalStateException("Concurrent profile edit; stopping before deletion");
            client.deleteVerified(old.id()); // Errors/timeouts stop immediately; never retry ambiguous mutations.
            owned.remove(old.id());
            live.remove(old.id());
            saveState(dir, profile, owned);
        }
        if (!RewritePlanner.index(client.fetchRewrites()).equals(live))
            throw new IllegalStateException("Final snapshot differs; inspect backup and state");
        Log.common("Rewrites synchronized; ownership state saved");
    }

    /** Explicit migration of individually reviewed legacy IDs; does not write to NextDNS. */
    public void adopt(String profile, Path dir, List<String> ids) {
        if (ids.isEmpty() || new HashSet<>(ids).size() != ids.size())
            throw new IllegalArgumentException("Provide unique, explicitly reviewed rewrite IDs");
        Plan plan = SyncFiles.read(dir.resolve("plan.json"), Plan.class);
        if (plan == null || plan.version() != 1 || !profile.equals(plan.profile()))
            throw new IllegalStateException("Invalid migration preview");
        State state = Files.exists(dir.resolve("state.json")) ? SyncFiles.read(dir.resolve("state.json"), State.class)
                : new State(1, profile, List.of());
        RewritePlanner.validate(state, profile);
        if (!state.equals(plan.state())) throw new IllegalStateException("State changed after preview");
        Map<String, RewriteDto> snapshot = RewritePlanner.index(plan.before());
        if (!snapshot.equals(RewritePlanner.index(client.fetchRewrites())))
            throw new IllegalStateException("Profile changed after preview; inspect a fresh snapshot");
        Map<String, RewriteDto> owned = new LinkedHashMap<>(RewritePlanner.index(state.managed()));
        for (String id : ids) {
            RewriteDto rule = snapshot.get(id);
            if (rule == null || Boolean.FALSE.equals(rule.enabled()))
                throw new IllegalArgumentException("Selected ID is missing or disabled: " + id);
            owned.put(id, rule);
        }
        saveState(dir, profile, owned);
        Log.common("Adopted %s reviewed IDs into the journal; no NextDNS changes made".formatted(ids.size()));
    }

    private void saveState(Path dir, String profile, Map<String, RewriteDto> owned) {
        SyncFiles.write(dir.resolve("state.json"), new State(1, profile, List.copyOf(owned.values())));
    }
}
