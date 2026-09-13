package fi.kinetik.arch26demo.client.http;

/** Result of an HTTP call: status code, body, and (for conditional GETs) the ETag header. */
public record HttpResult(int status, String body, String etag) {
}
