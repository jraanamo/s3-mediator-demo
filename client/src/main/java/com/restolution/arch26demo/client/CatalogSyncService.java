package com.restolution.arch26demo.client;

import com.restolution.arch26demo.client.http.HttpResult;
import com.restolution.arch26demo.client.http.HttpTransport;
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

    private volatile JSONObject config;
    private volatile JSONArray catalog;
    private String configEtag;
    private String catalogEtag;

    public CatalogSyncService(AuthClient authClient, HttpTransport http) {
        this.authClient = authClient;
        this.http = http;
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
        }
        try {
            pollOne("catalog", authClient.currentCatalogUrl(), catalogEtag, this::applyCatalog);
        } catch (IOException e) {
            LOG.error("catalog poll failed", e);
        }
    }

    private interface BodyHandler {
        void apply(String body, String etag);
    }

    private void pollOne(String name, String url, String etag, BodyHandler onChanged) throws IOException {
        HttpResult result = http.getConditional(url, etag);
        if (result.status() == 304) {
            LOG.info("{} unchanged", name);
            return;
        }
        if (result.status() == 401) {
            LOG.warn("{} poll got 401, forcing re-login and retrying once", name);
            authClient.invalidate();
            String freshUrl = name.equals("config") ? authClient.currentConfigUrl() : authClient.currentCatalogUrl();
            result = http.getConditional(freshUrl, etag);
        }
        if (result.status() == 200) {
            onChanged.apply(result.body(), result.etag());
            LOG.info("{} changed, fetched new version", name);
            return;
        }
        LOG.error("{} poll failed: HTTP {}", name, result.status());
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
