# Single Backend Instance

## Status

Accepted

## Context

The Backend runs two background timers in-process: CatalogPublisher (synthesizes and writes config/catalog to the Mediator) and InboxProcessor (drains and archives uploaded receipts). Neither uses distributed locking or leader election. A second concurrently running instance would race both timers against the same bucket paths.

## Decision

The Backend is deployed as exactly one Fly.io instance (`fly scale count 1`). All POS clients connect to this one instance's URL. Running exactly one instance is an architectural requirement, not just a cost-saving choice.

Fly no longer offers a free tier for new accounts (as of this project), so the instance is not always-on: `fly.toml` keeps `auto_stop_machines = 'stop'` / `min_machines_running = 0`, and the machine stops when idle and restarts on the next incoming request. CatalogPublisher/InboxProcessor's timers simply pause while stopped and resume once the machine wakes — accepted for this demo rather than paying for an always-on VM.

## Consequences

- No horizontal scaling of the Backend in this demo.
- Scaling beyond one instance would require adding coordination (e.g. a distributed lock or single-leader election) around CatalogPublisher and InboxProcessor first — out of scope here.
- Config/catalog publishing and inbox processing only happen while the machine is running, i.e. shortly after some request wakes it — not on a strict wall-clock schedule while idle. Acceptable for a demo; a real deployment would need an always-on instance or an external scheduler.
