package com.restolution.arch26demo.client.selfcheck;

import com.restolution.arch26demo.client.AuthClient;
import com.restolution.arch26demo.client.MonitorReporter;
import com.restolution.arch26demo.client.http.HttpResult;

import java.time.Instant;

public final class MonitorReporterChecks {
    private MonitorReporterChecks() {
    }

    public static void reportNeverPropagatesFailure() {
        FakeHttpTransport http = new FailingPostTransport();
        http.queuePost(new HttpResult(200,
                "{\"token\":\"t\",\"expiresAt\":\"" + Instant.now().plusSeconds(3600) + "\","
                        + "\"resources\":{\"config\":\"http://config-url\",\"catalog\":\"http://catalog-url\"},"
                        + "\"upload\":{\"endpoint\":\"http://s3\",\"region\":\"us-east-1\",\"bucket\":\"b\","
                        + "\"keyPrefix\":\"inbox/client-1/\",\"accessKeyId\":\"ak\",\"secretAccessKey\":\"sk\","
                        + "\"sessionToken\":\"st\",\"expiration\":\"" + Instant.now().plusSeconds(3600) + "\"}}",
                null));
        AuthClient authClient = new AuthClient("http://backend", "client-1", http);
        MonitorReporter reporter = new MonitorReporter(authClient, http, "http://backend");

        // No exception should escape, even though the underlying transport always throws for this call.
        reporter.report("receipt-uploaded", "ok", "r1", null);

        Check.that(true, "reaching this line means the failure was swallowed");
    }

    /** A transport whose /monitor/events calls always throw, everything else behaves normally. */
    private static class FailingPostTransport extends FakeHttpTransport {
        @Override
        public HttpResult postJson(String url, String jsonBody, String bearerToken) {
            if (url.endsWith("/monitor/events")) {
                throw new RuntimeException("simulated monitor reporting failure");
            }
            return super.postJson(url, jsonBody, bearerToken);
        }
    }
}
