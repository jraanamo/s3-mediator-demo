package com.restolution.arch26demo.client;

import com.restolution.arch26demo.client.http.HttpResult;
import com.restolution.arch26demo.client.http.HttpTransport;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

import java.io.IOException;
import java.time.Instant;

/**
 * Owns the current JWT, its expiry, and the current presigned config/catalog
 * URLs. Transparently re-logs in when the token is near expiry or a caller
 * reports a 401 from a Mediator call.
 */
public class AuthClient {
    private static final Logger LOG = LogManager.getLogger(AuthClient.class);
    private static final long EXPIRY_BUFFER_SECONDS = 30;

    private final String backendUrl;
    private final String clientId;
    private final HttpTransport http;

    private String token;
    private Instant expiresAt = Instant.EPOCH;
    private String configUrl;
    private String catalogUrl;

    public AuthClient(String backendUrl, String clientId, HttpTransport http) {
        this.backendUrl = backendUrl;
        this.clientId = clientId;
        this.http = http;
    }

    public synchronized String currentToken() throws IOException {
        ensureValid();
        return token;
    }

    public synchronized String currentConfigUrl() throws IOException {
        ensureValid();
        return configUrl;
    }

    public synchronized String currentCatalogUrl() throws IOException {
        ensureValid();
        return catalogUrl;
    }

    /** Call after any Mediator/Backend call returns 401 to force a fresh login before retrying. */
    public String clientId() {
        return clientId;
    }

    public synchronized void invalidate() {
        expiresAt = Instant.EPOCH;
    }

    private void ensureValid() throws IOException {
        if (token == null || Instant.now().isAfter(expiresAt.minusSeconds(EXPIRY_BUFFER_SECONDS))) {
            login();
        }
    }

    private void login() throws IOException {
        LOG.info("logging in as {}", clientId);
        String requestBody = new JSONObject().put("deviceId", clientId).toString();
        HttpResult result = http.postJson(backendUrl + "/login", requestBody, null);
        if (result.status() != 200) {
            LOG.error("login failed: HTTP {} {}", result.status(), result.body());
            throw new IOException("login failed with HTTP " + result.status());
        }

        JSONObject response = new JSONObject(result.body());
        token = response.getString("token");
        expiresAt = Instant.parse(response.getString("expiresAt"));
        JSONObject resources = response.getJSONObject("resources");
        configUrl = resources.getString("config");
        catalogUrl = resources.getString("catalog");
        LOG.info("login succeeded, token expires at {}", expiresAt);
    }
}
