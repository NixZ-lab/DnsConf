package com.novibe.dns.next_dns.sync;

import com.novibe.common.base_structures.BypassRoute;
import com.novibe.common.data_sources.ListLoader.SourceDigest;
import java.util.List;

public record SourceEvidence(String codeCommit, List<SourceDigest> sources, String exclusions,
                             List<BypassRoute> routes) {
    public void verify(SourceEvidence current) {
        if (codeCommit==null || !codeCommit.matches("[0-9a-f]{40}") || sources==null || sources.isEmpty()
                || !equals(current))
            throw new IllegalStateException("Code, hosts body, exclusions or effective routes changed after planning");
    }
}
