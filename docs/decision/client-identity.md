# Client Identity

## Status

Accepted

## Context

The Backend needs some id to attribute a client's login and its uploaded receipts (`clientId` in the receipt body, used to build the archive path). We initially had the Backend validate `deviceId` against a static `DEVICE_IDS` allow-list, standing in for real device provisioning/fleet management. For this demo, authorizing which clients may connect isn't a concern worth modeling — only having a stable id per client instance is.

## Decision

There is no client authorization. `/login` accepts any `deviceId` matching the safe-id format (`^[A-Za-z0-9_-]{1,64}$`) — no registry, no secret.

The client generates its own id instead of being assigned one: on startup, if `client.id` in `client.properties` is blank, the client generates a UUID, writes it back into that file, and uses it from then on (including across restarts, since the properties file is the one piece of client state that persists to disk — see [client-in-memory-state](client-in-memory-state.md)). A user can force a new identity by clearing or editing that line by hand.

## Consequences

- No concept of "provisioning" a device on the Backend; any generated id can log in and upload receipts.
- `client.properties` is no longer purely static input — the client writes to it once, on first run. This is a narrow, deliberate exception to the client's in-memory-only state.
- If authorization is ever needed, this is the decision to revisit (would reintroduce a registry and something to check the generated id against, e.g. an approval step before first login).
