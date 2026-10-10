package com.novibe.dns.next_dns.sync;

import com.novibe.dns.next_dns.http.dto.request.CreateRewriteDto;
import com.novibe.dns.next_dns.http.dto.response.rewrite.RewriteDto;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RewritePlannerTest {
    RewriteDto old = new RewriteDto("old", "chatgpt.com", "217.60.245.219");
    CreateRewriteDto want = new CreateRewriteDto("chatgpt.com", "5.175.161.40");
    RewritePlanner.State state = new RewritePlanner.State(1, "abc123", List.of(old));
    RewritePlanner.Plan plan(List<RewriteDto> current, List<CreateRewriteDto> source) {
        return RewritePlanner.plan("abc123", state, current, source, d -> false);
    }
    @Test void replacesOwnedIpOnEuUsSwitch() {
        var p = plan(List.of(old), List.of(want));
        assertEquals(List.of(old), p.delete()); assertEquals(List.of(want), p.create());
    }
    @Test void deletesOwnedDomainsRemovedFromSource() {
        var p = plan(List.of(old), List.of(new CreateRewriteDto("example.com", "1.2.3.4")));
        assertEquals(List.of(old), p.delete());
    }
    @Test void excludesOnlyOwnedDomains() {
        var manual = new RewriteDto("manual", "api.chatgpt.com", "1.1.1.1");
        var p = RewritePlanner.plan("abc123", state, List.of(old, manual),
                List.of(want, new CreateRewriteDto("example.com", "1.2.3.4")), d -> d.endsWith("chatgpt.com"));
        assertEquals(List.of(old), p.delete()); assertEquals(1, p.create().size());
    }
    @Test void manualConflictingAndMatchingRulesAreNotAdoptedOrReplaced() {
        for (String ip : List.of("1.1.1.1", want.content())) {
            var manual = new RewriteDto("manual", want.name(), ip);
            var p = plan(List.of(manual), List.of(want));
            assertTrue(p.delete().isEmpty()); assertTrue(p.create().isEmpty());
            assertEquals(List.of(want.name()), p.conflicts());
        }
    }
    @Test void manuallyEditedOwnedRuleBecomesUnmanaged() {
        var edited = new RewriteDto(old.id(), old.name(), "9.9.9.9");
        var p = plan(List.of(edited), List.of(want));
        assertTrue(p.delete().isEmpty()); assertTrue(p.create().isEmpty());
    }
    @Test void manualDuplicateProtectsDomainWhileOtherStaleDomainsStillClean() {
        var p = plan(List.of(old, new RewriteDto("manual", old.name(), "9.9.9.9")), List.of(want));
        assertTrue(p.delete().isEmpty()); assertTrue(p.create().isEmpty());
    }
    @Test void noStateNeverGuessesOwnershipFromOldProxyIp() {
        var p = RewritePlanner.plan("abc123", new RewritePlanner.State(1, "abc123", List.of()),
                List.of(old), List.of(want), d -> false);
        assertTrue(p.delete().isEmpty()); assertTrue(p.create().isEmpty());
    }
    @Test void emptySourceAndAllExcludedFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> plan(List.of(old), List.of()));
        assertThrows(IllegalArgumentException.class, () -> RewritePlanner.plan("abc123", state, List.of(old), List.of(want), d -> true));
    }
    @Test void wrongProfileAndDuplicateIdsFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> RewritePlanner.plan("def456", state, List.of(old), List.of(want), d -> false));
        assertThrows(IllegalArgumentException.class, () -> plan(List.of(old, old), List.of(want)));
    }
    @Test void unchangedOwnedRulesAreIdempotent() {
        var p = plan(List.of(old), List.of(new CreateRewriteDto(old.name(), old.content())));
        assertTrue(p.create().isEmpty()); assertTrue(p.delete().isEmpty());
    }
    @Test void sourcePriorityIsFirstWins() {
        assertEquals(List.of(want), plan(List.of(), List.of(want, new CreateRewriteDto(want.name(), "8.8.8.8"))).create());
    }
    @Test void removesOnlyOwnedDuplicates() {
        var duplicate = new RewriteDto("duplicate", old.name(), old.content());
        var s = new RewritePlanner.State(1, "abc123", List.of(old, duplicate));
        var p = RewritePlanner.plan("abc123", s, List.of(old, duplicate), List.of(new CreateRewriteDto(old.name(), old.content())), d -> false);
        assertEquals(List.of(duplicate), p.delete()); assertTrue(p.create().isEmpty());
    }
    @Test void manualWildcardAndParentChildOverridesAreNotShadowed() {
        for (String manualName : List.of("*.CHATGPT.COM.", "api.chatgpt.com")) {
            var p = plan(List.of(new RewriteDto("manual", manualName, "9.9.9.9")), List.of(want));
            assertTrue(p.create().isEmpty()); assertTrue(p.delete().isEmpty());
        }
        var p = plan(List.of(new RewriteDto("manual", "chatgpt.com", "9.9.9.9")),
                List.of(new CreateRewriteDto("api.chatgpt.com", "1.2.3.4")));
        assertTrue(p.create().isEmpty());
    }
    @Test void disabledRuleIsPreserved() {
        var disabled = new RewriteDto(old.id(), old.name(), old.content(), false);
        var p = plan(List.of(disabled), List.of(want));
        assertTrue(p.delete().isEmpty()); assertTrue(p.create().isEmpty());
    }
}
