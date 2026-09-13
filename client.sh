#!/usr/bin/env bash
# Runs the POS client simulator using client.properties in the CURRENT
# directory (created there on first run if missing) - see docs/spec/client.md
# and docs/decision/client-identity.md. Lets you run several independent
# client instances without copying the source: just invoke this script from
# a different directory per instance.
#
# Usage:
#   /path/to/client.sh                  # uses ./client.properties
#   /path/to/client.sh some-other.properties
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CLIENT_DIR="$SCRIPT_DIR/client"
BIN="$CLIENT_DIR/build/install/client/bin/client"

if [ ! -x "$BIN" ]; then
  echo "Building client (first run only)..." >&2
  (cd "$CLIENT_DIR" && ./gradlew installDist -q)
fi

exec "$BIN" "$@"
