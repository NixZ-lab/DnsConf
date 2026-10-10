package com.novibe.common.data_sources;

import com.novibe.common.base_structures.HostsLine;
import com.novibe.common.exception.UserInputException;
import com.novibe.common.util.DataParser;
import com.novibe.common.util.Log;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.StructuredTaskScope;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Setter(onMethod_ = @Autowired)
public abstract class ListLoader<T> {

    public record SourceDigest(String url, String sha256) {}
    private List<SourceDigest> lastSources = List.of();
    public List<SourceDigest> getLastSources() { return lastSources; }

    private HttpClient client;

    protected abstract T toObject(HostsLine hostsLine);

    protected abstract String listType();

    protected abstract Predicate<HostsLine> filterRelatedLines();

    @SuppressWarnings("preview")
    public List<T> fetchWebsites(List<String> urls) {
        lastSources = List.of();
        try (var scope = StructuredTaskScope.open()) {
            List<StructuredTaskScope.Subtask<String>> requests = new ArrayList<>();
            urls.stream()
                    .map(url -> scope.fork(() -> fetchList(url)))
                    .forEach(requests::add);
            scope.join();

            List<String> bodies = requests.stream().map(StructuredTaskScope.Subtask::get).toList();
            List<T> result = bodies.stream()
                    .flatMap(body -> parseSource(body).stream())
                    .distinct()
                    .collect(Collectors.toCollection(ArrayList::new));
            List<SourceDigest> digests = new ArrayList<>();
            for (int i=0; i<urls.size(); i++) {
                try {
                    byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(bodies.get(i).getBytes(StandardCharsets.UTF_8));
                    digests.add(new SourceDigest(urls.get(i), java.util.HexFormat.of().formatHex(hash)));
                } catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
            }
            lastSources=List.copyOf(digests);
            return result;
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    protected List<T> parseSource(String body) {
        return DataParser.splitByEol(body).map(String::strip)
                .filter(line -> !line.isBlank() && !DataParser.isComment(line))
                .map(String::toLowerCase).map(DataParser::parseHostsLine)
                .filter(Objects::nonNull).filter(filterRelatedLines())
                .map(this::toObject).toList();
    }

    private String fetchList(String url) throws IOException, InterruptedException {
        Log.io("Loading %s list from url: %s".formatted(listType(), url));
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(java.time.Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() > 299) {
            throw UserInputException.noStackTrace("Failed to load %s list, response code %s from url: %s"
                    .formatted(listType(), response.statusCode(), url));
        }
        return response.body();
    }

}
