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

Two supported paths. Both end with the same container, a bind-mounted SQLite database,
and the same healthcheck.

| Path | Use when |
|---|---|
| **A. Compose from source** | The Unraid host can clone/pull the repo, run `docker compose`, and build the image (the first build downloads base images and Gradle dependencies) |
| **B. Offline image + template** | You build on a development machine and copy a ready image; the host needs no source tree and no Compose plugin. This is what `docker/export-image.sh` and `docker/unraid/fribourg-events-digest.xml` support |

### 2.1 Prepare the share (both paths)

1. Create the deployment folder, e.g. `/mnt/user/appdata/fribourg-digest`.
2. Decide the owner. Unraid's defaults are `99:100` (`nobody:users`); other shares may use
   `1000:1000`. Check with:

   ```bash
   id <your-user>        # uid=... gid=...
   ```

   Set `PUID`/`PGID` to that. The entrypoint creates the runtime user and tries to `chown`
   `/data` on the host mount; a read-only or root-squashed mount cannot be fixed there.
3. Back up `.env`/template values separately; they contain the bot token.

### 2.2 Option A — build with Compose

1. Put the repository in the deployment folder (`git clone` or copy the tree).
2. Install **Docker Compose Manager** from Community Applications (Unraid does not ship
   `docker compose` by default). Verify with `docker compose version`.
3. Configure:

   ```bash
   cp .env.example .env
   $EDITOR .env
   ```

   Minimum for a real delivery:

   ```dotenv
   TELEGRAM_BOT_TOKEN=123456:replace-me
   TELEGRAM_CHAT_ID=42
   DIGEST_RUN_ON_STARTUP=true
   PUID=99
   PGID=100
   ```

4. Create the data folder and start:

   ```bash
   mkdir -p data
   docker compose up -d --build
   ```

5. Verify with §2.4.

### 2.3 Option B — export the image and use the Unraid template

1. **On the development machine**, from the repository root:

   ```bash
   ./docker/export-image.sh 1.0.0
   ```

   This builds `linux/amd64` (override with `PLATFORM=...`), writes
   `dist/fribourg-events-digest-1.0.0.tar.gz`, a `.sha256` beside it, and a matching
   `dist/fribourg-events-digest-1.0.0.xml` template with `<Repository>` set to this tag.
   Use `SKIP_BUILD=1` to export an image that is already built. On Apple Silicon, make
   sure buildx/QEMU emulation is available (or build on an amd64 host) so the Unraid
   image is `linux/amd64`.

2. **Copy the archive and the template** to the Unraid host:

   ```bash
   scp dist/fribourg-events-digest-1.0.0.tar.gz* \
       root@<unraid>:/mnt/user/appdata/fribourg-digest/
   scp dist/fribourg-events-digest-1.0.0.xml \
       root@<unraid>:/boot/config/plugins/dockerMan/templates-user/my-fribourg-events-digest.xml
   ```

   Prefer a disk share over `/boot` for the archive: `/boot` is FAT32 and caps files at
   4 GB. Keep the `.sha256` file and verify with `sha256sum -c` on the host if desired.

3. **Load the image** in the Unraid terminal:

   ```bash
   docker load -i /mnt/user/appdata/fribourg-digest/fribourg-events-digest-1.0.0.tar.gz
   docker image ls | grep fribourg-events-digest
   ```

4. **Install the template** (already copied in step 2 into
   `/boot/config/plugins/dockerMan/templates-user/my-fribourg-events-digest.xml`).

   If you exported a tag other than `1.0.0`, edit `<Repository>` in that file so it matches
   the loaded image, or re-copy it after editing the repo copy on the development machine.

