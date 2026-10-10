package com.novibe.common.data_sources;

import com.novibe.common.base_structures.BypassRoute;
import com.novibe.common.base_structures.HostsLine;
import org.springframework.stereotype.Service;

import java.util.function.Predicate;

@Service
public class HostsOverrideListsLoader extends ListLoader<BypassRoute> {

    @Override
    protected java.util.List<BypassRoute> parseSource(String body) {
        java.util.List<BypassRoute> routes = new java.util.ArrayList<>();
        for (String raw : body.split("\\R")) {
            String line = raw.split("#", 2)[0].strip();
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\s+");
            if (parts.length < 2) throw new IllegalArgumentException("Invalid redirect hosts line");
            String ip;
            try { ip = java.net.InetAddress.ofLiteral(parts[0]).getHostAddress(); }
            catch (IllegalArgumentException e) { throw new IllegalArgumentException("Invalid redirect IP", e); }
            if (HostsBlockListsLoader.isBlockIp(parts[0]) || ip.equals("0:0:0:0:0:0:0:0")
                    || ip.equals("0:0:0:0:0:0:0:1")) continue;
            for (int i = 1; i < parts.length; i++) {
                String domain = parts[i].toLowerCase(java.util.Locale.ROOT);
                if (domain.endsWith(".")) domain = domain.substring(0, domain.length() - 1);
                String bare = domain.startsWith("*.") ? domain.substring(2) : domain;
                if (bare.length() > 253 || !bare.contains(".")) throw new IllegalArgumentException("Invalid redirect domain");
                for (String label : bare.split("\\.", -1)) {
                    if (!label.matches("[a-z0-9_](?:[a-z0-9_-]{0,61}[a-z0-9_])?"))
                        throw new IllegalArgumentException("Invalid redirect domain");
                }
                routes.add(new BypassRoute(ip, domain));
            }
        }
        if (routes.isEmpty()) throw new IllegalArgumentException("Redirect source contains no usable routes");
        return routes;
    }

    @Override
    protected String listType() {
        return "Override";
    }

    @Override
    protected Predicate<HostsLine> filterRelatedLines() {
        return line -> line.hasIpAndDomain() && !HostsBlockListsLoader.isBlockIp(line.ip());

    }

    @Override
    protected BypassRoute toObject(HostsLine line) {
        return new BypassRoute(line.ip(), line.domain());
    }

}
