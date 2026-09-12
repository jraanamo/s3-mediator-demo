# Live Monitor Spec

## Purpose

A single page, served by the existing Backend, that both explains the demo's architecture and shows
it running live: POS clients logging in, config/catalog being checked, receipts being uploaded,
polled, processed, and archived. No separate service, database, or history — it's a live view for
demonstrations, not an audit log.

## Endpoints (all on the existing Backend)

### `GET /`

Serves the combined page: brief explainer text about the demo (what the Backend/Client/Mediator
are and why), the live architecture diagram, and a scrolling recent-events feed. A single static
HTML file (inline CSS/JS/SVG) read once at startup and cached in memory — no static-file-serving
dependency needed for one file.

### `GET /monitor/events`

Server-Sent Events stream (`content-type: text/event-stream`). Implemented directly against the
raw Node response (`reply.raw`) — Fastify has no built-in SSE helper, but none is needed for a
one-way stream. The Backend keeps an in-memory `Set` of connected response streams and writes every
broadcast event to all of them. On disconnect, the stream is removed from the set.

No replay: a browser connecting now only sees events from that point on.

### `POST /monitor/events`

Accepts one client-reported event as JSON. Requires a valid bearer JWT (the client's existing
token) — not real authorization, just consistent with the demo's no-auth stance elsewhere. Validates:

- `type` against the fixed allow-list below
- `clientId`, `receiptId`, `detail` are strings under a small length limit (e.g. 200 chars)
- request body under a small size limit

A malformed request is rejected (400) but never crashes the endpoint or affects the client's own
operation (the client never waits on or retries this call — see "Client reporting" below).
Accepted events are broadcast the same way as Backend-observed ones.

## Event Model

Every event, whether Backend-observed or client-reported, has the same shape:

```json
{
  "type": "login" | "catalog-publish" | "config-check" | "catalog-check" | "receipt-uploaded" | "inbox-poll" | "receipt-processed" | "receipt-archived",
  "source": "backend" | "client",
  "clientId": "...",
  "receiptId": "...",
  "outcome": "ok" | "unchanged" | "error",
  "detail": "...",
  "durationMs": 0,
  "timestamp": "<ISO-8601, set by the Backend on receipt/emission>"
}
```

`receiptId` is present only for receipt-related event types; `clientId` is present for every type
except events with no clear client owner. `detail` is a short free-text string (e.g. an HTTP status
or error message) — never a token, presigned URL, or full response/receipt body. `durationMs` is the
elapsed time (a plain number, milliseconds) of the specific network/S3 call the event represents —
e.g. the presigned GET for a `config-check`, the PUT for a `receipt-uploaded`, the S3 writes for a
`catalog-publish` — omitted when not measured (e.g. no clear single call, like a fully invalid
`deviceId` at `/login`).

### Backend-observed events (no client involvement — the Backend emits these directly)

| Event | Emitted from | Meaning |
|---|---|---|
| `login` | `AuthService` (`/login` handler) | A device logged in; `outcome` ok/error. |
| `catalog-publish` | `CatalogPublisher` | Config/catalog was (re)written to the Mediator — the initial startup write if missing, or the periodic overwrite; `outcome` ok/error. |
| `inbox-poll` | `InboxProcessor` | A poll of `/inbox/` ran; `detail` carries how many objects were found. |
| `receipt-processed` | `InboxProcessor` | A receipt object was read and parsed; `outcome` ok/error (e.g. invalid clientId/receiptId format). |
| `receipt-archived` | `InboxProcessor` | A receipt was copied to `/archive/` and removed from `/inbox/`; `outcome` ok, or error/warning per the existing copy-then-delete handling in `docs/decision/inbox-processing-model.md`. |

### Client-reported events (the Backend cannot observe these — direct client↔Mediator calls)

| Event | Reported from | Meaning |
|---|---|---|
| `config-check` / `catalog-check` | `CatalogSyncService` | Result of a conditional-GET poll; `outcome` unchanged (304), ok (200), or error. |
| `receipt-uploaded` | `ReceiptUploader` | Result of a PUT to a presigned upload URL; `outcome` ok or error. |

