package com.novibe.dns.next_dns.sync;

import java.util.*;

/** Conservative defaults; onboarding and changing limits require separate approval. */
public record SyncPolicy(int maxCreate, int maxUpdate, int maxDelete, int maxDeletePercent,
                         boolean allowNewWww, boolean allowNewOpenai) {
    public SyncPolicy {
        if (maxCreate < 0 || maxUpdate < 0 || maxDelete < 0 || maxDeletePercent < 0 || maxDeletePercent > 100)
            throw new IllegalArgumentException("Invalid Sync limits");
    }
    public static SyncPolicy fromEnvironment() {
        return new SyncPolicy(number("SYNC_MAX_CREATE",20), number("SYNC_MAX_UPDATE",20),
                number("SYNC_MAX_DELETE",20), number("SYNC_MAX_DELETE_PERCENT",5),
                flag("SYNC_ALLOW_NEW_WWW"), flag("SYNC_ALLOW_NEW_OPENAI"));
    }
    private static int number(String key, int fallback) {
        String value=System.getenv(key);
        return value==null || value.isBlank() ? fallback : Integer.parseInt(value);
    }
    public static boolean flag(String key) {
        String value=System.getenv(key);
        if (value==null || value.isBlank()) return false;
        if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException(key+" must be true or false");
        return Boolean.parseBoolean(value);
    }
    public void validate(RewritePlanner.Plan plan) {
        Set<String> removedNames=new HashSet<>();
        plan.delete().forEach(r -> removedNames.add(r.name()));
        Set<String> replacementNames=new HashSet<>();
        plan.create().stream().filter(r -> removedNames.contains(r.name())).forEach(r -> replacementNames.add(r.name()));
        long updates=replacementNames.size();
        long additions=plan.create().stream().filter(r -> !replacementNames.contains(r.name())).count();
        long removals=plan.delete().stream().filter(r -> !replacementNames.contains(r.name())).count();
        if (additions>maxCreate || updates>maxUpdate || plan.delete().size()>maxDelete
                || removals*100L > (long)Math.max(1,plan.state().managed().size())*maxDeletePercent)
            throw new IllegalStateException("Sync change budget exceeded; use a separately reviewed apply");
        for (var r : plan.create()) {
            if (replacementNames.contains(r.name())) continue;
            String n=r.name().toLowerCase(Locale.ROOT);
            String domain=n.startsWith("*.") ? n.substring(2) : n;
            if (!allowNewWww && domain.startsWith("www."))
                throw new IllegalStateException("New www route requires explicit approval");
            if (!allowNewOpenai && (n.contains("openai") || n.contains("chatgpt") || n.contains("oaistatic") || n.contains("oaiusercontent")))
                throw new IllegalStateException("New OpenAI route requires explicit approval");
        }
    }
}
