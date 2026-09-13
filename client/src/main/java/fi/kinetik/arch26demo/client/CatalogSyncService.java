package fi.kinetik.arch26demo.client;

import fi.kinetik.arch26demo.client.http.HttpResult;
import fi.kinetik.arch26demo.client.http.HttpTransport;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;

/**
 * Polls the presigned config/catalog URLs with conditional GET (ETag) so the
 * client detects updates without the Mediator being able to push them.
 */
public class CatalogSyncService {
    private static final Logger LOG = LogManager.getLogger(CatalogSyncService.class);

    private final AuthClient authClient;
    private final HttpTransport http;
    private final MonitorReporter reporter;

    private volatile JSONObject config;
    private volatile JSONArray catalog;
    private String configEtag;
    private String catalogEtag;

    public CatalogSyncService(AuthClient authClient, HttpTransport http, MonitorReporter reporter) {
        this.authClient = authClient;
        this.http = http;
        this.reporter = reporter;
    }

    public JSONObject currentConfig() {
        return config;
    }

    public JSONArray currentCatalog() {
        return catalog;
    }

    public void poll() {
        try {
            pollOne("config", authClient.currentConfigUrl(), configEtag, this::applyConfig);
        } catch (IOException e) {
            LOG.error("config poll failed", e);
            reporter.report("config-check", "error", null, e.getMessage());
        }
        try {
            pollOne("catalog", authClient.currentCatalogUrl(), catalogEtag, this::applyCatalog);
        } catch (IOException e) {
            LOG.error("catalog poll failed", e);
            reporter.report("catalog-check", "error", null, e.getMessage());
        }
    }

    private interface BodyHandler {
        void apply(String body, String etag);
    }

    private void pollOne(String name, String url, String etag, BodyHandler onChanged) throws IOException {
        String eventType = name + "-check";
        long startedAt = System.nanoTime();
        HttpResult result = http.getConditional(url, etag);
        long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
        if (result.status() == 304) {
            LOG.info("{} unchanged", name);
            reporter.report(eventType, "unchanged", null, null, durationMs);
            return;
        }
        if (result.status() == 401) {
            LOG.warn("{} poll got 401, forcing re-login and retrying once", name);
            authClient.invalidate();
            String freshUrl = name.equals("config") ? authClient.currentConfigUrl() : authClient.currentCatalogUrl();
            startedAt = System.nanoTime();
            result = http.getConditional(freshUrl, etag);
            durationMs = (System.nanoTime() - startedAt) / 1_000_000;
        }
        if (result.status() == 200) {
            onChanged.apply(result.body(), result.etag());
            LOG.info("{} changed, fetched new version", name);
            reporter.report(eventType, "ok", null, null, durationMs);
            return;
        }
        LOG.error("{} poll failed: HTTP {}", name, result.status());
        reporter.report(eventType, "error", null, "HTTP " + result.status(), durationMs);
    }

    private void applyConfig(String body, String etag) {
        config = new JSONObject(body);
        configEtag = etag;
    }

    private void applyCatalog(String body, String etag) {
        catalog = new JSONArray(body);
        catalogEtag = etag;
    }
}
