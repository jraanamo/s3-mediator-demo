# Backend Spec

## Purpose

The Backend is the thin, occasional direct link the POS client uses to bootstrap. It doesn't serve config/catalog or accept receipts directly — it hands out presigned URLs so the client can talk to the Mediator (S3-compatible object storage, UpCloud) directly for all bulk data exchange. The Backend also owns publishing config/catalog into the Mediator and processing uploaded receipts out of it.

Single tenant for this demo: one config, one catalog. No authorization of clients — see [client-identity](../decision/client-identity.md).

## Bucket Layout (Mediator)

```
/config/config.json
/catalog/catalog.json
/inbox/<receipt-id>.json                                   (client PUTs here)
/archive/<client-id>/<yyyy>/<mm>/<dd>/<receipt-id>.json     (after processing)
```

## REST API

### `POST /login`

Request: `{ "deviceId": "..." }`

Accepts any `deviceId` matching `^[A-Za-z0-9_-]{1,64}$` (no registry, no secret/password check — see [client-identity](../decision/client-identity.md)). On success:

```json
{
  "token": "<JWT>",
  "expiresAt": "<ISO-8601>",
  "resources": {
    "config": "<presigned GET URL for /config/config.json>",
    "catalog": "<presigned GET URL for /catalog/catalog.json>"
  }
}
```

Malformed `deviceId` → 400.

### `POST /upload-receipts`

Bearer JWT required (401 if missing/expired/invalid). Request: array of `{ "receiptId": "..." }`.

Response: array of `{ "receiptId": "...", "uploadUrl": "<presigned PUT URL for /inbox/<receiptId>.json>" }`.

## Components

- **AuthService** — implements `/login`: validates `deviceId` format, issues JWT (`JWT_EXPIRY_SECONDS`), generates the two presigned GET URLs.
- **UploadService** — implements `/upload-receipts`: verifies JWT, generates one presigned PUT URL per requested receipt id.
- **CatalogPublisher** — on startup, checks whether `/config/config.json` and `/catalog/catalog.json` exist in the bucket; if either is missing, synthesizes and writes it immediately so the client always has something to fetch. Then on `PUBLISH_INTERVAL_SECONDS`, regenerates and overwrites both objects (the resulting new ETag is what drives the client's conditional-GET polling).
- **InboxProcessor** — on `INBOX_POLL_INTERVAL_SECONDS`, lists `/inbox/` via `ListObjectsV2` with paging (`INBOX_PAGE_SIZE`, following continuation tokens across the full listing each interval). For each object: GET it, parse the receipt JSON, log a line to console (`receiptId`, `clientId`, `totalAmount`), `CopyObject` to `/archive/<clientId>/<yyyy>/<mm>/<dd>/<receiptId>.json` (path fields taken from the parsed receipt body), then `DeleteObject` on the inbox key. Copy-then-delete is the "transaction" boundary: if delete fails after a successful copy, the object is simply reprocessed on the next interval — the copy is idempotent (same destination key, overwritten) — logged as a warning, not a fatal error.

## Data Model

**Config** (`/config/config.json`):
```json
{
  "storeName": "Demo Bistro",
  "currency": "EUR",
  "taxRate": 0.14,
  "uploadReceiptsUrl": "https://backend.example.test/upload-receipts",
  "generatedAt": "<ISO-8601>"
}
```

**Catalog** (`/catalog/catalog.json`): list of items —
```json
[{ "id": "...", "name": "...", "price": 0.0 }]
```

**Receipt** (as read from `/inbox/<receipt-id>.json`, written by the client):
```json
{
  "receiptId": "<uuid>",
  "clientId": "...",
  "date": "<ISO-8601>",
  "totalAmount": 0.0,
  "products": [{ "id": "...", "name": "...", "price": 0.0, "quantity": 1 }]
}
```

## Configuration

`.env` at the backend project root (Node convention, via `dotenv`):

```
PORT=3000
BACKEND_URL=http://localhost:3000
JWT_SECRET=...
JWT_EXPIRY_SECONDS=3600
S3_ENDPOINT=https://.../upcloud-endpoint
S3_REGION=...
S3_BUCKET=...
S3_ACCESS_KEY_ID=...
S3_SECRET_ACCESS_KEY=...
PUBLISH_INTERVAL_SECONDS=300
INBOX_POLL_INTERVAL_SECONDS=30
INBOX_PAGE_SIZE=50
```

## Error Handling

- Malformed `deviceId` at `/login` → 400.
- Missing/expired/invalid JWT at `/upload-receipts` → 401.
- InboxProcessor: a copy failure is logged as an error and the object is left in the inbox for retry next interval; a delete failure after a successful copy is logged as a warning and reprocessed next interval (idempotent, no duplicate side effects beyond a repeated console log line).
- CatalogPublisher: a failure to write config/catalog is logged as an error; startup does not crash the process, but the client will simply have nothing to fetch until the next successful publish (accepted for this demo).

## Deployment

Deployed as a single Fly.io instance (one shared-cpu-1x VM, `fly scale count 1`). One instance is a deliberate choice, not just a cost-saving one: CatalogPublisher and InboxProcessor run as in-process timers with no distributed locking, so a second concurrent instance would race to publish config/catalog and drain the same inbox objects. All POS clients connect to this single Backend URL. Scaling beyond one instance is out of scope for this demo (would require moving these timers to a coordinated/single-leader scheduling model).

Fly.io has no free tier for new accounts, so the instance is not always-on: `fly.toml` allows it to scale to zero when idle (`min_machines_running = 0`) and wake on the next incoming request. See [single-backend-instance](../decision/single-backend-instance.md) for the consequences on the background timers.

## Out of Scope

- Multi-tenancy.
- Any authentication/authorization of clients — `/login` trusts whatever `deviceId` it's given (see [client-identity](../decision/client-identity.md)).
- Any read-back/reporting API over archived receipts.
- Persistent job state for InboxProcessor/CatalogPublisher (in-memory intervals only, no durable scheduling).

## Testing

One runnable self-check per non-trivial piece of logic, no test framework:
- InboxProcessor: paging through a multi-page inbox listing correctly drains all objects.
- InboxProcessor: copy-then-delete idempotency (reprocessing an object already archived doesn't error or duplicate archive entries incorrectly).
- CatalogPublisher: "create if missing" logic on startup vs. "already present, skip initial write" logic.

## Tech Stack

- Node.js, Fastify.
- `@aws-sdk/client-s3` + `@aws-sdk/s3-request-presigner`, configured with a custom endpoint/region for UpCloud's S3-compatible API.
- `jsonwebtoken` for JWT issuance/verification.
- `dotenv` for configuration.
