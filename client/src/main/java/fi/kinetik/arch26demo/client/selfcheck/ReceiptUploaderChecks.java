package fi.kinetik.arch26demo.client.selfcheck;

import fi.kinetik.arch26demo.client.AuthClient;
import fi.kinetik.arch26demo.client.MonitorReporter;
import fi.kinetik.arch26demo.client.ReceiptUploader;
import fi.kinetik.arch26demo.client.http.HttpResult;
import fi.kinetik.arch26demo.client.model.Product;
import fi.kinetik.arch26demo.client.model.Receipt;

import java.time.Instant;
import java.util.List;

public final class ReceiptUploaderChecks {
    private ReceiptUploaderChecks() {
    }

    private static HttpResult loginResponse() {
        return new HttpResult(200,
                "{\"token\":\"t\",\"expiresAt\":\"" + Instant.now().plusSeconds(3600) + "\","
                        + "\"resources\":{\"config\":\"http://config-url\",\"catalog\":\"http://catalog-url\"},"
                        + "\"upload\":{\"endpoint\":\"http://s3\",\"region\":\"us-east-1\",\"bucket\":\"b\","
                        + "\"keyPrefix\":\"inbox/client-1/\",\"accessKeyId\":\"ak\",\"secretAccessKey\":\"sk\","
                        + "\"sessionToken\":\"st\",\"expiration\":\"" + Instant.now().plusSeconds(3600) + "\"}}",
                null);
    }

    public static void happyPathEmptiesQueue() throws Exception {
        FakeHttpTransport http = new FakeHttpTransport();
        FakeReceiptSink sink = new FakeReceiptSink();
        Receipt receipt = Receipt.create("client-1", List.of(new Product("item-1", "Burger", 12.5, 1)));

        http.queuePost(loginResponse());
        sink.queueOk();

        AuthClient authClient = new AuthClient("http://backend", "client-1", http);
        MonitorReporter reporter = new MonitorReporter(authClient, http, "http://backend");
        ReceiptUploader uploader = new ReceiptUploader(authClient, sink, reporter, 10, 2, 60, 5);

        uploader.enqueue(receipt);
        uploader.uploadBatch();

        Check.equal(0, uploader.queueSize(), "a successfully uploaded receipt should be removed from the queue");
    }

    public static void retryBackoffThenDropAfterMaxAttempts() throws Exception {
        FakeHttpTransport http = new FakeHttpTransport();
        FakeReceiptSink sink = new FakeReceiptSink();
        Receipt receipt = Receipt.create("client-1", List.of(new Product("item-1", "Burger", 12.5, 1)));

        http.queuePost(loginResponse());
        // 3 attempts total (maxAttempts=2 means the 3rd failure exceeds it and drops).
        sink.queueFailure().queueFailure().queueFailure();

        // baseDelay/maxDelay of 0 so a retried receipt is immediately ready again on the next uploadBatch() call.
        AuthClient authClient = new AuthClient("http://backend", "client-1", http);
        MonitorReporter reporter = new MonitorReporter(authClient, http, "http://backend");
        ReceiptUploader uploader = new ReceiptUploader(authClient, sink, reporter, 10, 0, 0, 2);

        uploader.enqueue(receipt);

        uploader.uploadBatch();
        Check.equal(1, uploader.queueSize(), "after 1st failure (attempt 1 of 2) the receipt should be requeued");

        uploader.uploadBatch();
        Check.equal(1, uploader.queueSize(), "after 2nd failure (attempt 2 of 2) the receipt should still be requeued");

        uploader.uploadBatch();
        Check.equal(0, uploader.queueSize(), "after the 3rd failure (exceeding max attempts) the receipt should be dropped");
    }
}
