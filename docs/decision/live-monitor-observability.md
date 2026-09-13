# Live Monitor Observability

## Status

Accepted

## Context

The demo benefits from a live view of what's actually happening: client logins, config/catalog
checks, record uploads, polling, processing, and archiving. Some of these the Backend already
performs itself and can observe directly; others (the client's direct calls to the Mediator) it
physically cannot see, since they never touch the Backend.

## Decision

Host the monitor page and its event stream in the existing Node Backend — no separate service,
database, or durable history. See [monitor spec](../spec/monitor.md) for the full design; summary:

- Backend-observed activity (login, inbox poll, record processed, record archived) is emitted
  directly from the code that already does that work.
- Client-only activity (config/catalog check, record upload) is reported by the Java client via a
  fire-and-forget, JWT-authenticated `POST /monitor/events` call — never retried, never blocking the
  client's real work, silently dropped on failure.
- The browser gets live updates via `GET /monitor/events` (Server-Sent Events, no library — plain
  Node response streaming). `GET /` serves one combined static page: brief explainer text, a live
  SVG diagram (visual layout designed with the `archify` skill, but driven at runtime by our own JS
  reacting to real SSE events, not Archify's own canned animation), and a bounded recent-events feed.
- No replay on connect, no persistence, no historical counters.

## Consequences

- One existing deployment hosts both the demo API and the monitor — Backend downtime also means the
  monitor is unreachable; accepted, since the monitor is a demonstration aid, not a health system.
- Lost/dropped event reports are simply invisible on the page — it shows activity observed since
  connection, not a complete or guaranteed-accurate audit.
- Adds a small amount of traffic and a few new event-emission call sites to the Backend and client;
  none of it changes core business logic (receipt sync still works identically whether or not
  anyone has the monitor page open).
- If real usage/history tracking is ever needed, this decision is the one to revisit (would require
  persistence, which is deliberately out of scope here).
