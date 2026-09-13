package fi.kinetik.arch26demo.client.http;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Real HttpTransport backed by the JDK's built-in HttpClient — no extra HTTP dependency needed. */
public class JdkHttpTransport implements HttpTransport {
    private static final Logger LOG = LogManager.getLogger(JdkHttpTransport.class);

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
        long startNanos = System.nanoTime();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
            LOG.info("{} {} -> HTTP {} in {}ms", request.method(), withoutQuery(request.uri()), response.statusCode(), elapsedMs);
            String etag = response.headers().firstValue("ETag").orElse(null);
            return new HttpResult(response.statusCode(), response.body(), etag);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted during HTTP call", e);
        } catch (IOException e) {
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
            LOG.info("{} {} -> failed after {}ms: {}", request.method(), withoutQuery(request.uri()), elapsedMs, e.getMessage());
            throw e;
        }
    }

    // Presigned URLs carry a long signature query string that's just noise in
    // the logs (and not secret enough to matter either way, but unreadable).
    private static String withoutQuery(URI uri) {
        return uri.getScheme() + "://" + uri.getAuthority() + uri.getPath();
    }
}
