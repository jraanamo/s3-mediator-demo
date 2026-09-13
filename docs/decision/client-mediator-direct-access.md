# Client-Mediator Direct Access

## Status

Accepted (revised — see History)

## Context

The whole point of this architecture is to detach the POS client from direct, constant Backend communication. The client needs config/catalog data and needs to upload receipts, but should not require standing S3/UpCloud credentials, and there's no practical way for the Mediator to push change notifications to the client.

The original design (see History) still required a Backend round-trip for every batch of receipt uploads, since `/upload-receipts` had to mint a fresh presigned PUT URL per receipt. That defeats the detachment goal for the produce side: the Backend would need to be reachable continuously, not just at bootstrap.

## Decision

- **Config/catalog (read side):** unchanged. `/login` returns short-lived presigned GET URLs for `/config/config.json` and `/catalog/catalog.json`. The client polls these with conditional GET (`If-None-Match` against the last seen `ETag`) on a timer, since the Mediator can't push change notifications.
- **Receipt upload (write side):** `/login` additionally returns temporary, auto-expiring S3 credentials (via AWS STS `AssumeRole`, scoped with a session policy to `s3:PutObject` under `inbox/<deviceId>/*` only) alongside a `keyPrefix`. The client uses these credentials directly with an S3 SDK to PUT receipts to the Mediator for the life of the credential — no per-upload Backend contact, no `/upload-receipts` endpoint.
- Credential lifetime matches the JWT's (`JWT_EXPIRY_SECONDS`), so refreshing the JWT via re-login also refreshes the upload credentials.
- This requires a pre-created IAM role (one-time setup, not per-deploy) whose own policy is at least as broad as any prefix the Backend will scope a session to, and whose trust policy allows the Backend's IAM user to assume it. The session policy passed at `AssumeRole` time can only narrow, never widen, the role's permissions — this is what enforces the per-device prefix scoping.

## Consequences

- The client now carries an S3 SDK dependency (AWS SDK v2 for Java) and briefly holds real, scoped credentials — a deliberate tradeoff against the alternative of contacting the Backend on every upload.
- Credentials are scoped per-device (`inbox/<deviceId>/*`) and expire automatically; a compromised client can only ever write into its own prefix, and only until its credentials expire.
- Change detection latency (config/catalog) is still bounded by the poll interval, not instant — unaffected by this decision.
- One-time manual step required outside the app: create the IAM role + trust policy + inline policy in UpCloud's IAM/STS console (or via the AWS CLI pointed at UpCloud's endpoints), and set `S3_UPLOAD_ROLE_ARN` in the Backend's config.

## Alternatives Considered

- **Presigned POST policy** (browser-form-style upload, one client-side upload without a fresh presigned URL per file): rejected — UpCloud's S3-compatible Managed Object Storage does not support the `POST` object operation (confirmed via UpCloud's own docs and a live `405`).
- **Pre-fetched pool of presigned PUT URLs**: each URL is bound to one exact object key (a SigV4 limitation, not UpCloud-specific), so this would mean the Backend pre-minting and handing out a batch of single-use URLs ahead of time. Would reduce Backend contact frequency but not eliminate it, adds bookkeeping (unused URL expiry, pool refill), and was not pursued once STS was confirmed viable.

## History

Originally accepted with a simpler mechanism: the Backend minted a fresh presigned PUT URL per receipt via `/upload-receipts`, requiring a Backend call for every upload batch. Revised to the STS-based mechanism above once that limitation was identified as violating the architecture's core detachment goal.
