# Backend Spec

## Purpose

The Backend is the thin, occasional direct link the client uses to bootstrap. It doesn't serve config/catalog or accept records directly — `/login` hands out presigned GET URLs for config/catalog and temporary, prefix-scoped S3 credentials for record uploads, so the client talks to the Mediator (S3-compatible object storage, UpCloud) directly for all bulk data exchange thereafter. The Backend also owns publishing config/catalog into the Mediator and processing uploaded records out of it.

Single tenant for this demo: one config, one catalog. No authorization of clients — see [client-identity](../decision/client-identity.md).

## Bucket Layout (Mediator)

```
/config/config.json
/catalog/catalog.json
/inbox/<client-id>/<record-id>.json                        (client PUTs here, scoped by STS credentials)
/archive/<client-id>/<yyyy>/<mm>/<dd>/<record-id>.json     (after processing)
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

`upload` carries temporary STS credentials (via `AssumeRole` with a session policy) scoped to `s3:PutObject` under `inbox/<deviceId>/*` only — the client uses these directly with an S3 SDK to upload records, no further Backend contact required until the credentials near expiry. See [client-mediator-direct-access](../decision/client-mediator-direct-access.md).

Malformed `deviceId` → 400.

## Components

- **AuthService** — implements `/login`: validates `deviceId` format, issues JWT (`JWT_EXPIRY_SECONDS`), generates the two presigned GET URLs, and assumes the upload role (`assumeUploadRole`) to mint prefix-scoped temporary S3 credentials matching the JWT's lifetime.
- **CatalogPublisher** — on startup, checks whether `/config/config.json` and `/catalog/catalog.json` exist in the bucket; if either is missing, synthesizes and writes it immediately so the client always has something to fetch. Then on `PUBLISH_INTERVAL_SECONDS`, regenerates and overwrites both objects (the resulting new ETag is what drives the client's conditional-GET polling).
- **InboxProcessor** — on `INBOX_POLL_INTERVAL_SECONDS`, lists `/inbox/` via `ListObjectsV2` with paging (`INBOX_PAGE_SIZE`, following continuation tokens across the full listing each interval). Within each page, up to `INBOX_CONCURRENCY` objects are GET+parsed+copied concurrently (S3 has no bulk GET/COPY, so this is what parallelizes that part): for each object, GET it, parse the record JSON, log a line to console (key fields from the parsed record body), then `CopyObject` to `/archive/<clientId>/<yyyy>/<mm>/<dd>/<recordId>.json` (path fields taken from the parsed record body). Once the whole page has been copied, every successfully archived key in that page is deleted from the inbox in one batched `DeleteObjects` call (up to 1000 keys per call — S3 does have a bulk delete, unlike GET/COPY) instead of one `DeleteObject` per record. Copy-then-delete is still the "transaction" boundary: if a key's delete fails within that batch (partial failures are reported per-key, not all-or-nothing), that object is simply reprocessed on the next interval — the copy is idempotent (same destination key, overwritten) — logged as a warning, not a fatal error.

## Data Model

**Config** (`/config/config.json`):
```json
{
  "storeName": "Demo Bistro",
  "currency": "EUR",
  "taxRate": 0.14,
  "generatedAt": "<ISO-8601>"
}
```

**Catalog** (`/catalog/catalog.json`): list of items —
```json
[{ "id": "...", "name": "...", "price": 0.0 }]
```

**Record** (as read from `/inbox/<client-id>/<record-id>.json`, written by the client):
```json
{
  "recordId": "<uuid>",
  "clientId": "...",
  "timestamp": "<ISO-8601>",
  "data": {}
}
```

## Configuration

`.env` at the backend project root (Node convention, via `dotenv`):

```
PORT=3000
JWT_SECRET=...
JWT_EXPIRY_SECONDS=3600
S3_ENDPOINT=https://.../upcloud-endpoint
S3_STS_ENDPOINT=https://.../upcloud-endpoint:4443/sts
S3_REGION=...
S3_BUCKET=...
S3_ACCESS_KEY_ID=...
S3_SECRET_ACCESS_KEY=...
S3_UPLOAD_ROLE_ARN=urn:ecs:iam::<account-id>:role/<upload-role>
PUBLISH_INTERVAL_SECONDS=300
INBOX_POLL_INTERVAL_SECONDS=30
INBOX_PAGE_SIZE=50
INBOX_CONCURRENCY=20
```

`S3_ACCESS_KEY_ID`/`S3_SECRET_ACCESS_KEY` are the Backend's own long-lived IAM user credentials, used for all direct S3 calls and to call `AssumeRole` against `S3_UPLOAD_ROLE_ARN`. That role is a one-time manual setup step (not part of any deploy), done once via UpCloud's IAM console or the AWS CLI pointed at UpCloud's endpoints:
1. Create an IAM role with a trust policy allowing the Backend's IAM user to assume it.
2. Attach an inline policy granting `s3:PutObject` on `arn:aws:s3:::<bucket>/inbox/*`.
3. Set `S3_UPLOAD_ROLE_ARN` to that role's URN.

See [client-mediator-direct-access](../decision/client-mediator-direct-access.md) for why (per-device prefix scoping via STS session policies).

## Error Handling

- Malformed `deviceId` at `/login` → 400.
- InboxProcessor: a copy failure is logged as an error and the object is left in the inbox for retry next interval; a delete failure within the batched delete (per-key, doesn't affect other keys in the same batch) is logged as a warning and reprocessed next interval (idempotent, no duplicate side effects beyond a repeated console log line).
- CatalogPublisher: a failure to write config/catalog is logged as an error; startup does not crash the process, but the client will simply have nothing to fetch until the next successful publish (accepted for this demo).

## Deployment

Deployed as a single Fly.io instance (one shared-cpu-1x VM, `fly scale count 1`). One instance is a deliberate choice, not just a cost-saving one: CatalogPublisher and InboxProcessor run as in-process timers with no distributed locking, so a second concurrent instance would race to publish config/catalog and drain the same inbox objects. All clients connect to this single Backend URL. Scaling beyond one instance is out of scope for this demo (would require moving these timers to a coordinated/single-leader scheduling model).

Fly.io has no free tier for new accounts, so the instance is not always-on: `fly.toml` allows it to scale to zero when idle (`min_machines_running = 0`) and wake on the next incoming request. See [single-backend-instance](../decision/single-backend-instance.md) for the consequences on the background timers.

## Out of Scope

- Multi-tenancy.
- Any authentication/authorization of clients — `/login` trusts whatever `deviceId` it's given (see [client-identity](../decision/client-identity.md)).
- Any read-back/reporting API over archived records.
- Persistent job state for InboxProcessor/CatalogPublisher (in-memory intervals only, no durable scheduling).

## Testing

One runnable self-check per non-trivial piece of logic, no test framework:
- InboxProcessor: paging through a multi-page inbox listing correctly drains all objects.
- InboxProcessor: copy-then-delete idempotency (reprocessing an object already archived doesn't error or duplicate archive entries incorrectly).
- InboxProcessor: a partial batch-delete failure (some keys in a `DeleteObjects` call fail, others succeed) only leaves the failed keys for reprocessing.
- CatalogPublisher: "create if missing" logic on startup vs. "already present, skip initial write" logic.

## Tech Stack

- Node.js, Fastify.
- `@aws-sdk/client-s3` + `@aws-sdk/s3-request-presigner`, configured with a custom endpoint/region for UpCloud's S3-compatible API.
- `@aws-sdk/client-sts` for `AssumeRole`, pointed at UpCloud's dedicated STS endpoint (`S3_STS_ENDPOINT`).
- `jsonwebtoken` for JWT issuance/verification.
- `dotenv` for configuration.
