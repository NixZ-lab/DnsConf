package com.novibe.dns.next_dns.http;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.novibe.common.util.Jsonable;
import com.novibe.dns.next_dns.http.dto.request.CreateRewriteDto;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NextDnsRewriteClientTest {
    @Test void realHttp429StopsAfterOneRequestWithoutRetry() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/rewrites",exchange -> {
            calls.incrementAndGet();
            byte[] bytes="{\"errors\":[{\"code\":\"rate_limit\"}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Retry-After","60");
            exchange.sendResponseHeaders(429,bytes.length);
            exchange.getResponseBody().write(bytes);exchange.close();
        });
        server.start();
        try {
            var c=new NextDnsRewriteClient() {
                @Override protected void pace() {}
                @Override protected String apiUrl() {return "http://127.0.0.1:"+server.getAddress().getPort();}
                @Override protected String authHeaderValue() {return "synthetic-test-only";}
            };
            c.setHttpClient(java.net.http.HttpClient.newHttpClient());c.setJsonMapper(new Gson());
            var error=assertThrows(com.novibe.common.exception.DnsHttpError.class,
                () -> c.createVerified(new CreateRewriteDto("example.com","1.2.3.4")));
            assertEquals(429,error.getCode());assertEquals(1,calls.get());
        } finally {server.stop(0);}
    }
    static class Client extends NextDnsRewriteClient {
        Deque<String> replies = new ArrayDeque<>(); List<String> paths = new ArrayList<>();
        Client(String... values) { replies.addAll(List.of(values)); setJsonMapper(new Gson()); }
        @Override protected void pace() {}
        @Override protected <T, R extends Jsonable> T sendRequest(String method, String path, R body, Class<T> type) {
            paths.add(method + " " + path); String reply = replies.removeFirst(); return reply.equals("null") ? null : new Gson().fromJson(reply, type);
        }
    }
    @Test void readsEveryPageAndKeepsRawBackupFields() {
        var c = new Client("""
            {"data":[{"id":"a","name":"one.com","content":"1.2.3.4","unknown":"preserve"}],"meta":{"pagination":{"cursor":"next"}}}
            """, """
            {"data":[{"id":"b","name":"two.com","content":"1.2.3.4"}],"meta":{"pagination":{"cursor":null}}}
            """);
        assertEquals(2, c.fetchRewrites().size()); assertEquals(2, c.getLastSnapshot().size());
        assertEquals("GET /rewrites?cursor=next", c.paths.getLast());
        assertTrue(c.getLastSnapshot().getFirst().toString().contains("preserve"));
    }
    @Test void failedOrMissingArrayIsNeverAnEmptyProfile() {
        for (String body : List.of("null", "{}", "{\"errors\":[{\"code\":\"invalid\"}]}", "{\"data\":null}"))
            assertThrows(IllegalStateException.class, () -> new Client(body).fetchRewrites());
    }
    @Test void repeatedCursorFailsClosed() {
        String body = "{\"data\":[],\"meta\":{\"pagination\":{\"cursor\":\"same\"}}}";
        assertThrows(IllegalStateException.class, () -> new Client(body, body).fetchRewrites());
    }
    @Test void postMustReturnVerifiableIdentityAndValue() {
        var r = new CreateRewriteDto("one.com", "1.2.3.4");
        assertEquals("new", new Client("{\"data\":{\"id\":\"new\",\"name\":\"one.com\",\"content\":\"1.2.3.4\"}}").createVerified(r).id());
        assertThrows(IllegalStateException.class, () -> new Client("{}").createVerified(r));
        assertThrows(IllegalStateException.class, () -> new Client("{\"errors\":[{\"code\":\"invalid\"}]}").createVerified(r));
    }
    @Test void idOnlyCreateResponseIsVerifiedByExactIdReadback() {
        var c = new Client("{\"data\":{\"id\":\"new\"}}",
                "{\"data\":[{\"id\":\"new\",\"name\":\"one.com\",\"content\":\"1.2.3.4\"}]}");
        assertEquals("new", c.createVerified(new CreateRewriteDto("one.com", "1.2.3.4")).id());
        assertEquals(List.of("POST /rewrites", "GET /rewrites"), c.paths);
    }
    @Test void deleteHandles204ButNot200ErrorEnvelope() {
        new Client("null").deleteVerified("old");
        assertThrows(IllegalStateException.class, () -> new Client("{\"errors\":[{\"code\":\"invalid\"}]}").deleteVerified("old"));
    }
}
