package com.restolution.arch26demo.client.selfcheck;

import com.restolution.arch26demo.client.AuthClient;
import com.restolution.arch26demo.client.ReceiptUploader;
import com.restolution.arch26demo.client.http.HttpResult;
import com.restolution.arch26demo.client.model.Product;
import com.restolution.arch26demo.client.model.Receipt;

import java.time.Instant;
import java.util.List;

public final class ReceiptUploaderChecks {
    private ReceiptUploaderChecks() {
    }

    private static HttpResult loginResponse() {
        return new HttpResult(200,
                "{\"token\":\"t\",\"expiresAt\":\"" + Instant.now().plusSeconds(3600) + "\","
                        + "\"resources\":{\"config\":\"http://config-url\",\"catalog\":\"http://catalog-url\"}}",
                null);
    }

    private static HttpResult uploadReceiptsResponse(String receiptId) {
        return new HttpResult(200,
                "[{\"receiptId\":\"" + receiptId + "\",\"uploadUrl\":\"http://upload-url\"}]", null);
    }

    public static void happyPathEmptiesQueue() throws Exception {
        FakeHttpTransport http = new FakeHttpTransport();
        Receipt receipt = Receipt.create("client-1", List.of(new Product("item-1", "Burger", 12.5, 1)));

        http.queuePost(loginResponse());
        http.queuePost(uploadReceiptsResponse(receipt.receiptId()));
        http.queuePut(new HttpResult(200, "", null));

        AuthClient authClient = new AuthClient("http://backend", "client-1", http);
        ReceiptUploader uploader = new ReceiptUploader(authClient, http, "http://backend", 10, 2, 60, 5);

        uploader.enqueue(receipt);
        uploader.uploadBatch();

        Check.equal(0, uploader.queueSize(), "a successfully uploaded receipt should be removed from the queue");
    }

    public static void retryBackoffThenDropAfterMaxAttempts() throws Exception {
        FakeHttpTransport http = new FakeHttpTransport();
        Receipt receipt = Receipt.create("client-1", List.of(new Product("item-1", "Burger", 12.5, 1)));

        http.queuePost(loginResponse());
        // 3 attempts total (maxAttempts=2 means the 3rd failure exceeds it and drops).
        for (int i = 0; i < 3; i++) {
            http.queuePost(uploadReceiptsResponse(receipt.receiptId()));
            http.queuePut(new HttpResult(500, "server error", null));
        }

        // baseDelay/maxDelay of 0 so a retried receipt is immediately ready again on the next uploadBatch() call.
        AuthClient authClient = new AuthClient("http://backend", "client-1", http);
        ReceiptUploader uploader = new ReceiptUploader(authClient, http, "http://backend", 10, 0, 0, 2);

        uploader.enqueue(receipt);

        uploader.uploadBatch();
        Check.equal(1, uploader.queueSize(), "after 1st failure (attempt 1 of 2) the receipt should be requeued");

        uploader.uploadBatch();
        Check.equal(1, uploader.queueSize(), "after 2nd failure (attempt 2 of 2) the receipt should still be requeued");

        uploader.uploadBatch();
        Check.equal(0, uploader.queueSize(), "after the 3rd failure (exceeding max attempts) the receipt should be dropped");
    }
}
