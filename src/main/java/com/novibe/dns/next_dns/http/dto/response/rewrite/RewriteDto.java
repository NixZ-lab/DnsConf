package com.novibe.dns.next_dns.http.dto.response.rewrite;

public record RewriteDto(String id, String name, String content, Boolean enabled) {
    public RewriteDto(String id, String name, String content) { this(id, name, content, null); }
}
