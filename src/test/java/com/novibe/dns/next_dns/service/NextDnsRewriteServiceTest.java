package com.novibe.dns.next_dns.service;

import com.novibe.common.base_structures.BypassRoute;
import com.novibe.common.data_sources.ExcludeRedirectSettingsLoader;
import com.novibe.common.service.ExcludeRedirectCheckService;
import com.novibe.dns.next_dns.http.NextDnsRewriteClient;
import com.novibe.dns.next_dns.http.dto.request.CreateRewriteDto;
import com.novibe.dns.next_dns.http.dto.response.rewrite.RewriteDto;
import com.novibe.dns.next_dns.sync.RewritePlanner.*;
import com.novibe.dns.next_dns.sync.SyncFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NextDnsRewriteServiceTest {
    @TempDir Path dir;
    final RewriteDto old = new RewriteDto("old", "chatgpt.com", "217.60.245.219");
    final List<BypassRoute> routes = List.of(new BypassRoute("5.175.161.40", "chatgpt.com"));
    final FakeClient client = new FakeClient();
    NextDnsRewriteService service() {
        return new NextDnsRewriteService(client, new ExcludeRedirectCheckService(new ExcludeRedirectSettingsLoader() {
            @Override public List<String> loadIgnoredDomains() { return List.of(); }
        }));
    }
    void prepare() {
        client.live.add(old);
        SyncFiles.write(dir.resolve("state.json"), new State(1, "abc123", List.of(old)));
        service().prepare("abc123", routes, dir);
    }
    @Test void dryRunWritesBackupAndPlanButNeverMutatesProfileOrState() throws Exception {
        client.live.add(old);
        service().prepare("abc123", routes, dir);
        assertTrue(client.operations.isEmpty());
        assertTrue(Files.exists(dir.resolve("backup.json")));
        assertTrue(Files.exists(dir.resolve("plan.json")));
        assertFalse(Files.exists(dir.resolve("state.json")));
    }
    @Test void replacementCreatedBeforeDeleteAndJournalMakesNextRunIdempotent() {
        prepare(); service().apply("abc123", dir, 1);
        assertEquals(List.of("create", "delete"), client.operations);
        assertEquals("5.175.161.40", client.live.getFirst().content());
        var p = service().prepare("abc123", routes, dir);
        assertTrue(p.create().isEmpty()); assertTrue(p.delete().isEmpty());
    }
    @Test void changedSnapshotAbortsBeforeAnyMutation() {
        prepare(); client.live.add(new RewriteDto("manual", "my.home", "192.168.1.1"));
        assertThrows(IllegalStateException.class, () -> service().apply("abc123", dir, 1));
        assertTrue(client.operations.isEmpty());
    }
    @Test void postFailureDoesNotDeleteWorkingRule() {
        prepare(); client.failCreate = true;
        assertThrows(IllegalStateException.class, () -> service().apply("abc123", dir, 1));
        assertEquals(List.of(old), client.live);
        assertFalse(client.operations.contains("delete"));
    }
    @Test void deleteFailureRetainsBothRulesAndJournalForRecovery() {
        prepare(); client.failDelete = true;
        assertThrows(IllegalStateException.class, () -> service().apply("abc123", dir, 1));
        assertEquals(2, client.live.size());
        assertEquals(2, SyncFiles.read(dir.resolve("state.json"), State.class).managed().size());
        client.failDelete = false;
        service().prepare("abc123", routes, dir); service().apply("abc123", dir, 1);
        assertEquals(1, client.live.size());
    }
    @Test void deletionBudgetAndMissingBackupPreventEveryWrite() throws Exception {
        prepare();
        assertThrows(IllegalStateException.class, () -> service().apply("abc123", dir, 0));
        Files.delete(dir.resolve("backup.json"));
        assertThrows(IllegalStateException.class, () -> service().apply("abc123", dir, 1));
        assertTrue(client.operations.isEmpty());
    }
    @Test void emptySourceCreatesNoPlanOrMutations() {
        assertThrows(IllegalArgumentException.class, () -> service().prepare("abc123", List.of(), dir));
        assertFalse(Files.exists(dir.resolve("plan.json"))); assertTrue(client.operations.isEmpty());
    }
    @Test void corruptAndWrongProfileStateAreNotReset() throws Exception {
        Files.writeString(dir.resolve("state.json"), "broken");
        assertThrows(RuntimeException.class, () -> service().prepare("abc123", routes, dir));
        SyncFiles.write(dir.resolve("state.json"), new State(1, "def456", List.of()));
        assertThrows(RuntimeException.class, () -> service().prepare("abc123", routes, dir));
        assertTrue(client.operations.isEmpty());
    }
    @Test void stateChangeInvalidatesReviewedPlan() {
        prepare(); SyncFiles.write(dir.resolve("state.json"), new State(1, "abc123", List.of()));
        assertThrows(IllegalStateException.class, () -> service().apply("abc123", dir, 1));
        assertTrue(client.operations.isEmpty());
    }
    @Test void tamperedDeletionIsRejected() {
        prepare(); var p = SyncFiles.read(dir.resolve("plan.json"), Plan.class);
        var manual = new RewriteDto("manual", "my.home", "192.168.1.1");
        SyncFiles.write(dir.resolve("plan.json"), new Plan(1, p.profile(), p.state(), p.before(), p.desired(), p.create(), List.of(manual), p.conflicts()));
        assertThrows(IllegalStateException.class, () -> service().apply("abc123", dir, 1));
        assertTrue(client.operations.isEmpty());
    }
    @Test void adoptsOnlyExplicitReviewedIdsWithoutApiWrites() {
        client.live.add(old);
        client.live.add(new RewriteDto("manual", "my.home", "192.168.1.1"));
        service().prepare("abc123", routes, dir);
        service().adopt("abc123", dir, List.of(old.id()));
        assertTrue(client.operations.isEmpty());
        assertEquals(List.of(old), SyncFiles.read(dir.resolve("state.json"), State.class).managed());
        var p = service().prepare("abc123", routes, dir);
        assertEquals(List.of(old), p.delete());
    }
    @Test void adoptionRejectsMissingIdsAndChangedSnapshot() {
        client.live.add(old); service().prepare("abc123", routes, dir);
        assertThrows(IllegalArgumentException.class, () -> service().adopt("abc123", dir, List.of("missing")));
        assertFalse(Files.exists(dir.resolve("state.json")));
        client.live.clear();
        assertThrows(IllegalStateException.class, () -> service().adopt("abc123", dir, List.of(old.id())));
        assertTrue(client.operations.isEmpty());
    }
    @Test void failedPreviewInvalidatesPreviousLocalPlan() {
        prepare();
        assertThrows(IllegalArgumentException.class, () -> service().prepare("abc123", List.of(), dir));
        assertFalse(Files.exists(dir.resolve("plan.json")));
    }
    static class FakeClient extends NextDnsRewriteClient {
        List<RewriteDto> live = new ArrayList<>(); List<String> operations = new ArrayList<>();
        boolean failCreate, failDelete;
        @Override public List<RewriteDto> fetchRewrites() { return List.copyOf(live); }
        @Override public List<com.google.gson.JsonObject> getLastSnapshot() { return List.of(new com.google.gson.JsonObject()); }
        @Override public RewriteDto createVerified(CreateRewriteDto r) {
            operations.add("create"); if (failCreate) throw new IllegalStateException("POST failed");
            var created = new RewriteDto("new" + live.size(), r.name(), r.content()); live.add(created); return created;
        }
        @Override public void deleteVerified(String id) {
            operations.add("delete"); if (failDelete) throw new IllegalStateException("429");
            live.removeIf(r -> r.id().equals(id));
        }
    }
}
