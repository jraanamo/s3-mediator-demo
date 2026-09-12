# Inbox Processing Model

## Status

Accepted

## Context

Receipts land in `/inbox/<receipt-id>.json` via client-initiated presigned PUT. The Backend needs to move each processed receipt into a durable archive path without a database or transactional store to track processing state — the bucket itself is the only state.

## Decision

The Backend processes the inbox on a timer, paged (`ListObjectsV2` with a configurable page size): for each object, GET it, log it, `CopyObject` to `/archive/<clientId>/<yyyy>/<mm>/<dd>/<receiptId>.json` (path fields taken from the parsed receipt body), then `DeleteObject` on the inbox key. Copy-then-delete is the transaction boundary.

## Consequences

- If delete fails after a successful copy, the object is left in the inbox and reprocessed next interval. This is safe: the copy destination key is deterministic, so reprocessing just overwrites the same archive object and re-logs it — no duplicate side effects beyond a repeated log line.
- Processing is at-least-once, not exactly-once. Acceptable for this demo; would need idempotency keys or a processed-set if archive writes ever became non-idempotent (e.g. append-only logs) instead of overwrites.
