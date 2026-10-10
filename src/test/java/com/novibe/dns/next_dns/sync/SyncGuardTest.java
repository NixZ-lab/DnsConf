package com.novibe.dns.next_dns.sync;

import com.novibe.common.base_structures.BypassRoute;
import com.novibe.common.data_sources.ListLoader.SourceDigest;
import com.novibe.dns.next_dns.http.dto.response.rewrite.RewriteDto;
import com.novibe.dns.next_dns.http.dto.request.CreateRewriteDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SyncGuardTest {
    @TempDir Path dir;
    final SyncPolicy policy=new SyncPolicy(20,20,20,5,false,false);
    RewritePlanner.Plan plan(List<RewriteDto> owned,List<RewriteDto> live,List<CreateRewriteDto> desired) {
        return RewritePlanner.plan("abc123",new RewritePlanner.State(1,"abc123",owned),live,desired,n->false);
    }
    @Test void replacementDoesNotTripMassRemovalGuardEvenForSingleManagedRule() {
        var old=new RewriteDto("old","example.com","1.2.3.4");
        assertDoesNotThrow(()->policy.validate(plan(List.of(old),List.of(old),List.of(new CreateRewriteDto("example.com","2.3.4.5")))));
    }
    @Test void entireSourceReplacementFailsBeforeAnyWrites() {
        var old=new RewriteDto("old","example.com","1.2.3.4");
        assertThrows(IllegalStateException.class,()->policy.validate(plan(List.of(old),List.of(old),List.of(new CreateRewriteDto("new.com","2.3.4.5")))));
    }
    @Test void newWwwAndOpenaiRequireIndependentApproval() {
        for(String name:List.of("www.example.com","api.openai.com","oaistatic.com","chatgpt.com"))
            assertThrows(IllegalStateException.class,()->policy.validate(plan(List.of(),List.of(),List.of(new CreateRewriteDto(name,"1.2.3.4")))));
        assertThrows(IllegalStateException.class,()->new SyncPolicy(20,20,20,5,true,false).validate(plan(List.of(),List.of(),List.of(new CreateRewriteDto("www.openai.com","1.2.3.4")))));
    }
    @Test void ownedWwwReplacementIsAnUpdateNotNewAlias() {
        var old=new RewriteDto("old","www.example.com","1.2.3.4");
        assertDoesNotThrow(()->policy.validate(plan(List.of(old),List.of(old),List.of(new CreateRewriteDto("www.example.com","2.3.4.5")))));
    }
    @Test void regularAdditionAndNoOpPassButCreationBudgetFailsClosed() {
        var p=plan(List.of(),List.of(),List.of(new CreateRewriteDto("example.com","1.2.3.4")));
        assertDoesNotThrow(()->policy.validate(p));
        assertThrows(IllegalStateException.class,()->new SyncPolicy(0,20,20,5,false,false).validate(p));
        var old=new RewriteDto("old","example.com","1.2.3.4");
        assertDoesNotThrow(()->policy.validate(plan(List.of(old),List.of(old),List.of(new CreateRewriteDto("example.com","1.2.3.4")))));
    }
    @Test void rawHostsOrCodeChangeInvalidatesEvidenceEvenWhenEffectiveRoutesSame() {
        var routes=List.of(new BypassRoute("1.2.3.4","example.com"));String sha="a".repeat(40);
        var saved=new SourceEvidence(sha,List.of(new SourceDigest("https://example.test/hosts","body1")),"exact:www.openai.com",routes);
        assertDoesNotThrow(()->saved.verify(saved));
        assertThrows(IllegalStateException.class,()->saved.verify(new SourceEvidence(sha,List.of(new SourceDigest("https://example.test/hosts","body2")),saved.exclusions(),routes)));
        assertThrows(IllegalStateException.class,()->saved.verify(new SourceEvidence("b".repeat(40),saved.sources(),saved.exclusions(),routes)));
        assertThrows(IllegalStateException.class,()->saved.verify(new SourceEvidence(sha,saved.sources(),"tree:openai.com",routes)));
    }
    @Test void concurrentInvocationFailsButLockIsReleasedAfterFailure() {
        try(var first=ProfileLock.acquire(dir)) {assertThrows(IllegalStateException.class,()->ProfileLock.acquire(dir));}
        assertDoesNotThrow(()->{try(var second=ProfileLock.acquire(dir)) {}});
    }
}
