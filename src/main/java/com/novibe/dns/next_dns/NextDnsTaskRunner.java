package com.novibe.dns.next_dns;

import com.novibe.common.DnsTaskRunner;
import com.novibe.common.base_structures.BypassRoute;
import com.novibe.common.util.DonorDnsUtils;
import com.novibe.common.util.EnvParser;
import com.novibe.common.util.Log;
import com.novibe.dns.next_dns.service.NextDnsDenyService;
import com.novibe.dns.next_dns.service.NextDnsRewriteService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.nio.file.Path;
import java.util.List;
import static com.novibe.common.config.EnvironmentVariables.*;

@Service
@RequiredArgsConstructor
public class NextDnsTaskRunner extends DnsTaskRunner {
    private final NextDnsRewriteService rewrites;
    private final NextDnsDenyService denys;

    @Override public void greetingMessage() {
        Log.global("NextDNS safe synchronization: preview by default; empty inputs never clear settings");
    }
    @Override protected void process() {
        boolean dryRun = booleanSetting("DRY_RUN", true);
        Path dir = Path.of(System.getenv().getOrDefault("SYNC_DIR", "nextdns-sync"), dnsProfile.clientId());
        if (!dryRun) {
            // Apply exactly the saved target, without downloading a potentially changed hosts file.
            List<String> adoptIds = EnvParser.parse(System.getenv("ADOPT_IDS"));
            if (!adoptIds.isEmpty()) rewrites.adopt(dnsProfile.clientId(), dir, adoptIds);
            else rewrites.apply(dnsProfile.clientId(), dir,
                    Integer.parseInt(System.getenv().getOrDefault("MAX_DELETE", "0")));
            return;
        }
        try { java.nio.file.Files.deleteIfExists(dir.resolve("plan.json")); }
        catch (java.io.IOException e) { throw new IllegalStateException("Cannot invalidate old plan", e); }
        List<String> sources = EnvParser.parse(REDIRECT);
        if (sources.isEmpty()) throw new IllegalStateException("REDIRECT is empty; no profile changes made");
        // Fetch ALL inputs before producing a usable plan. Block writes are deliberately separate.
        List<String> blockSources = EnvParser.parse(BLOCK);
        if (!blockSources.isEmpty()) {
            List<String> blocks = blockListsLoader.fetchWebsites(blockSources);
            Log.common("BLOCK preview only: %s potential additions; denylist will not be changed by rewrite apply"
                    .formatted(denys.omitExistingDenys(blocks).size()));
        }
        List<BypassRoute> routes = overrideListsLoader.fetchWebsites(sources);
        if (dnsProfile.donorDns() != null) DonorDnsUtils.replaceIPs(routes, dnsProfile);
        rewrites.prepare(dnsProfile.clientId(), routes, dir);
    }
    static boolean booleanSetting(String key, boolean fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) return fallback;
        if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException(key + " must be true or false");
        return Boolean.parseBoolean(value);
    }
    @Override protected void finishMessage() { Log.global("NextDNS phase completed"); }
}
