#!/bin/sh
# Creates the runtime user that matches PUID/PGID, fixes ownership of the
# mounted volume, then drops privileges. Running as root is only the wrapper:
# Java itself always runs as the configured non-root user.
set -eu

PUID="${PUID:-1000}"
PGID="${PGID:-1000}"

run_java() {
  # shellcheck disable=SC2086  # JAVA_OPTS is intentionally word-split.
  exec java ${JAVA_OPTS:-} -jar /app/app.jar "$@"
}

if [ "$(id -u)" != "0" ]; then
  # Already non-root (compose `user:` or `--user`): just run.
  run_java "$@"
fi

if ! getent group "$PGID" >/dev/null 2>&1; then
  groupadd --gid "$PGID" digest
fi
if ! getent passwd "$PUID" >/dev/null 2>&1; then
  useradd --uid "$PUID" --gid "$PGID" --no-create-home \
          --home-dir /tmp --shell /usr/sbin/nologin digest
fi

# The bind mount is owned by the host; align it with the runtime user. A
# read-only or root-squashed mount cannot be fixed here, so warn and continue.
mkdir -p /data
chown -R "$PUID:$PGID" /data 2>/dev/null \
  || echo "entrypoint: could not chown /data to $PUID:$PGID; check mount permissions" >&2

exec gosu "$PUID:$PGID" java ${JAVA_OPTS:-} -jar /app/app.jar "$@"
