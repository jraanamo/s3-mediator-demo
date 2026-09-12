# Client-Mediator Direct Access

## Status

Accepted

## Context

The whole point of this architecture is to detach the POS client from direct, constant Backend communication. The client needs config/catalog data and needs to upload receipts, but should not require its own S3/UpCloud credentials, and there's no practical way for the Mediator to push change notifications to the client.

## Decision

- The Backend issues short-lived presigned URLs (via `/login` for GET config/catalog, via `/upload-receipts` for PUT receipt uploads). The client never holds S3/UpCloud credentials and never needs an S3 SDK — plain HTTP GET/PUT against the presigned URLs is sufficient.
- Since the Mediator can't notify the client of changes, the client detects updated config/catalog itself by polling with conditional GET (`If-None-Match` against the last seen `ETag`) on a timer.

## Consequences

- No S3 client library dependency anywhere in the client codebase.
- Change detection latency is bounded by the poll interval, not instant.
- Presigned URLs expire; the client re-logs in (getting fresh URLs and a fresh JWT together) when a URL is rejected or the JWT is near expiry.
