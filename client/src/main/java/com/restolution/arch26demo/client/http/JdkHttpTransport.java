package com.restolution.arch26demo.client.http;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Real HttpTransport backed by the JDK's built-in HttpClient — no extra HTTP dependency needed. */
public class JdkHttpTransport implements HttpTransport {
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public HttpResult postJson(String url, String jsonBody, String bearerToken) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
        if (bearerToken != null) {
            builder.header("authorization", "Bearer " + bearerToken);
        }
        return send(builder.build());
    }

    @Override
    public HttpResult getConditional(String url, String ifNoneMatchEtag) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET();
        if (ifNoneMatchEtag != null) {
            builder.header("If-None-Match", ifNoneMatchEtag);
        }
        return send(builder.build());
    }

    @Override
    public HttpResult putJson(String url, String jsonBody) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .header("content-type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        return send(request);
    }

    private HttpResult send(HttpRequest request) throws IOException {
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            String etag = response.headers().firstValue("ETag").orElse(null);
            return new HttpResult(response.statusCode(), response.body(), etag);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted during HTTP call", e);
        }
    }
}
