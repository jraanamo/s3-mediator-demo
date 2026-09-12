# Backend Catalog Publishing

## Status

Accepted

## Context

The client needs config/catalog data to exist in the Mediator bucket. A real system would have a back-office UI or admin API for tenants to manage this; building that is out of scope for a demo focused on the client/mediator/backend sync architecture.

## Decision

The Backend synthesizes config and catalog data itself. On startup it writes `/config/config.json` and `/catalog/catalog.json` if either is missing, so the client always has something to fetch. On a timer (`PUBLISH_INTERVAL_SECONDS`), it regenerates and overwrites both — the resulting new `ETag` is what drives the client's conditional-GET polling. There is no admin API to author this data by hand.

## Consequences

- Single tenant, single config/catalog — no per-tenant authoring flow exists.
- If tenant-editable catalogs are ever needed, this decision is the one to revisit (would add write endpoints instead of/alongside synthesis).
