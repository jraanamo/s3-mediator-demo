# Client Spec

## Purpose

A Java simulator demonstrating the detached architecture: a thin, occasional direct link to the Backend to bootstrap, and all bulk data exchange done directly against the Mediator (S3-compatible object storage) thereafter — config/catalog download via presigned GET URLs, record upload via temporary, prefix-scoped S3 credentials (STS). Headless — no UI. Runs as a long-lived process that simulates client activity on timers.

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
  },
  "upload": {
    "endpoint": "<S3 endpoint>",
    "region": "<S3 region>",
    "bucket": "<bucket name>",
    "keyPrefix": "inbox/<deviceId>/",
    "accessKeyId": "...",
    "secretAccessKey": "...",
    "sessionToken": "...",
    "expiration": "<ISO-8601, matches expiresAt>"
  }
}
```

The JWT is used as a bearer token on subsequent Backend calls. `upload` carries temporary STS credentials scoped to `s3:PutObject` under `keyPrefix` only — the client uses these directly with an S3 SDK to PUT records to the Mediator, no further Backend call per upload. When the JWT is expired or about to expire, the client re-calls `/login` — this refreshes the token, the presigned URLs, and the upload credentials together. There is no separate refresh endpoint, and no `/upload-records` endpoint (removed — see [client-mediator-direct-access](../decision/client-mediator-direct-access.md)).

## Client Components

- **AuthClient** — owns the current JWT, its expiry, the current `resources` map, and the current `upload` credentials. Exposes "get current config URL" / "get current catalog URL" / "get current upload credentials" to other components, transparently re-logging in when needed (expiry reached, or a 401 bubbles up from a caller).
- **CatalogSyncService** — on `catalog-poll-interval-seconds`, issues a conditional GET (`If-None-Match: <last ETag>`) against the config and catalog presigned URLs. 304 → no-op. 200 → replace in-memory config/catalog, store new ETag. A 401 delegates to AuthClient for re-login and retries once.
- **RecordSimulator** — on `simulate-interval-seconds`, builds a fake record and enqueues it in-memory.
- **RecordUploader** — on `upload-interval-seconds`, drains up to `upload-batch-size` records from the queue and PUTs each directly to the Mediator (via `RecordSink`, backed by AWS SDK v2 for Java) at `keyPrefix + recordId + ".json"`, using the current upload credentials from AuthClient. On failure, the record goes back on the queue with an incremented attempt count and exponential backoff (`retry-base-delay-seconds`, doubling, capped at `retry-max-delay-seconds`); once `retry-max-attempts` is exceeded, the record is dropped and the failure is logged as an error.
- **Main/Scheduler** — loads `client.properties`; if `client.id` is blank/absent, generates a new UUID, writes it back into `client.properties` (so it's stable across restarts), and uses it from then on. Wires the components above onto a `ScheduledExecutorService`.

All state (JWT, config, catalog, record queue) is in-memory only. A process restart loses anything not yet uploaded — accepted tradeoff for this demo, no persistence layer.

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
- record simulated (enqueued)
- upload batch attempt, per-record success/failure, drop after max attempts

## Data Model

**Config / Catalog**: opaque JSON blobs as far as the client's sync logic is concerned.

**Record**: JSON, generated locally, uploaded to `/inbox/<clientId>/<recordId>.json` on the Mediator:
```json
{
  "recordId": "<uuid>",
  "clientId": "...",
  "timestamp": "<ISO-8601>",
  "data": {}
}
```

## Error Handling

- All Backend and Mediator (S3) calls retry with exponential backoff per the `retry.*` settings above, in-memory only.
- A 401 from a Backend call is treated as an expired-token signal: delegate to AuthClient for a fresh `/login`, retry the operation once, then apply normal retry/backoff if it fails again. Upload credentials are refreshed proactively near expiry (same buffer as the JWT) rather than reactively, since S3 auth failures don't map cleanly to a single HTTP status.
- Records exceeding `retry.max-attempts` are dropped and logged as an error; the client keeps running.

## Out of Scope

- Real UI / interactive CLI.
- Log file upload (logging data is written to stdout only, not synced to the Mediator).
- Disk-backed persistence / durability across restarts (the `client.id` line in `client.properties` is the one exception — see [client-identity](../decision/client-identity.md)).
- Real authentication scheme behind `/login`.

## Testing

One runnable self-check per non-trivial piece of logic, no test framework:
- Main/Scheduler: generates and persists `client.id` when blank; reuses the existing value when already set.
- AuthClient: expiry-triggered re-login behavior; exposes upload credentials from the login response.
- CatalogSyncService: ETag-conditional fetch (304 no-op vs 200 replace).
- ReceiptUploader: retry/backoff progression and drop-after-max-attempts behavior (against a fake `ReceiptSink`, no real S3 endpoint needed).

## Tech Stack

- Java (LTS), Gradle build.
- log4j2 for logging (console appender).
- HTTP client: JDK built-in `java.net.http.HttpClient` — used for Backend calls (`/login`, config/catalog GET, monitor reporting).
- AWS SDK v2 for Java (`software.amazon.awssdk:s3`) — used only for receipt uploads, signed with the temporary STS credentials from `/login`.
