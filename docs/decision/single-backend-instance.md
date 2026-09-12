# Single Backend Instance

## Status

Accepted

## Context

The Backend runs two background timers in-process: CatalogPublisher (synthesizes and writes config/catalog to the Mediator) and InboxProcessor (drains and archives uploaded receipts). Neither uses distributed locking or leader election. A second concurrently running instance would race both timers against the same bucket paths.

## Decision

The Backend is deployed as exactly one always-on instance (Fly.io free-tier single VM). All POS clients connect to this one instance's URL. This is an architectural requirement, not just a cost-saving choice.

## Consequences

- No horizontal scaling of the Backend in this demo.
- Scaling beyond one instance would require adding coordination (e.g. a distributed lock or single-leader election) around CatalogPublisher and InboxProcessor first — out of scope here.
