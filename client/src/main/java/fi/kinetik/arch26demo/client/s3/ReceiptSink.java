package fi.kinetik.arch26demo.client.s3;

import fi.kinetik.arch26demo.client.UploadCredentials;

import java.io.IOException;

/**
 * Small seam over the actual S3 PUT, so ReceiptUploader can be self-checked
 * against a fake implementation instead of a real Mediator bucket.
 */
public interface ReceiptSink {
    void put(UploadCredentials credentials, String key, String jsonBody) throws IOException;
}
