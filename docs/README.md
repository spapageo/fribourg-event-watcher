# Fribourg & Bern Event Digest

A small Spring Boot service that reads `insitu.live`, `bka.ch`, and `eventfrog.ch`, stores
the events in SQLite, deduplicates the same event across sources, and sends a chronological
14-day digest to Telegram every Monday at 07:00 `Europe/Zurich`.

It is built for one private deployment on an Unraid host. There is no inbound trigger
endpoint; the schedule and the startup flag are the only ways to run it.

- Spec and success criteria: [`SPEC.md`](SPEC.md)
- Architecture and data model: [`architecture.md`](architecture.md)
- Deployment and operations: [`runbook.md`](runbook.md)
- Source selectors and quirks: [`sources.md`](sources.md)
- Implementation plan and task history: [`plan.md`](plan.md), [`todo.md`](todo.md)

---

## Quickstart

Prerequisites:

- Java 25 (the Gradle toolchain resolves it; a full JDK is required, not just a JRE)
- Docker (plus the Compose plugin if you deploy by building from source)
- Network access for a real run; the default test suite is fully offline

### 1. Build and test from a clone

```bash
cd source
./gradlew spotlessApply build        # format, tests, JaCoCo gate, bootJar
```

`build` excludes the `live` tag, so it never touches the network. To run a single live
smoke, see [Live checks](#live-checks).

### 2. Render a digest locally without sending it

`digest.dry-run=true` renders the digest to the log and does not need Telegram
credentials. A dry run still fetches the sources, so it needs network access.

```bash
cd source
mkdir -p ../data
./gradlew bootRun --args='--digest.dry-run=true --digest.run-on-startup=true'
```

The SQLite file defaults to `../data/digest.db` when running from `source/`. Look for the
`digest_run_started`, one `source_sync` per source, `digest_run_dry_run`, and
`digest_run_finished` log lines.

### 3. Deploy with Docker Compose

```bash
cp .env.example .env
$EDITOR .env                     # Telegram credentials, at minimum
mkdir -p data
docker compose up -d --build
docker compose ps                # wait for "healthy"
docker compose logs -f digest    # watch the first run
curl -fsS http://localhost:8080/actuator/health
```

The container persists the database in `./data` and runs Java as `PUID:PGID`
(default `1000:1000`). Set `digest.run-on-startup=true` (or `DIGEST_RUN_ON_STARTUP=true`)
for the first deployment so a run happens immediately instead of waiting for Monday.
See [`runbook.md`](runbook.md) for the Unraid-specific steps.

### 4. Offline Unraid deployment (no build on the host)

Build the image on a development machine, export it as an archive, and import it on the
Unraid host:

```bash
./docker/export-image.sh 1.0.0       # -> dist/ archive + checksum + matching Unraid template
```

Copy the archive and generated template to the host, `docker load -i` the archive, then
install the template as a user template and create the container from the Unraid Docker UI. The template already carries the port, `/data` volume, health
web UI, `--shm-size=1g`, and the common environment variables; fill in the Telegram
credentials and apply. Full steps: [`runbook.md`](runbook.md) §2.3.

---

## Configuration

Every setting is environment-driven and validated at startup; a bad value fails fast
with the property name in the message. `.env.example` mirrors the common keys;
`source/src/main/resources/application.yml` carries the complete set and defaults, and
[`SPEC.md`](SPEC.md) §3 lists the deployment values.

### Required for a real delivery

| Variable | Meaning |
|---|---|
| `TELEGRAM_BOT_TOKEN` | Bot API token from `@BotFather` |
| `TELEGRAM_CHAT_ID` | Target chat id (a private chat by default) |

A dry run (`DIGEST_DRY_RUN=true`) does not require either value.

### Schedule and content

| Variable | Default | Meaning |
|---|---|---|
| `DIGEST_CRON` | `0 0 7 * * MON` | Spring cron expression |
| `DIGEST_ZONE` | `Europe/Zurich` | Zone for the window, day grouping, and cron |
| `DIGEST_WINDOW_DAYS` | `14` | Fetch/selection window in local days |
| `DIGEST_RUN_ON_STARTUP` | `false` | Run once when the context is ready |
| `DIGEST_DRY_RUN` | `false` | Render and log instead of sending |
| `DIGEST_SEND_FAILURE_NOTICE` | `false` | Send a short notice when every source fails |
| `DIGEST_SHOW_MERGED_COUNT` | `true` | Append the dedup count footer line |
| `TELEGRAM_PART_LIMIT` | `4000` | Maximum characters per Telegram message part |

### Sources

| Variable | Default | Meaning |
|---|---|---|
| `SOURCE_INSITU_ENABLED` | `true` | Enable `insitu.live` |
| `SOURCE_BKA_ENABLED` | `true` | Enable `bka.ch` |
| `SOURCE_EVENTFROG_ENABLED` | `true` | Enable `eventfrog.ch` |
| `DEDUP_TITLE_THRESHOLD` | `0.90` | Jaro-Winkler title score for a fuzzy merge |
| `DEDUP_VENUE_THRESHOLD` | `0.85` | Jaro-Winkler venue score when both sides have one |
| `DEDUP_DATE_WINDOW_DAYS` | `1` | Maximum day difference between first occurrences |

### Network and browser

| Variable | Default | Meaning |
|---|---|---|
| `DIGEST_HTTP_TIMEOUT` | `10s` | Outbound API/Telegram timeout |
| `DIGEST_RATE_LIMIT_PER_SECOND` | `1` | Per-host request rate for scraped sources |
| `DIGEST_MAX_REQUESTS_PER_RUN` | `60` | Per-source request budget |
| `DIGEST_BROWSER_HEADLESS` | `true` | Headless Chromium |
| `DIGEST_BROWSER_WIDTH` / `DIGEST_BROWSER_HEIGHT` | `1920` / `1080` | Viewport size |
| `DIGEST_BROWSER_USER_AGENT` | `fribourg-events-digest/1.0` | Descriptive UA, no contact details |
| `DIGEST_BROWSER_PAGE_LOAD_TIMEOUT` | `30s` | Navigation timeout |
| `DIGEST_BROWSER_READY_STATE_TIMEOUT` | `30s` | `document.readyState` timeout |

### Storage and container

| Variable | Default | Meaning |
|---|---|---|
| `DB_PATH` | `../data/digest.db` (`/data/digest.db` in Compose) | SQLite file |
| `DIGEST_HTTP_PORT` | `8080` | Compose host port for Actuator |
| `PUID` / `PGID` | `1000` / `1000` | Runtime user that owns the mounted volume |
| `TZ` | `Europe/Zurich` | Container timezone |

---

## Commands

Run from `source/` unless stated otherwise.

| Command | Purpose |
|---|---|
| `./gradlew spotlessApply build` | Required before every commit: format, tests, coverage, jar |
| `./gradlew test` | Unit and integration tests (offline, `live` excluded) |
| `./gradlew bootRun` | Run locally with `application.yml` defaults |
| `./gradlew bootRun --args='--digest.dry-run=true --digest.run-on-startup=true'` | Local dry run |
| `./gradlew jacocoTestReport` | HTML coverage under `build/reports/jacoco/test/html` |
| `docker compose up -d --build` | Build and start the service (repo root) |
| `./docker/export-image.sh 1.0.0` | Build and export an image archive for offline Unraid import (`dist/`) |
| `docker compose logs -f digest` | Follow run logs |
| `docker compose restart digest` | Restart with the same environment (startup run fires if enabled) |
| `docker compose up -d` | Apply `.env` changes; recreates the container when the environment differs |

### Live checks

Live tests are tagged `live` and are excluded from `build`. `-PincludeTags=live` selects
that tag; narrow to one class with `--tests`:

```bash
cd source

# Source adapters (each is a manual smoke; keep the 1 req/s budget)
./gradlew test -PincludeTags=live --tests 'ch.fribourgevents.source.insitu.InsituLiveTest'
./gradlew test -PincludeTags=live --tests 'ch.fribourgevents.source.bka.BkaLiveTest'
./gradlew test -PincludeTags=live --tests 'ch.fribourgevents.source.eventfrog.EventfrogLiveTest'
./gradlew test -PincludeTags=live --tests 'ch.fribourgevents.source.browser.BrowserLiveTest'

# Telegram delivery (reads credentials from the environment only)
TELEGRAM_BOT_TOKEN=... TELEGRAM_CHAT_ID=... \
  ./gradlew test -PincludeTags=live --tests 'ch.fribourgevents.notify.telegram.TelegramLiveTest'
```

The adapters log their event and page counts, which is the evidence for the source-count
success criteria in [`SPEC.md`](SPEC.md) §8. Record the numbers in
[`sources.md`](sources.md) when they change materially.

---

## First run checklist

1. `source/`: `./gradlew spotlessApply build` is green.
2. `.env` has `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID`, and `DIGEST_RUN_ON_STARTUP=true`.
3. `mkdir -p data` (owned by `PUID:PGID`, or let the entrypoint fix it).
4. `docker compose up -d --build` (or apply the Unraid template after importing the
   exported image); the container shows `healthy`.
5. Logs show `startup_run_started` and `digest_run_finished ... status=SUCCESS` (or
   `PARTIAL` naming the failed source).
6. Telegram received one or more messages, each part numbered `(n/m)`.
7. `./data/digest.db` exists; row counts grow on the next run and no duplicates appear.
8. Restart the container and confirm the event rows persist.

If a step fails, start with [`runbook.md`](runbook.md#troubleshooting).

---

## Repository layout

```
docs/                      all Markdown (spec, plan, tasks, operator docs)
  SPEC.md                  initiative spec, module map, success criteria
  architecture.md          module graph, data model, run flow, decisions
  runbook.md               Unraid deployment and operations
  sources.md               per-source selectors, limits, quirks
docker/                    Dockerfile, entrypoint, healthcheck
  export-image.sh          build + docker save to dist/ for offline deployment
  unraid/                  Unraid Docker UI application template
docker-compose.yml         single-service deployment
dist/                      exported image archives (gitignored)
data/                      SQLite volume (gitignored)
source/                    Gradle root
  src/main/java/ch/fribourgevents/
    domain/                pure model, validation, normalization, dedup key
    store/                 Flyway schema, JPA entities, upsert, window query
    dedup/                 cross-source matcher and resolver
    digest/                window selection, grouping, plain-text rendering
    notify/                sender SPI, chunker; notify.telegram implementation
    source/                adapter SPI + insitu, bka, eventfrog, browser layer
    pipeline/              fetch → store → select → render → send orchestration
    runtime/               config, scheduler, startup runner, Spring wiring
  src/test/java/...        unit, fixture, SQLite integration, live smokes
```

A root `README.md` is intentionally absent: the project convention is that every
Markdown file lives under `docs/`.