Issuing a presigned upload URL (`/upload-receipts`) is deliberately **not** a `receipt-uploaded`
event — that would conflate "a URL was handed out" with "the upload actually happened."

## Client Reporting

`AuthClient` doesn't report anything itself (the Backend already observes `/login` directly).
`CatalogSyncService` and `ReceiptUploader` each gain a small fire-and-forget report call after their
existing logic runs:

- Short request timeout (a couple of seconds).
- Silently dropped on failure or timeout — never retried, never logged as a client error in its own
  right (it's instrumentation, not core functionality).
- Never blocks or delays the client's real scheduled work; the report happens after the real
  operation's result is already known, on the same background thread, and its own failure is
  swallowed.

## Page Content

- Explainer text: what this demo is (detaching POS client↔server communication via a Mediator),
  drawn from `AGENTS.md`/`README.md`.
- Left-hand column (mirrors the right-hand "Session totals" sidebar): one short color-coded card per
  component — POS Client, Mediator, Backend — naming its technology and its role in this demo. Stacks
  above the main column on narrow viewports.
- Live sequence diagram: three lifelines (Client, Mediator, Backend, left-to-right), each a distinct
  color. Hand-authored static SVG (a sequence diagram fit better than a node/edge architecture
  diagram, which kept looking cluttered — the `archify` skill was tried but not used in the end) with
  plain JS adding a "pulse" class to the relevant arrow when a matching SSE event arrives, and
  updating that arrow's label to the specific event type.
- Recent-events feed: a bounded list (last ~100 events) of type/source/clientId/receiptId/outcome/
  durationMs/time.
- Session totals (right-hand sidebar, stacks below the main column on narrow viewports): running
  counts of receipts sent (`receipt-uploaded`), received (`receipt-processed`), and archived
  (`receipt-archived`) — all `outcome: ok` — since the page connected; reset on refresh, matching
  the rest of the page's "no history" stance.
- Connection indicator: whether the SSE stream is currently connected.
- Bound the number of distinctly tracked client nodes shown (e.g. latest 20 distinct `clientId`s) so
  the "Clients seen" list doesn't grow unbounded over a long-running demo.

## Security / Validation

- Never send tokens, presigned URLs, or full receipt/response bodies in an event — only the small
  fields in the Event Model above.
- Validate every incoming `POST /monitor/events` body (type allow-list, string length caps, request
  size cap) before broadcasting it — the feed renders event fields as text, never as HTML.
- A slow or stalled SSE viewer must not block broadcasting to others or leak memory — writes to a
  dead/blocked stream are best-effort; a stream that errors is removed from the broadcast set.

## Out of Scope

- Historical replay, persistent event log, or database.
- Exact bucket inventory / receipt drill-down screen.
- Heartbeat protocol or backend-outage support (the monitor assumes the Backend it's served from is
  up — if it's down, the page itself can't be reached anyway).
- Any new operator/auth system for viewing the page (it's public, like the rest of this demo).
- Multi-tenancy (matches the rest of the Backend's single-tenant scope).

## Testing

One runnable self-check per non-trivial piece of logic, no test framework (matching the rest of
this project):

- Backend: `POST /monitor/events` validation (rejects bad `type`, oversized fields/body; accepts a
  well-formed event).
- Backend: broadcasting to multiple connected streams, and that a stream which errors is dropped
  from the broadcast set without affecting the others.
- Client: the fire-and-forget report call never propagates its own failure/timeout to the caller
  (`CatalogSyncService`/`ReceiptUploader` continue exactly as before regardless of report outcome).

## Tech Stack

- No new dependencies. SSE via raw Node response streaming; page served via `fs.readFileSync` +
  `reply.type('text/html')`.
- Diagram SVG authored with help from the `archify` skill (design-time only — its output is
  hand-adapted into the static page, not consumed at runtime).
