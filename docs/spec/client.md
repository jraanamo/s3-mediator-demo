# POS Client Spec

## Purpose

A Java simulator standing in for a real POS client (RestoGo / SoftPos / RestoSnap). It demonstrates the detached architecture: a thin, occasional direct link to the Backend to bootstrap, and all bulk data exchange (config/catalog download, receipt upload) done directly against the Mediator (S3-compatible object storage) via presigned URLs. Headless — no UI. Runs as a long-lived process that simulates POS activity on timers.

## Backend API (imaginary, minimal)

### `POST /login`

Request: `{ "deviceId": "..." }` (this client's generated id — see [client-identity](../decision/client-identity.md); the Backend accepts any well-formed id, no registration required).

Response:
```json
{
  "token": "<JWT>",
  "expiresAt": "<ISO-8601>",
  "resources": {
    "config": "<presigned GET URL>",
    "catalog": "<presigned GET URL>"
  }
}
```

The JWT is used as a bearer token on subsequent Backend calls. When it is expired or about to expire, or any call returns 401, the client re-calls `/login` — this refreshes the token AND the presigned URLs together. There is no separate refresh endpoint.

### `POST /upload-receipts`

Request: array of `{ "receiptId": "..." }`, one entry per queued receipt.

Response: array of `{ "receiptId": "...", "uploadUrl": "<presigned PUT URL>" }`, used to upload the receipt body directly to the Mediator. The fetched config also carries an `uploadReceiptsUrl` field (the Backend's own endpoint URL) for realism, but this demo client just uses its configured `backend.url` for both calls rather than switching sources mid-flight.

## Client Components

- **AuthClient** — owns the current JWT, its expiry, and the current `resources` map. Exposes "get current config URL" / "get current catalog URL" to other components, transparently re-logging in when needed (expiry reached, or a 401 bubbles up from a caller).
- **CatalogSyncService** — on `catalog-poll-interval-seconds`, issues a conditional GET (`If-None-Match: <last ETag>`) against the config and catalog presigned URLs. 304 → no-op. 200 → replace in-memory config/catalog, store new ETag. A 401 delegates to AuthClient for re-login and retries once.
- **ReceiptSimulator** — on `simulate-interval-seconds`, builds a fake receipt (random products drawn from the current in-memory catalog, with a computed `totalAmount`) and enqueues it in-memory.
- **ReceiptUploader** — on `upload-interval-seconds`, drains up to `upload-batch-size` receipts from the queue, calls `/upload-receipts`, then PUTs each receipt body to its returned presigned URL. On failure, the receipt goes back on the queue with an incremented attempt count and exponential backoff (`retry-base-delay-seconds`, doubling, capped at `retry-max-delay-seconds`); once `retry-max-attempts` is exceeded, the receipt is dropped and the failure is logged as an error.
- **Main/Scheduler** — loads `client.properties`; if `client.id` is blank/absent, generates a new UUID, writes it back into `client.properties` (so it's stable across restarts), and uses it from then on. Wires the components above onto a `ScheduledExecutorService`.

All state (JWT, config, catalog, receipt queue) is in-memory only. A process restart loses anything not yet uploaded — accepted tradeoff for this demo, no persistence layer.

## Configuration

Single file, `client.properties`, in the client working directory (or a path given as the first CLI
argument, e.g. `client instance-2.properties`, to run multiple instances from one directory). Running
the same built client from different working directories — or with different argument filenames —
runs independent instances without copying any source; each gets its own generated `client.id`.
`client.sh` at the repo root is a convenience launcher: run it from whatever directory you want that
instance's `client.properties` to live in (it builds the client once via Gradle if needed, then runs
the already-built binary from wherever it was invoked).

```properties
# client.id is generated and written here on first run if left blank
client.id=
backend.url=https://backend.example.test
backend.login-check-interval-seconds=60
catalog.poll-interval-seconds=60
simulate.interval-seconds=5
upload.interval-seconds=10
upload.batch-size=10
retry.base-delay-seconds=2
retry.max-delay-seconds=60
retry.max-attempts=5
```

## Logging

log4j2, console appender only (stdout), no file appender. Logged at INFO or above for each significant stage/event:
- login attempt / success / failure
- token refresh
- catalog/config change detected (ETag changed) vs. unchanged
- receipt simulated (enqueued)
- upload batch attempt, per-receipt success/failure, drop after max attempts

## Data Model

**Config / Catalog**: opaque JSON blobs as far as the client's sync logic is concerned. The catalog is assumed to have a list of items with at least `id`, `name`, `price`, used by ReceiptSimulator to build products.

**Receipt**: JSON, generated locally, uploaded to `/inbox/<receiptId>.json` on the Mediator:
```json
{
  "receiptId": "<uuid>",
  "clientId": "...",
  "date": "<ISO-8601>",
  "totalAmount": 0.0,
  "products": [
    { "id": "...", "name": "...", "price": 0.0, "quantity": 1 }
  ]
}
```

## Error Handling

- All Backend and Mediator (S3) calls retry with exponential backoff per the `retry.*` settings above, in-memory only.
- A 401 from any Mediator call is treated as an expired-URL signal: delegate to AuthClient for a fresh `/login`, retry the operation once, then apply normal retry/backoff if it fails again.
- Receipts exceeding `retry.max-attempts` are dropped and logged as an error; the client keeps running.

## Out of Scope

- Real UI / interactive CLI.
- Log file upload (logging data is written to stdout only, not synced to the Mediator).
- Disk-backed persistence / durability across restarts (the `client.id` line in `client.properties` is the one exception — see [client-identity](../decision/client-identity.md)).
- Real authentication scheme behind `/login`.

## Testing

One runnable self-check per non-trivial piece of logic, no test framework:
- Main/Scheduler: generates and persists `client.id` when blank; reuses the existing value when already set.
- AuthClient: expiry-triggered and 401-triggered re-login behavior.
- CatalogSyncService: ETag-conditional fetch (304 no-op vs 200 replace).
- ReceiptUploader: retry/backoff progression and drop-after-max-attempts behavior.

## Tech Stack

- Java (LTS), Gradle build.
- log4j2 for logging (console appender).
- HTTP client: JDK built-in `java.net.http.HttpClient` — no extra dependency needed for simple JSON/REST calls.
