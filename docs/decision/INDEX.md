# Architecture Decisions

| Decision | Summary |
|---|---|
| [live-monitor-observability](live-monitor-observability.md) | Existing Backend serves a live diagram + event feed over SSE at `GET /`; direct Backend events plus best-effort client reports via `POST /monitor/events`; no separate service, history, or outage support. |
| [client-mediator-direct-access](client-mediator-direct-access.md) | Client reads config/catalog via Backend-issued presigned URLs, and writes receipts directly using per-device, prefix-scoped STS credentials from `/login` — no per-upload Backend contact; change detection via conditional GET (ETag), not push. |
| [client-in-memory-state](client-in-memory-state.md) | Client keeps all state (JWT, config, catalog, receipt queue) in memory only; no disk persistence. |
| [single-backend-instance](single-backend-instance.md) | Backend runs as exactly one instance (Fly.io); no free always-on tier, so it scales to zero when idle; background timers assume no concurrent instance. |
| [inbox-processing-model](inbox-processing-model.md) | Backend drains the receipt inbox via copy-to-archive-then-delete, tolerating at-least-once reprocessing on partial failure. |
| [backend-catalog-publishing](backend-catalog-publishing.md) | Backend synthesizes config/catalog itself and publishes them to the Mediator on a timer; no admin API in this demo. |
| [backend-secrets-configuration](backend-secrets-configuration.md) | Backend secrets (S3 credentials, JWT secret, etc.) are supplied as Fly.io env vars/secrets, set independently of deploys. |
| [client-identity](client-identity.md) | No client authorization; `/login` accepts any well-formed id. The client generates and persists its own id in `client.properties` on first run. |
