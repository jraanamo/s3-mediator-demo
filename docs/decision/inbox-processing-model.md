# Inbox Processing Model

## Status

Accepted

## Context

Receipts land in `/inbox/<client-id>/<receipt-id>.json` via a direct client PUT (temporary, prefix-scoped STS credentials — see [client-mediator-direct-access](client-mediator-direct-access.md)). The Backend needs to move each processed receipt into a durable archive path without a database or transactional store to track processing state — the bucket itself is the only state.

At thousands of clients, the naive one-object-at-a-time version of this (GET, then CopyObject, then DeleteObject, fully sequential) doesn't scale: GET and CopyObject have no S3 bulk equivalent, but processing objects one at a time with nothing in flight concurrently means throughput per poll is bounded by round-trip latency, not by any real limit.

## Decision

The Backend processes the inbox on a timer, paged (`ListObjectsV2` with a configurable page size, `INBOX_PAGE_SIZE`). Within each page, up to `INBOX_CONCURRENCY` objects are GET+parsed+copied concurrently (bounded worker pool, not one request in flight at a time) — for each object: GET it, log it, `CopyObject` to `/archive/<clientId>/<yyyy>/<mm>/<dd>/<receiptId>.json` (path fields taken from the parsed receipt body). Once the whole page has been copied, every successfully archived key in that page is deleted in a single batched `DeleteObjects` call (up to 1000 keys per call) instead of one `DeleteObject` per receipt. Copy-then-delete is still the transaction boundary, just with the delete step batched across the page instead of per-object.

`DeleteObjects` against UpCloud requires the request to carry a `Content-MD5` header, which the AWS SDK v3 only computes when `ChecksumAlgorithm` is explicitly set on the command (confirmed via live testing — the SDK's default checksum handling for this operation silently omits the header and UpCloud rejects the request with `400 InvalidRequest`).

## Consequences

- If a key's delete fails within a batch, only that object is left in the inbox and reprocessed next interval — `DeleteObjects` reports failures per-key, not all-or-nothing for the batch. This is safe: the copy destination key is deterministic, so reprocessing just overwrites the same archive object and re-logs it — no duplicate side effects beyond a repeated log line.
- Processing is at-least-once, not exactly-once. Acceptable for this demo; would need idempotency keys or a processed-set if archive writes ever became non-idempotent (e.g. append-only logs) instead of overwrites.
- GET and CopyObject still cost one S3 call per object each — no bulk equivalent exists for either. Concurrency (not batching) is what parallelizes that part; `INBOX_CONCURRENCY` bounds how many are in flight at once so a large inbox doesn't overwhelm the Mediator or this demo's single small Fly VM.
