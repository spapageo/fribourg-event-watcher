# Runbook

Operator guide for deploying and keeping the digest alive on an Unraid host (or any
Docker host). Architecture: [`architecture.md`](architecture.md). Configuration keys:
[`README.md`](README.md#configuration) and [`SPEC.md`](SPEC.md) §3.

The service is a single container with no inbound trigger endpoint. You change behaviour
by editing environment values and restarting; you inspect it through Actuator health and
container logs.

---

## 1. What runs in the container

| Part | Detail |
|---|---|
| Java | Eclipse Temurin 25 JRE copied onto a Debian bookworm userland |
| Browser | Debian `chromium` + `chromium-driver` (real debs, verified at image build) |
| Process | `tini` → `docker/entrypoint.sh` → `java -jar /app/app.jar` |
| User | `PUID:PGID` (default `1000:1000`); the entrypoint creates it and drops root |
| Home | `/tmp` (writable, disposable; Chromium needs it) |
| Data | `/data/digest.db` on a bind mount; the entrypoint `chown`s `/data` when possible |
| Port | `8080` inside; Compose maps `${DIGEST_HTTP_PORT:-8080}` |
| Health | `/app/healthcheck.sh` calls `GET /actuator/health`, requires `"status":"UP"` |
| Shared memory | Compose sets `shm_size: 1gb`; Chromium crashes with the 64 MB default |

`DB_PATH` is pinned to `/data/digest.db` by `docker-compose.yml`, so a `DB_PATH` value in
`.env` does not affect the container. Use `DB_PATH` only for local runs from `source/`.

---

## 2. Unraid installation

### 2.1 Prepare the share

1. Create a share (or folder) for the deployment, e.g. `/mnt/user/appdata/fribourg-digest`.
2. Put the repository there (git clone, or copy the tree). Compose builds from this
   directory.
3. Create the data folder: `mkdir -p data`.
4. Find the owner Unraid should use:

   ```bash
   id <your-user>        # uid=1000(...) gid=1000(...)
   ```

   Keep `PUID`/`PGID` equal to that. If the share uses a different owner, set them to
   match; the entrypoint will align `/data` on the host mount.

### 2.2 Install the Compose plugin

Unraid does not ship the `docker compose` subcommand by default. Install **Docker Compose
Manager** from Community Applications, or use the Unraid terminal with a Compose binary
that provides `docker compose`. Verify:

```bash
docker compose version
```

### 2.3 Configure

```bash
cp .env.example .env
$EDITOR .env
```

Minimum for a real delivery:

```dotenv
TELEGRAM_BOT_TOKEN=123456:replace-me
TELEGRAM_CHAT_ID=42
DIGEST_RUN_ON_STARTUP=true
PUID=1000
PGID=1000
```

Recommended for the first deploy: `DIGEST_RUN_ON_STARTUP=true` so a digest is produced
immediately instead of waiting for Monday. Set it back to `false` afterwards if you do not
want every restart to send.

Do **not** commit `.env`; it is gitignored. Back it up separately (it contains the bot
token).

### 2.4 Start and verify

```bash
docker compose up -d --build
docker compose ps                 # wait for (healthy)
docker compose logs -f digest
curl -fsS http://<host>:8080/actuator/health
```

First-run signs in the log:

```
startup_run_started reason=digest.run-on-startup is true
digest_run_triggered trigger=startup window_from=... window_to=...
digest_run_started run_id=... sources=[insitu,bka,eventfrog] dry_run=false
source_sync run_id=... source=insitu status=OK pages_fetched=... events_seen=...
source_sync run_id=... source=bka status=OK ...
source_sync run_id=... source=eventfrog status=OK ...
notification_delivery run_id=... channel=telegram status=DELIVERED message_count=...
digest_run_finished run_id=... status=SUCCESS events_selected=... events_merged=...
```

`PARTIAL` instead of `SUCCESS` is not a crash: one source failed and the digest says so in
its footer. Find the failing source on its `source_sync` line.

---

## 3. Routine operations

### 3.1 Change configuration

Edit `.env`, then recreate or restart:

```bash
docker compose up -d        # recreates if environment changed
# or, for a pure restart with the same env:
docker compose restart digest
```

Cron, zone, window, source toggles, thresholds, and credentials all take effect this way;
no rebuild is needed. A rebuild is only needed for code changes.

### 3.2 Manual re-run

There is no HTTP trigger. The supported ways to run now:

1. Set `DIGEST_RUN_ON_STARTUP=true` in `.env` and run `docker compose up -d` (not
   `restart`: a restart keeps the old environment). The recreated container runs once at
   startup, then you can set the flag back to `false` and `docker compose up -d` again.
2. Wait for the next cron firing (`DIGEST_CRON`, default Monday 07:00 `Europe/Zurich`).

A run takes roughly a minute: up to 44 insitu pages and 15 bka pages at 1 req/s plus the
eventfrog browser pages. Do not run a second instance against the same SQLite file.

### 3.3 Logs

```bash
docker compose logs --tail=200 digest
docker compose logs -f digest | grep -E 'digest_run_|source_sync|notification_delivery'
```

Useful greps:

```bash
# last run outcome
docker compose logs digest | grep 'digest_run_finished' | tail -1
# failed sources
docker compose logs digest | grep 'source_sync' | grep 'status=FAILED'
# delivery problems
docker compose logs digest | grep 'notification_delivery'
```

Logs rotate with the Docker log driver. The service keeps no run history in SQLite by
design; if you need longer retention, configure the Docker log driver (e.g. `json-file`
with `max-size`/`max-file`) on the host.

### 3.4 Backup

SQLite runs in WAL mode, so copy the database **while the container is stopped** (or use
`sqlite3 .backup`):

```bash
docker compose stop digest
cp -a data/digest.db data/digest.db-wal data/digest.db-shm /path/to/backup/ 2>/dev/null || true
# or, from the host if sqlite3 is installed:
# sqlite3 data/digest.db ".backup '/path/to/backup/digest.db'"
docker compose start digest
```

Back up `.env` too, separately, because it holds the bot token.

### 3.5 Restore

```bash
docker compose stop digest
cp /path/to/backup/digest.db data/digest.db
rm -f data/digest.db-wal data/digest.db-shm
chown "$PUID:$PGID" data/digest.db
docker compose start digest
```

The next run validates the schema with `ddl-auto=validate`; a missing Flyway history table
means the file was not a valid database, so restore a known-good copy.

### 3.6 Reset the database

Deleting the file is safe: Flyway recreates the schema on the next start and the next run
repopulates it. Execution history is not part of the database, so nothing else is lost.

```bash
docker compose stop digest
rm -f data/digest.db data/digest.db-wal data/digest.db-shm
docker compose start digest
```

### 3.7 Upgrade and roll back

```bash
git pull
docker compose up -d --build
```

Flyway applies any new migration at startup. To roll back code, check out the previous
tag/commit and rebuild. Schema migrations are forward-only; if a migration is
incompatible with the old code, restore the matching database backup as well.

---

## 4. Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `docker compose ps` shows `unhealthy` | App not up yet, Actuator not on 8080, or a startup validation failure | `docker compose logs digest`; the exception names the property. Check `start_period` was enough. |
| `permission denied` on `/data` | Bind mount not writable by `PUID:PGID` | Set `PUID`/`PGID` to the host owner (`id <user>`), ensure the share is not read-only, then restart. |
| `SQLITE_BUSY` / `database is locked` | A second process or a stale connection writing the file; long WAL checkpoint | Never run a second container/manual JVM on the same DB. Restart the container; check that only one instance uses `./data`. |
| Chromium exits immediately / `session not created` | `/dev/shm` too small, or `/tmp` not writable | Keep `shm_size: 1gb` (Compose) or `--shm-size=1g`; ensure `HOME=/tmp` is writable; restart. |
| `chromedriver`/`chromium` not found | Wrong image or an old image built from the Ubuntu JRE base | Rebuild with the provided Dockerfile; it installs Debian's browser/driver pair and verifies both at build time. |
| eventfrog source `FAILED` | Page layout changed, or the browser timed out | Check the `source_sync` error; run the live smoke (below) and update selectors/fixtures. Other sources still deliver a `PARTIAL` digest. |
| Telegram `401 Unauthorized` | Bad bot token | Re-copy the token from `@BotFather`; rebuild not needed, restart after editing `.env`. |
| Telegram `400 Bad Request` | Wrong chat id, bot not a member of the chat, or a rejected message | Check `TELEGRAM_CHAT_ID`; message the bot once so the chat exists; verify the bot can post. |
| Telegram `429` / delivery `PARTIAL` | Too many parts sent too quickly, or a chat-wide limit | The client retries twice using the API's `retry_after` (max 30 s). If it still fails, lower traffic by reducing the window/item cap or retry later. |
| Digest arrives with old events | Window/cron or time zone misconfigured | Check `DIGEST_WINDOW_DAYS`, `DIGEST_ZONE`, and the cron in `.env`; restart. The window is local-midnight based, not "now + 14 days". |
| Digest is empty | All sources failed, or the window has no events | Check `digest_run_finished status` and `source_sync` lines. The pipeline skips the message entirely when every source fails (unless `DIGEST_SEND_FAILURE_NOTICE=true`). |
| Same event listed twice | Dedup thresholds too strict for these titles/venues | Check the pair's normalized title/venue/city/date; tune `DEDUP_TITLE_THRESHOLD`/`DEDUP_VENUE_THRESHOLD`/`DEDUP_DATE_WINDOW_DAYS` and restart. Loosening them risks false merges. |
| Two unrelated events merged | Thresholds too loose | Raise the title/venue thresholds. The date window and city block are the other guards. |
| Startup run repeats on every restart | `DIGEST_RUN_ON_STARTUP=true` | Set it to `false` and apply with `docker compose up -d` once the first deploy is verified. |
| Build fails in `docker compose up --build` | Gradle download blocked, or no Java toolchain in the build stage | The build stage uses `eclipse-temurin:25-jdk` and the committed wrapper; it needs network on first build. Retry; check the build log. |
| Container is healthy but no digest ever arrives | Cron mismatch or `TZ`/zone misunderstanding | `DIGEST_CRON` is a Spring cron with seconds and runs in `DIGEST_ZONE`; verify with the manual re-run above. |

### Live smoke checks

Run these from `source/` on the host (not inside the container), one at a time:

```bash
./gradlew test -PincludeTags=live --tests 'ch.fribourgevents.source.insitu.InsituLiveTest'
./gradlew test -PincludeTags=live --tests 'ch.fribourgevents.source.bka.BkaLiveTest'
./gradlew test -PincludeTags=live --tests 'ch.fribourgevents.source.eventfrog.EventfrogLiveTest'
./gradlew test -PincludeTags=live --tests 'ch.fribourgevents.source.browser.BrowserLiveTest'
TELEGRAM_BOT_TOKEN=... TELEGRAM_CHAT_ID=... \
  ./gradlew test -PincludeTags=live --tests 'ch.fribourgevents.notify.telegram.TelegramLiveTest'
```

Each adapter logs its event/page counts; record changed numbers in
[`sources.md`](sources.md). Never run all live tests in parallel or in a tight loop.

---

## 5. Security and secrets

- Secrets come only from the environment (`env_file: .env`). They are not baked into the
  image and are never logged; Telegram errors redact the API description and the tokenized
  URL never appears in exception messages.
- `.env`, the SQLite file, and `data/` are gitignored. Do not commit them.
- Rotate the bot token by editing `.env` and restarting; no code change or rebuild needed.
- The container exposes only Actuator `health` and `info` on port 8080. Do not expose the
  SQLite file or add inbound trigger endpoints.
- Keep the source rate limits (`DIGEST_RATE_LIMIT_PER_SECOND=1`,
  `DIGEST_MAX_REQUESTS_PER_RUN=60`) at or below the spec values.

---

## 6. Deployment evidence checklist

Use this when recording the v1 verification in [`SPEC.md`](SPEC.md) §8:

- [ ] `docker compose ps` output shows `healthy`.
- [ ] `docker compose logs digest | grep digest_run_finished` shows one startup run.
- [ ] Telegram received the digest; each part is numbered `(n/m)`.
- [ ] `data/digest.db` exists and contains `event`, `event_source_ref`,
      `event_occurrence` rows.
- [ ] `docker compose restart digest` keeps the rows (same counts, plus any new ones).
- [ ] Delete the DB, restart, and confirm Flyway recreates it and the next run repopulates.
- [ ] `docker image inspect fribourg-events-digest:dev` contains no bot token (secrets are
      runtime env only), and a sample `docker compose logs` contains no token or chat id.
- [ ] Cron/timezone override verified by changing `DIGEST_CRON`/`DIGEST_ZONE` and
      restarting without a rebuild.
