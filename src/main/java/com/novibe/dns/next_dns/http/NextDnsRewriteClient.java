package com.novibe.dns.next_dns.http;

import com.google.gson.JsonObject;
import com.novibe.dns.next_dns.http.dto.request.CreateRewriteDto;
import com.novibe.dns.next_dns.http.dto.response.rewrite.RewriteDto;
import com.novibe.dns.next_dns.sync.RewritePlanner;
import lombok.Getter;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class NextDnsRewriteClient extends AbstractNextDnsHttpClient {
    @Getter private List<JsonObject> lastSnapshot;
    private long lastRequest;

    // Sequential pacing for reads and writes, including phase-boundary requests.
    protected void pace() {
        long remaining = 1200 - (System.currentTimeMillis() - lastRequest);
        if (remaining > 0) {
            try { Thread.sleep(remaining); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
        }
        lastRequest = System.currentTimeMillis();
    }

    public List<RewriteDto> fetchRewrites() {
        List<RewriteDto> rules = new ArrayList<>();
        List<JsonObject> pages = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String cursor = null;
        do {
            pace();
            JsonObject response = get(path() + (cursor == null ? "" : "?cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8)), JsonObject.class);
            checkErrors(response);
            if (response == null || !response.has("data") || !response.get("data").isJsonArray())
                throw new IllegalStateException("NextDNS returned no rewrite array");
            pages.add(response.deepCopy());
            response.getAsJsonArray("data").forEach(r -> rules.add(jsonMapper.fromJson(r, RewriteDto.class)));
            cursor = null;
            if (response.has("meta") && !response.get("meta").isJsonNull()) {
                JsonObject meta = response.getAsJsonObject("meta");
                if (meta.has("pagination") && !meta.get("pagination").isJsonNull()) {
                    var next = meta.getAsJsonObject("pagination").get("cursor");
                    if (next != null && !next.isJsonNull()) cursor = next.getAsString();
                }
            }
            if (cursor != null && (!seen.add(cursor) || seen.size() > 10000))
                throw new IllegalStateException("Invalid pagination cursor");
        } while (cursor != null);
        RewritePlanner.index(rules);
        lastSnapshot = pages;
        return rules;
    }

    public RewriteDto createVerified(CreateRewriteDto request) {
        pace();
        JsonObject response = post(path(), request, JsonObject.class);
        checkErrors(response);
        if (response == null || !response.has("data") || !response.get("data").isJsonObject())
            throw new IllegalStateException("POST returned no rewrite ID; inspect profile before retrying");
        JsonObject data = response.getAsJsonObject("data");
        if (!data.has("id") || data.get("id").isJsonNull())
            throw new IllegalStateException("POST returned no ID; inspect profile before retrying");
        String id = data.get("id").getAsString();
        // Some API responses contain only the created ID; read back that exact ID, never infer ownership.
        RewriteDto created = data.has("name") && data.has("content")
                ? jsonMapper.fromJson(data, RewriteDto.class)
                : fetchRewrites().stream().filter(r -> id.equals(r.id())).findFirst()
                    .orElseThrow(() -> new IllegalStateException("Created ID not present in read-back"));
        RewritePlanner.index(List.of(created));
        if (!created.name().equals(request.name()) || !created.content().equals(request.content())
                || Boolean.FALSE.equals(created.enabled()))
            throw new IllegalStateException("POST response does not match request");
        return created;
    }

    public void deleteVerified(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Invalid ID");
        pace();
        checkErrors(delete(path() + "/" + id, JsonObject.class));
    }

    static void checkErrors(JsonObject response) {
        if (response != null && response.has("errors") && !response.get("errors").isJsonNull()
                && (!response.get("errors").isJsonArray() || !response.getAsJsonArray("errors").isEmpty()))
            throw new IllegalStateException("NextDNS API returned errors");
    }

    @Override protected String path() { return "/rewrites"; }
}
