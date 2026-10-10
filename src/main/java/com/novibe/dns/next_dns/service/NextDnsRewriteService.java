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
import com.novibe.dns.next_dns.sync.SyncPolicy;
import com.novibe.dns.next_dns.sync.ExecutionReport;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
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
        Plan p = SyncFiles.read(dir.resolve("plan.json"), Plan.class);
        ExecutionReport report = new ExecutionReport(profile, p == null ? 0 : p.conflicts().size());
        try {
            applyChecked(profile, dir, maxDeletes, report);
            report.status="Success";
        } catch(RuntimeException error) {
            report.status="Failed";
            report.failureType=error.getClass().getSimpleName();
            throw error;
        } finally {
            SyncFiles.write(dir.resolve("result.json"), report);
        }
    }

    /** Opt-in onboarding is enforced by runner/workflow; state existence is an independent guard. */
    public void sync(String profile, Path dir, List<BypassRoute> currentRoutes, SyncPolicy policy) {
        if (!Files.isRegularFile(dir.resolve("state.json")))
            throw new IllegalStateException("Sync requires an explicitly onboarded ownership journal");
        Plan p=SyncFiles.read(dir.resolve("plan.json"),Plan.class);
        State state=SyncFiles.read(dir.resolve("state.json"),State.class);
        RewritePlanner.validate(state,profile);
        var target=currentRoutes.stream().map(r -> new CreateRewriteDto(r.website(),r.ip())).toList();
        Plan current=RewritePlanner.plan(profile,state,p.before(),target,exclusions::shouldExclude);
        if (!current.equals(p)) throw new IllegalStateException("Hosts, exclusions or ownership changed after preview");
        policy.validate(p);
        apply(profile,dir,policy.maxDelete());
    }

    private void applyChecked(String profile, Path dir, int maxDeletes, ExecutionReport report) {
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
        Map<String,JsonObject> originalRaw = rawRecords(SyncFiles.read(dir.resolve("backup.json"),JsonArray.class));
        if (!originalRaw.equals(rawRecords(client.getLastSnapshot())))
            throw new IllegalStateException("Full API fields changed after dry run");
        Set<String> removedIds = new HashSet<>();
        Set<String> replacementNames = new HashSet<>();
        Set<String> createNames = new HashSet<>();
        plan.create().forEach(r -> createNames.add(r.name()));
        plan.delete().stream().filter(r -> createNames.contains(r.name())).forEach(r -> replacementNames.add(r.name()));
        SyncFiles.write(dir.resolve("result.json"), report);
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
            report.postRequests++;
            if (!replacementNames.contains(created.name())) report.added++;
            report.operations.add(new ExecutionReport.Event("create",created.id(),created.name(),created.content()));
            saveState(dir, profile, owned);
            SyncFiles.write(dir.resolve("result.json"), report);
        }
        // Detect manual edits during creation before starting the deletion phase.
        if (!RewritePlanner.index(client.fetchRewrites()).equals(live))
            throw new IllegalStateException("Concurrent profile edit; stopping before deletions");
        verifySurvivors(originalRaw,removedIds);
        for (RewriteDto old : plan.delete()) {
            if (!RewritePlanner.index(client.fetchRewrites()).equals(live))
                throw new IllegalStateException("Concurrent profile edit; stopping before deletion");
            verifySurvivors(originalRaw,removedIds);
            client.deleteVerified(old.id()); // Errors/timeouts stop immediately; never retry ambiguous mutations.
            owned.remove(old.id());
            live.remove(old.id());
            removedIds.add(old.id());
            report.deleteRequests++;
            if (replacementNames.contains(old.name())) report.updated++;
            else report.deleted++;
            report.operations.add(new ExecutionReport.Event("delete",old.id(),old.name(),old.content()));
            saveState(dir, profile, owned);
            SyncFiles.write(dir.resolve("result.json"), report);
        }
        if (!RewritePlanner.index(client.fetchRewrites()).equals(live))
            throw new IllegalStateException("Final snapshot differs; inspect backup and state");
        verifySurvivors(originalRaw,removedIds);
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
        if (!rawRecords(SyncFiles.read(dir.resolve("backup.json"),JsonArray.class)).equals(rawRecords(client.getLastSnapshot())))
            throw new IllegalStateException("Full API record changed after migration preview");
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

    private void verifySurvivors(Map<String,JsonObject> original, Set<String> removed) {
        var current=rawRecords(client.getLastSnapshot());
        for (var entry:original.entrySet())
            if (!removed.contains(entry.getKey()) && !entry.getValue().equals(current.get(entry.getKey())))
                throw new IllegalStateException("Full fields of an existing API record changed");
    }
    private static Map<String,JsonObject> rawRecords(JsonArray pages) {
        List<JsonObject> list=new ArrayList<>();
        if (pages==null) throw new IllegalStateException("Missing raw backup");
        pages.forEach(p -> list.add(p.getAsJsonObject()));
        return rawRecords(list);
    }
    private static Map<String,JsonObject> rawRecords(List<JsonObject> pages) {
        Map<String,JsonObject> records=new LinkedHashMap<>();
        if(pages==null) throw new IllegalStateException("Missing API snapshot");
        for(var page:pages) {
            if (!page.has("data") || !page.get("data").isJsonArray()
                    || (page.has("errors") && !page.get("errors").isJsonNull()
                        && (!page.get("errors").isJsonArray() || !page.getAsJsonArray("errors").isEmpty())))
                throw new IllegalStateException("Invalid raw API snapshot");
            for(var item:page.getAsJsonArray("data")) {
                var r=item.getAsJsonObject();
                if(!r.has("id") || records.putIfAbsent(r.get("id").getAsString(),r.deepCopy())!=null)
                    throw new IllegalStateException("Invalid raw API identity");
            }
        }
        return records;
    }
}
