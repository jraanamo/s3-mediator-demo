# Backend Secrets Configuration

## Status

Accepted

## Context

The Backend needs S3/UpCloud credentials (endpoint, region, bucket, access key, secret key), a JWT signing secret, and the device allow-list. These shouldn't be committed to the repo, and provisioning the UpCloud object storage credentials is a separate manual step from deploying the app to Fly.io.

## Decision

All Backend secrets/config are supplied as environment variables, set on Fly.io via `fly secrets set` (not baked into the image, not committed to git). Locally, the same variables come from a `.env` file (via `dotenv`), matching Node convention. Deploying and configuring secrets are independent steps: the app can be deployed to Fly.io before real S3 credentials exist — CatalogPublisher/InboxProcessor will simply fail their startup checks and log errors until the secrets are set, at which point `fly secrets set` triggers a restart that picks them up.

Variables: `PORT`, `JWT_SECRET`, `JWT_EXPIRY_SECONDS`, `DEVICE_IDS`, `S3_ENDPOINT`, `S3_REGION`, `S3_BUCKET`, `S3_ACCESS_KEY_ID`, `S3_SECRET_ACCESS_KEY`, `PUBLISH_INTERVAL_SECONDS`, `INBOX_POLL_INTERVAL_SECONDS`, `INBOX_PAGE_SIZE` (see `docs/spec/backend.md` for the full list, kept in sync there as it evolves).

## Consequences

- No secrets in git, no secrets in the deploy pipeline itself (GitHub Actions only needs a Fly deploy token, not the app's own secrets).
- Deploy order is decoupled from credential provisioning — a working CI/CD pipeline doesn't have to wait on UpCloud bucket setup.
