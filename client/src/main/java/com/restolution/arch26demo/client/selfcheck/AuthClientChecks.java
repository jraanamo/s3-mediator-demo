package com.restolution.arch26demo.client.selfcheck;

import com.restolution.arch26demo.client.AuthClient;
import com.restolution.arch26demo.client.UploadCredentials;
import com.restolution.arch26demo.client.http.HttpResult;

import java.time.Instant;

public final class AuthClientChecks {
    private AuthClientChecks() {
    }

    private static HttpResult loginResponse(String token, Instant expiresAt) {
        String body = "{\"token\":\"" + token + "\",\"expiresAt\":\"" + expiresAt + "\","
                + "\"resources\":{\"config\":\"http://config-url\",\"catalog\":\"http://catalog-url\"},"
                + "\"upload\":{\"endpoint\":\"http://s3\",\"region\":\"us-east-1\",\"bucket\":\"b\","
                + "\"keyPrefix\":\"inbox/client-1/\",\"accessKeyId\":\"ak\",\"secretAccessKey\":\"sk\","
                + "\"sessionToken\":\"st\",\"expiration\":\"" + expiresAt + "\"}}";
        return new HttpResult(200, body, null);
    }

    public static void reusesTokenUntilExpiry() throws Exception {
        FakeHttpTransport http = new FakeHttpTransport();
        http.queuePost(loginResponse("token-1", Instant.now().plusSeconds(3600)));
        AuthClient authClient = new AuthClient("http://backend", "client-1", http);

        String first = authClient.currentToken();
        String second = authClient.currentToken();

        Check.equal("token-1", first, "should return the token from login");
        Check.equal("token-1", second, "should reuse the same token without a second login");
        Check.equal(1, http.postCalls, "only one login call should have happened");
    }

    public static void invalidateTriggersRelogin() throws Exception {
        FakeHttpTransport http = new FakeHttpTransport();
        http.queuePost(loginResponse("token-1", Instant.now().plusSeconds(3600)));
        http.queuePost(loginResponse("token-2", Instant.now().plusSeconds(3600)));
        AuthClient authClient = new AuthClient("http://backend", "client-1", http);

        authClient.currentToken();
        authClient.invalidate();
        String afterInvalidate = authClient.currentToken();

        Check.equal("token-2", afterInvalidate, "invalidate() should force a fresh login on next use");
        Check.equal(2, http.postCalls, "invalidate should trigger exactly one extra login call");
    }

    public static void exposesUploadCredentials() throws Exception {
        FakeHttpTransport http = new FakeHttpTransport();
        http.queuePost(loginResponse("token-1", Instant.now().plusSeconds(3600)));
        AuthClient authClient = new AuthClient("http://backend", "client-1", http);

        UploadCredentials upload = authClient.currentUpload();

        Check.equal("inbox/client-1/", upload.keyPrefix(), "should expose the keyPrefix from the login response");
        Check.equal("ak", upload.accessKeyId(), "should expose the accessKeyId from the login response");
    }
}
