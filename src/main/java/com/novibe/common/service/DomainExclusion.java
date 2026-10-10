package com.novibe.common.service;

import com.novibe.common.util.DataParser;
import java.util.Locale;

/** Explicit modes preserve literal www; unprefixed entries retain legacy semantics. */
public record DomainExclusion(String domain, boolean exact) {
    public static DomainExclusion parse(String token) {
        String value = token.strip().toLowerCase(Locale.ROOT);
        boolean exact = value.startsWith("exact:");
        boolean tree = value.startsWith("tree:");
        String domain = exact || tree ? value.substring(value.indexOf(':') + 1) : DataParser.removeWWW(value);
        if (exact || tree) domain = domain.replaceFirst("\\.$", "");
        if (domain.isBlank() || domain.contains(":") || !domain.matches("(?:\\*\\.)?[a-z0-9_-]+(?:\\.[a-z0-9_-]+)+"))
            throw new IllegalArgumentException("Invalid domain exclusion");
        return new DomainExclusion(domain, exact);
    }
    public boolean matches(String name) {
        String key = name.toLowerCase(Locale.ROOT).replaceFirst("\\.$", "");
        return key.equals(domain) || (!exact && key.endsWith("." + domain));
    }
}
