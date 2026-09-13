package fi.kinetik.arch26demo.client.selfcheck;

import fi.kinetik.arch26demo.client.AuthClient;
import fi.kinetik.arch26demo.client.CatalogSyncService;
import fi.kinetik.arch26demo.client.MonitorReporter;
import fi.kinetik.arch26demo.client.http.HttpResult;

import java.time.Instant;

public final class CatalogSyncServiceChecks {
    private CatalogSyncServiceChecks() {
    }

    public static void conditionalGetReplacesOnlyOnChange() {
        FakeHttpTransport http = new FakeHttpTransport();
        http.queuePost(new HttpResult(200,
                "{\"token\":\"t\",\"expiresAt\":\"" + Instant.now().plusSeconds(3600) + "\","
                        + "\"resources\":{\"config\":\"http://config-url\",\"catalog\":\"http://catalog-url\"},"
                        + "\"upload\":{\"endpoint\":\"http://s3\",\"region\":\"us-east-1\",\"bucket\":\"b\","
                        + "\"keyPrefix\":\"inbox/client-1/\",\"accessKeyId\":\"ak\",\"secretAccessKey\":\"sk\","
                        + "\"sessionToken\":\"st\",\"expiration\":\"" + Instant.now().plusSeconds(3600) + "\"}}",
                null));

        // poll 1: both changed (200)
        http.queueGet(new HttpResult(200, "{\"a\":1}", "e1"));
        http.queueGet(new HttpResult(200, "[{\"id\":\"x\"}]", "e2"));
        // poll 2: both unchanged (304)
        http.queueGet(new HttpResult(304, null, null));
        http.queueGet(new HttpResult(304, null, null));
        // poll 3: both changed again (200)
        http.queueGet(new HttpResult(200, "{\"a\":2}", "e3"));
        http.queueGet(new HttpResult(200, "[{\"id\":\"y\"}]", "e4"));

        AuthClient authClient = new AuthClient("http://backend", "client-1", http);
        MonitorReporter reporter = new MonitorReporter(authClient, http, "http://backend");
        CatalogSyncService sync = new CatalogSyncService(authClient, http, reporter);

        sync.poll();
        Check.equal(1, sync.currentConfig().getInt("a"), "poll 1 should apply the fetched config");
        Check.equal("x", sync.currentCatalog().getJSONObject(0).getString("id"), "poll 1 should apply the fetched catalog");

        sync.poll();
        Check.equal(1, sync.currentConfig().getInt("a"), "poll 2 (304) should leave config unchanged");
        Check.equal("x", sync.currentCatalog().getJSONObject(0).getString("id"), "poll 2 (304) should leave catalog unchanged");

        sync.poll();
        Check.equal(2, sync.currentConfig().getInt("a"), "poll 3 should apply the newly changed config");
        Check.equal("y", sync.currentCatalog().getJSONObject(0).getString("id"), "poll 3 should apply the newly changed catalog");
    }
}
