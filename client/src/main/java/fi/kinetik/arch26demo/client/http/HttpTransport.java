package fi.kinetik.arch26demo.client.http;

import java.io.IOException;

/**
 * Small seam over the actual HTTP calls the client makes, so AuthClient /
 * CatalogSyncService / ReceiptUploader can be self-checked against a fake
 * implementation instead of a real network/Mediator.
 */
public interface HttpTransport {
    HttpResult postJson(String url, String jsonBody, String bearerToken) throws IOException;

    HttpResult getConditional(String url, String ifNoneMatchEtag) throws IOException;

    HttpResult putJson(String url, String jsonBody) throws IOException;
}
