package com.restolution.arch26demo.client;

import java.time.Instant;

/** Temporary, prefix-scoped S3 credentials issued by /login. See docs/decision/client-mediator-direct-access.md. */
public record UploadCredentials(String endpoint, String region, String bucket, String keyPrefix,
                                 String accessKeyId, String secretAccessKey, String sessionToken, Instant expiration) {
}
