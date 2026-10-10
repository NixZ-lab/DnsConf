package com.novibe.dns.next_dns.sync;

import com.novibe.dns.next_dns.http.dto.request.CreateRewriteDto;
import com.novibe.dns.next_dns.http.dto.response.rewrite.RewriteDto;

import java.util.*;
import java.util.function.Predicate;

/** Ownership is an exact API ID + last-written value, never a domain/IP heuristic. */
public final class RewritePlanner {
    public record State(int version, String profile, List<RewriteDto> managed) {}
    public record Plan(int version, String profile, State state, List<RewriteDto> before,
                       List<CreateRewriteDto> desired, List<CreateRewriteDto> create,
                       List<RewriteDto> delete, List<String> conflicts) {}

    public static Map<String, RewriteDto> index(List<RewriteDto> rules) {
        if (rules == null) throw new IllegalArgumentException("Missing rewrites; refusing to treat as empty");
        Map<String, RewriteDto> result = new LinkedHashMap<>();
        for (RewriteDto r : rules) {
            if (r == null || r.id() == null || !r.id().matches("[A-Za-z0-9_-]+")
                    || r.name() == null || r.name().isBlank() || r.content() == null || r.content().isBlank()
                    || result.putIfAbsent(r.id(), r) != null) {
                throw new IllegalArgumentException("Invalid or duplicate rewrite ID");
            }
        }
        return result;
    }

    public static void validate(State state, String profile) {
        if (state == null || state.version() != 1 || !profile.equals(state.profile()))
            throw new IllegalArgumentException("Missing, unsupported or wrong-profile ownership state");
        index(state.managed());
    }

    private static String domainKey(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        if (key.startsWith("*.")) key = key.substring(2);
        if (key.endsWith(".")) key = key.substring(0, key.length() - 1);
        return key;
    }

    private static boolean conflictsWithManual(String name, Set<String> manual) {
        String key = domainKey(name);
        return manual.stream().map(RewritePlanner::domainKey)
                .anyMatch(other -> key.equals(other) || key.endsWith("." + other) || other.endsWith("." + key));
    }

    public static Plan plan(String profile, State state, List<RewriteDto> current,
                            List<CreateRewriteDto> source, Predicate<String> excluded) {
        validate(state, profile);
        index(current);
        if (source == null || source.isEmpty())
            throw new IllegalArgumentException("Empty redirect source; no changes are allowed");
        Map<String, CreateRewriteDto> desired = new TreeMap<>();
        for (CreateRewriteDto r : source) {
            if (r == null || r.name() == null || r.name().isBlank() || r.content() == null || r.content().isBlank())
                throw new IllegalArgumentException("Invalid source rewrite");
            if (!excluded.test(r.name())) desired.putIfAbsent(r.name(), r);
        }
        if (desired.isEmpty()) throw new IllegalArgumentException("All redirects excluded; refusing an empty target");
        Map<String, RewriteDto> managed = index(state.managed());
        Set<String> manualDomains = new HashSet<>();
        for (RewriteDto r : current) {
            if (!r.equals(managed.get(r.id())) || Boolean.FALSE.equals(r.enabled())) manualDomains.add(r.name());
        }
        List<CreateRewriteDto> create = new ArrayList<>();
        List<RewriteDto> delete = new ArrayList<>();
        Set<String> retained = new HashSet<>();
        List<String> conflicts = new ArrayList<>();
        for (RewriteDto r : current) {
            if (!r.equals(managed.get(r.id())) || Boolean.FALSE.equals(r.enabled())) continue;
            CreateRewriteDto want = desired.get(r.name());
            if (conflictsWithManual(r.name(), manualDomains) && want != null) continue;
            if (want == null || !want.content().equals(r.content()) || !retained.add(r.name())) delete.add(r);
        }
        for (CreateRewriteDto want : desired.values()) {
            if (conflictsWithManual(want.name(), manualDomains)) {
                conflicts.add(want.name()); // Includes matching manual values: never silently adopt them.
            } else if (!retained.contains(want.name())) create.add(want);
        }
        return new Plan(1, profile, state, List.copyOf(current), List.copyOf(desired.values()),
                create, delete, conflicts);
    }
}
