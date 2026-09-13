# Architecture 26 — Demo

A demo of detaching POS client communication from a Backend server. Instead of the POS client
talking to the Backend directly for everything, a Mediator (S3-compatible object storage) carries
the bulk of the traffic — config/catalog downloads and receipt uploads — while the Backend is only
touched briefly to bootstrap (`/login`), which hands out both presigned GET URLs and temporary,
prefix-scoped S3 credentials in one call.

Goals (see [AGENTS.md](AGENTS.md) for the full background): fault tolerance, no impact from peak
loads, outsourcing uptime-sensitive parts to a managed object store, cheap to scale, and no direct
client↔server coupling.

## Architecture at a glance

```
 POS Client (Java)  ---------------- /login -------------->  Backend (Node/Fastify, Fly.io)
       |                                                            |
       | presigned GET (config/catalog)                             | synthesizes config/catalog,
       | direct PUT via temporary STS credentials (receipts)        | processes uploaded receipts
       '----------------------------------------------> Mediator (UpCloud S3) <-'
```

- **Client** calls `/login` once (and again near JWT expiry) to get a token, presigned GET URLs for
  config/catalog, and temporary S3 credentials (via STS) scoped to its own `inbox/<clientId>/*`
  prefix. Everything else — fetching config/catalog, uploading receipt bodies — goes straight to the
  Mediator bucket: GETs via those presigned URLs, receipt PUTs signed directly with the STS
  credentials using an S3 SDK. No per-upload Backend contact.
- **Backend** owns the Mediator bucket: it synthesizes and publishes config/catalog on a timer, and
  drains/archives uploaded receipts from `/inbox/` into `/archive/` on a timer. It never talks to
  the client except at `/login`.
- **Mediator** is a single UpCloud Object Storage bucket (S3-compatible). No custom code runs there.

See [client-mediator-direct-access](docs/decision/client-mediator-direct-access.md) for why (and
what didn't work first).

## What's where

| Path | What |
|---|---|
| `backend/` | Node/Fastify Backend — `/login`, catalog publishing, inbox processing. Deployed to Fly.io. |
| `client/` | Java POS client simulator — Gradle project, no UI, runs on timers. |
| `docs/spec/` | Feature specs for the backend and client (what they do, API shapes, config). |
| `docs/decision/` | Architectural decisions made along the way, with rationale (see `INDEX.md`). |
| `.github/workflows/` | CI: tests + deploy-to-Fly on push to `main`. |

Start with [`docs/spec/backend.md`](docs/spec/backend.md) and [`docs/spec/client.md`](docs/spec/client.md)
for the full API/data-model details, and [`docs/decision/INDEX.md`](docs/decision/INDEX.md) for why
things are built the way they are (single Backend instance, no client authorization, in-memory-only
client state, etc.).

## Running the Backend

```bash
cd backend
npm install
cp .env.example .env   # fill in real UpCloud S3 credentials
npm start              # listens on :3000
```

`npm test` runs the framework-free self-checks (`node --test`). Requires an UpCloud Object Storage
bucket to already exist (the Backend never creates one — see `docs/decision`). On startup, if
`config/config.json` / `catalog/catalog.json` are missing from the bucket, the Backend synthesizes
and writes them immediately so a client always has something to fetch.

**One-time IAM setup** (not part of any deploy — do this once per bucket, before first run): create
an IAM role in UpCloud's IAM/STS console (or via the AWS CLI pointed at UpCloud's endpoints) with a
trust policy allowing the Backend's own IAM user to assume it, and an inline policy granting
`s3:PutObject` on `arn:aws:s3:::<bucket>/inbox/*`. Set that role's URN as `S3_UPLOAD_ROLE_ARN`, and
the bucket's dedicated STS endpoint (from UpCloud's "Get service details" API, `sts_url`) as
`S3_STS_ENDPOINT`. See [client-mediator-direct-access](docs/decision/client-mediator-direct-access.md).

### Deploying the Backend

Already deployed to Fly.io (`backend/fly.toml`, app `backend-gentle-rain-7280`, single instance).

- `backend/setup-fly.sh` pushes everything in `backend/.env` to `fly secrets set` — run it after
  editing `.env` with real values.
- Pushing to `main` (touching `backend/**`) runs `.github/workflows/deploy-backend.yml`: tests, then
  `flyctl deploy`. Needs a `FLY_API_TOKEN` repo secret (already set).
- The Fly instance scales to zero when idle (no free always-on tier) — the first request after a
  quiet period takes a few extra seconds to wake it up.

## Running the Client

```bash
cd client
./gradlew run
```

On first run it creates `client/client.properties` (edit `backend.url` to point at your Backend;
defaults to `http://localhost:3000`) and generates a `client.id`, which it writes back into that
file — that id is reused on every subsequent run unless you clear it by hand. From then on the
client logs in, polls config/catalog, and periodically simulates + uploads fake receipts, logging
each step to the console.

`./gradlew selfCheck` runs the framework-free self-checks (no JUnit — see `docs/spec/client.md`
"Testing").

### Running multiple client instances

No need to copy the source per instance — build once (`./gradlew installDist`), then run the built
binary from a different working directory per instance (each gets its own `client.properties` and
generated `client.id`). `client.sh` at the repo root is a shortcut for this: run it from wherever you
want that instance's `client.properties` to live.

```bash
mkdir -p clients/pos-1 clients/pos-2
(cd clients/pos-1 && /path/to/repo/client.sh &)
(cd clients/pos-2 && /path/to/repo/client.sh &)
```

Or run several instances from one directory by passing a different properties filename per instance:

```bash
./client.sh instance-1.properties &
./client.sh instance-2.properties &
```

## Everything at once, locally

```bash
# terminal 1
cd backend && npm start

# terminal 2
cd client && ./gradlew run
```

Point `client.properties`' `backend.url` at `http://localhost:3000` (the default) and both need a
real UpCloud bucket configured in `backend/.env` — there's no local/fake Mediator.