5. **Create the container from the UI.** Unraid **Docker** tab → **Add Container** →
   select the `fribourg-events-digest` template. Fill in:

   - **Telegram bot token** and **Telegram chat id** (required unless `DIGEST_DRY_RUN=true`)
   - **Data** host path, default `/mnt/user/appdata/fribourg-digest/data`
   - **PUID**/**PGID** if the share is not `99:100`
   - **Run on startup** = `true` for the first deploy

   Apply. The template fixes `DB_PATH=/data/digest.db`, the `8080` port, `TZ`, and the
   source/threshold defaults. Because the image is local, do **not** use “Force update”:
   Unraid has no registry to pull from. To upgrade, export a new tag, `docker load` it, edit
   the template's repository, and apply.

   CLI equivalent (if you prefer not to use the template):

   ```bash
   docker run -d --name fribourg-events-digest \
     --restart unless-stopped --shm-size=1g \
     -p 8080:8080 \
     -v /mnt/user/appdata/fribourg-digest/data:/data \
     -e PUID=99 -e PGID=100 -e TZ=Europe/Zurich -e DB_PATH=/data/digest.db \
     -e TELEGRAM_BOT_TOKEN=... -e TELEGRAM_CHAT_ID=... \
     -e DIGEST_RUN_ON_STARTUP=true \
     fribourg-events-digest:1.0.0
   ```

6. Verify with §2.4.

### 2.4 Verify either path

```bash
# Option A
docker compose ps                 # wait for (healthy)
docker compose logs -f digest

# Option B
docker ps --filter name=fribourg-events-digest
docker logs -f fribourg-events-digest

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

The commands below show the Compose form. With the template deployment, use the Docker CLI
equivalents: `docker logs [-f] fribourg-events-digest`, `docker restart fribourg-events-digest`,
`docker stop|start fribourg-events-digest`. Environment changes go through the Unraid UI
(edit the container and Apply), which recreates it.

### 3.1 Change configuration

Compose: edit `.env`, then recreate (a plain `restart` keeps the old environment):

```bash
docker compose up -d
```

Template: Docker tab → the container → **Edit** → change the variable → **Apply**
(Unraid recreates the container). A plain `docker restart` keeps the old environment.

Cron, zone, window, source toggles, thresholds, and credentials all take effect this way;
no rebuild is needed. A rebuild or a new exported image is only needed for code changes.

### 3.2 Manual re-run

There is no HTTP trigger. The supported ways to run now:

1. Make sure `DIGEST_RUN_ON_STARTUP=true` is in effect, then restart:
   - Compose: set it in `.env` and run `docker compose up -d` (not `restart`: a restart
     keeps the old environment), then optionally set it back to `false`.
   - Template: set the variable to `true` and **Apply** in the UI, then optionally set it
     back to `false`; a plain `docker restart` is enough when it is already `true`.
2. Wait for the next cron firing (`DIGEST_CRON`, default Monday 07:00 `Europe/Zurich`).

A run takes roughly a minute: up to 44 insitu pages and 15 bka pages at 1 req/s plus the
eventfrog browser pages. Do not run a second instance against the same SQLite file.

### 3.3 Logs

```bash
# Compose
docker compose logs --tail=200 digest
docker compose logs -f digest | grep -E 'digest_run_|source_sync|notification_delivery'

# Template / plain Docker
docker logs --tail 200 fribourg-events-digest
docker logs -f fribourg-events-digest | grep -E 'digest_run_|source_sync|notification_delivery'
```

Useful greps (same substitution applies):

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
`sqlite3 .backup`). `$DATA_DIR` is the Compose repo's `data/` or the template's
`/mnt/user/appdata/fribourg-digest/data`.

```bash
# Compose: stop digest
# Template: docker stop fribourg-events-digest
docker stop <container>                     # or: docker compose stop digest

cp -a "$DATA_DIR/digest.db" "$DATA_DIR/digest.db-wal" "$DATA_DIR/digest.db-shm" \
      /path/to/backup/ 2>/dev/null || true
# or, from the host if sqlite3 is installed:
# sqlite3 "$DATA_DIR/digest.db" ".backup '/path/to/backup/digest.db'"

docker start <container>                    # or: docker compose start digest
```

Back up `.env` (Compose) or the template values (Unraid UI) separately; they hold the bot
token.

### 3.5 Restore

```bash
docker stop <container>                     # or: docker compose stop digest
cp /path/to/backup/digest.db "$DATA_DIR/digest.db"
rm -f "$DATA_DIR/digest.db-wal" "$DATA_DIR/digest.db-shm"
chown "$PUID:$PGID" "$DATA_DIR/digest.db"
docker start <container>                    # or: docker compose start digest
```

The next run validates the schema with `ddl-auto=validate`; a missing Flyway history table
means the file was not a valid database, so restore a known-good copy.

### 3.6 Reset the database

Deleting the file is safe: Flyway recreates the schema on the next start and the next run
repopulates it. Execution history is not part of the database, so nothing else is lost.

```bash
docker stop <container>                     # or: docker compose stop digest
rm -f "$DATA_DIR/digest.db" "$DATA_DIR/digest.db-wal" "$DATA_DIR/digest.db-shm"
docker start <container>                    # or: docker compose start digest
```

### 3.7 Upgrade and roll back

**Compose:** `git pull && docker compose up -d --build`.

**Template / offline image:**

```bash
# on the development machine
./docker/export-image.sh 1.1.0
# copy the archive over, then on Unraid
docker load -i /mnt/user/appdata/fribourg-digest/fribourg-events-digest-1.1.0.tar.gz
# Docker UI -> Edit the container -> set the repository to fribourg-events-digest:1.1.0 -> Apply
# (or edit <Repository> in the template copy before applying)
```

Keep the previous archive; rolling back means loading it again and setting the repository
back to the old tag. Flyway applies any new migration at startup; migrations are
forward-only, so if a migration is incompatible with the old code, restore the matching
database backup as well.

---

## 4. Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Container shows `unhealthy` | App not up yet, Actuator not on 8080, or a startup validation failure | `docker compose logs digest` (or `docker logs fribourg-events-digest`); the exception names the property. Check `start_period` was enough. |
| `permission denied` on `/data` | Bind mount not writable by `PUID:PGID` | Set `PUID`/`PGID` to the host owner (`id <user>`), ensure the share is not read-only, then restart. |
| `SQLITE_BUSY` / `database is locked` | A second process or a stale connection writing the file; long WAL checkpoint | Never run a second container/manual JVM on the same DB. Restart the container; check that only one instance uses `./data`. |
| Chromium exits immediately / `session not created` | `/dev/shm` too small, or `/tmp` not writable | Keep `shm_size: 1gb` (Compose) or `--shm-size=1g`; ensure `HOME=/tmp` is writable; restart. |
| `chromedriver`/`chromium` not found | Wrong image or an old image built from the Ubuntu JRE base | Rebuild with the provided Dockerfile; it installs Debian's browser/driver pair and verifies both at build time. |
| eventfrog source `FAILED` | Page layout changed, or the browser timed out | Check the `source_sync` error; run the live smoke (below) and update selectors/fixtures. Other sources still deliver a `PARTIAL` digest. |
| Telegram `401 Unauthorized` | Bad bot token | Re-copy the token from `@BotFather`; rebuild not needed, restart after editing `.env`. |
| Telegram `400 Bad Request` | Wrong chat id, bot not a member of the chat, or a rejected message | Check `TELEGRAM_CHAT_ID`; message the bot once so the chat exists; verify the bot can post. |
| Telegram `429` / delivery `PARTIAL` | Too many parts sent too quickly, or a chat-wide limit | The client retries twice using the API's `retry_after` (max 30 s). If it still fails, lower traffic by reducing the window/item cap or retry later. |
| Digest arrives with old events | Window/cron or time zone misconfigured | Check `DIGEST_WINDOW_DAYS`, `DIGEST_ZONE`, and the cron (`.env` or the template variable); restart/Apply. The window is local-midnight based, not "now + 14 days". |
| Digest is empty | All sources failed, or the window has no events | Check `digest_run_finished status` and `source_sync` lines. The pipeline skips the message entirely when every source fails (unless `DIGEST_SEND_FAILURE_NOTICE=true`). |
| Same event listed twice | Dedup thresholds too strict for these titles/venues | Check the pair's normalized title/venue/city/date; tune `DEDUP_TITLE_THRESHOLD`/`DEDUP_VENUE_THRESHOLD`/`DEDUP_DATE_WINDOW_DAYS` and restart. Loosening them risks false merges. |
| Two unrelated events merged | Thresholds too loose | Raise the title/venue thresholds. The date window and city block are the other guards. |
| Startup run repeats on every restart | `DIGEST_RUN_ON_STARTUP=true` | Set it to `false` and apply (`docker compose up -d`, or the Unraid UI Apply) once the first deploy is verified. |
| Build fails in `docker compose up --build` | Gradle download blocked, or no Java toolchain in the build stage | The build stage uses `eclipse-temurin:25-jdk` and the committed wrapper; it needs network on first build. Retry, or use the offline image path (Option B) and `docker load`. |
| `docker load` succeeds but the template cannot start the container | The template's `<Repository>` tag does not match the loaded image, or Unraid tried to pull instead | `docker image ls`; edit the template repository to the exact `name:tag`, apply again. Do not use “Force update” for a local image. |
| Unraid says the container/update is not available | The image has no registry; Unraid's update check has nothing to query | Expected for the offline path. Upgrade by loading a new archive and pointing the template at the new tag. |
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
- Rotate the bot token by editing `.env` (Compose) or the template variable (Unraid UI) and
  applying; no code change or rebuild needed.
- The container exposes only Actuator `health` and `info` on port 8080. Do not expose the
  SQLite file or add inbound trigger endpoints.
- Keep the source rate limits (`DIGEST_RATE_LIMIT_PER_SECOND=1`,
  `DIGEST_MAX_REQUESTS_PER_RUN=60`) at or below the spec values.

---

## 6. Deployment evidence checklist

Use this when recording the v1 verification in [`SPEC.md`](SPEC.md) §8. Commands show the
Compose form first and the template/plain-Docker form in parentheses.

- [ ] `docker compose ps` (`docker ps --filter name=fribourg-events-digest`) shows
      `healthy`.
- [ ] `docker compose logs digest | grep digest_run_finished` (`docker logs ... | grep ...`)
      shows one startup run.
- [ ] Telegram received the digest; each part is numbered `(n/m)`.
- [ ] The data folder contains `digest.db` with `event`, `event_source_ref`, and
      `event_occurrence` rows.
- [ ] Restarting the container keeps the rows (same counts, plus any new ones):
      `docker compose restart digest` / `docker restart fribourg-events-digest`.
- [ ] Delete the DB, restart, and confirm Flyway recreates it and the next run repopulates.
- [ ] `docker image inspect fribourg-events-digest:1.0.0` contains no bot token (secrets are
      runtime env only), and a sample log contains no token or chat id.
- [ ] Cron/timezone override verified by changing `DIGEST_CRON`/`DIGEST_ZONE` and
      restarting/applying without a rebuild.
