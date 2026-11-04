#!/bin/sh
# Container healthcheck: static command (no shell features) that succeeds only
# when Actuator reports UP. Matches the exposed surface — health and info only.
set -eu

PORT="${SERVER_PORT:-8080}"

body="$(curl --fail --silent --show-error --max-time 4 \
  "http://127.0.0.1:${PORT}/actuator/health")" || exit 1

printf '%s' "$body" | grep -q '"status":"UP"'
