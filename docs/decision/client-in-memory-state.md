# Client In-Memory State

## Status

Accepted

## Context

The client is a headless simulator for this demo, not a production POS. It holds a JWT, the current config/catalog, and a queue of receipts pending upload. Adding durable local storage (file-based queue, embedded DB) is real engineering effort that doesn't serve the demo's purpose of showing the client/mediator/backend sync architecture.

## Decision

All client state lives in memory only, for the life of the process. No disk-backed queue or persistence layer.

## Consequences

- A process restart silently loses any receipts simulated but not yet uploaded, and the current config/catalog/JWT (all cheaply re-fetched via `/login` on next start).
- If this client is ever taken beyond a demo, this is the first thing to revisit — a real POS cannot lose unsynced receipts on restart.
