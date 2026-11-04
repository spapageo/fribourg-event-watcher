# Task List: Fribourg & Bern Event Digest

Task list target: this file (project convention — all Markdown lives in `docs/`).
Plan: `docs/plan.md` · Spec: `docs/SPEC.md` · Module specs: `docs/SPEC-<module-id>.md`.

Conventions used below:

- All Gradle commands run from `source/`.
- `BASE` = `source/src/main/java/ch/fribourgevents`
- `TEST` = `source/src/test/java/ch/fribourgevents`
- Every task ends with `./gradlew spotlessApply build`.
- No task may touch the network during `build`; live checks use `-PincludeTags=live`.

---

## Phase 1 — Foundation (`domain`, `store`)

### Task 1: Scaffold Gradle project, toolchain, and quality gates

**Description:** Create the Gradle project under `source/` with Groovy DSL, the Java 25
toolchain, Spring Boot 4.1.x dependency management, a version catalog, Spotless,
JaCoCo, JUnit Platform, and the test-tag wiring. Add repo hygiene files and initialize
git so checkpoints can be committed.

**Acceptance criteria:**
- [x] `source/settings.gradle` sets `rootProject.name = 'fribourg-events-digest'`.
- [x] `source/build.gradle` applies Java, Spring Boot, Spotless (google-java-format),
      JaCoCo; toolchain = Java 25; JUnit 5; `live` tag excluded from `test` unless
      `-PincludeTags=live`.
- [x] `source/gradle/libs.versions.toml` pins Spring Boot 4.1.2, Hibernate 7.4.13,
      sqlite-jdbc, Selenium, Jsoup, WireMock, ArchUnit.
- [x] `./gradlew build` succeeds with only the bootstrap `Application` class.
- [x] `.gitignore` excludes `.gradle/`, `build/`, `data/`, `.env`, `*.db*`.
- [x] `.env.example` matches `docs/SPEC.md` §3; `git init` + initial commit of `docs/`.

**Verification:**
- [x] `cd source && ./gradlew build`
- [x] `./gradlew dependencies --configuration runtimeClasspath` resolves
- [x] `git log --oneline` shows the docs commit

**Dependencies:** None · **Size:** M (5 files + wrapper)

**Files likely touched:**
- `source/settings.gradle`
- `source/build.gradle`
- `source/gradle/libs.versions.toml`
- `.gitignore`
- `.env.example`

---

### Task 2: Domain value types and invariants

**Description:** Implement `FetchWindow`, `SourceId`, and `EventStatus` as immutable
domain types with compact-constructor validation and no external dependencies.

**Acceptance criteria:**
- [x] `FetchWindow` rejects null components and `from >= to`; `fromDate()` uses the
      window zone.
- [x] `SourceId` has `INSITU("insitu")`, `BKA("bka")`, `EVENTFROG("eventfrog")` and
      parses from its wire name (unknown → `Optional.empty()` or exception, documented).
- [x] `EventStatus` covers `CONFIRMED`, `TENTATIVE`, `CANCELLED`, `UNKNOWN` and maps
      case-insensitively from upstream strings.
- [x] Unit tests cover every rejection path and mapping.

**Verification:**
- [x] `./gradlew test --tests 'ch.fribourgevents.domain.*'`
- [x] `./gradlew build`

**Dependencies:** Task 1 · **Size:** S

**Files likely touched:**
- `BASE/domain/FetchWindow.java`
- `BASE/domain/SourceId.java`
- `BASE/domain/EventStatus.java`
- `TEST/domain/FetchWindowTest.java`
- `TEST/domain/SourceIdTest.java`

---

### Task 3: Event and occurrence model

**Description:** Add the canonical input model returned by every adapter:
`EventOccurrence` and `SourceEvent`, with defensive copies and validation.

**Acceptance criteria:**
- [x] `EventOccurrence(Instant start, Instant end, boolean allDay)` rejects null start
      and `end < start`; `localDate(ZoneId)` returns the zone-local date.
- [x] `SourceEvent` requires non-blank `sourceEventId` and `title`, a non-null source,
      and at least one occurrence; it copies `categories`/`occurrences` into unmodifiable
      lists/sets.
- [x] Optional fields (`description`, `venueName`, `addressLine`, `city`, `canton`,
      coordinates, url, price) are explicitly nullable and documented; `country`
      defaults to `CH`, `status` to `UNKNOWN`.
- [x] Tests cover blank title, empty occurrences, `end < start`, defensive copies, and
      nullable-field handling.

**Verification:**
- [x] `./gradlew test --tests 'ch.fribourgevents.domain.*'`
- [x] `./gradlew build`

**Dependencies:** Task 2 · **Size:** S

**Files likely touched:**
- `BASE/domain/EventOccurrence.java`
- `BASE/domain/SourceEvent.java`
- `TEST/domain/EventOccurrenceTest.java`
- `TEST/domain/SourceEventTest.java`

---

### Task 4: Normalization and dedup key

**Description:** Implement deterministic text normalization and the dedup key used by
the store and matcher.

**Acceptance criteria:**
- [x] `Normalizer.title/venue/city` apply: Unicode NFD, strip combining marks,
      `ß → ss`, `Locale.ROOT` lowercase, non-alphanumeric → space, collapse whitespace,
      trim.
- [x] Normalization is idempotent and stable across JVM runs (no locale/random input).
- [x] `DedupKey.of(SourceEvent, ZoneId)` hashes
      `normalizedTitle|normalizedVenue|normalizedCity|localDateOfFirstOccurrence` with
      SHA-256 and returns 64 lowercase hex chars.
- [x] Tests: `"Cinéma – Zéro!"` → `"cinema zero"`, `"L'ÉTÉ  à Fribourg"` →
      `"l ete a fribourg"`, `"Straße"` → `"strasse"`; accent/case variants share a key;
      different dates do not.

**Verification:**
- [x] `./gradlew test --tests 'ch.fribourgevents.domain.NormalizerTest' --tests '*DedupKeyTest'`
- [x] `./gradlew build`

**Dependencies:** Task 3 · **Size:** S

**Files likely touched:**
- `BASE/domain/Normalizer.java`
- `BASE/domain/DedupKey.java`
- `TEST/domain/NormalizerTest.java`
- `TEST/domain/DedupKeyTest.java`

---

### Task 5: Flyway V1 schema and JPA entities

**Description:** Create the SQLite schema and the three core JPA entities with mappings
that satisfy `ddl-auto=validate`.

**Acceptance criteria:**
- [x] `V1__init.sql` creates only the canonical data tables `event`,
      `event_source_ref`, `event_occurrence` with the constraints and indexes from
      `docs/SPEC.md` §4; no execution-history tables are created.
- [x] Timestamps stored as ISO-8601 UTC text; enums as names; `local_date` as
      `YYYY-MM-DD`; FKs cascade where ownership implies it.
- [x] Entities map `event`, `event_source_ref`, `event_occurrence` and validate against
      a migrated temp DB.
- [x] Flyway runs automatically on startup and in tests.

**Verification:**
- [x] `./gradlew test --tests 'ch.fribourgevents.store.SchemaValidationTest'`
- [x] store tests create a real temp SQLite file via Flyway and assert `sqlite_master` contents
- [x] `./gradlew build`

**Dependencies:** Task 4 · **Size:** M

**Files likely touched:**
- `source/src/main/resources/db/migration/V1__init.sql`
- `source/src/main/resources/application.yml`
- `BASE/store/EventEntity.java`
- `BASE/store/EventSourceRefEntity.java`
- `BASE/store/EventOccurrenceEntity.java`
- `BASE/store/InstantText.java`
- `BASE/store/InstantTextConverter.java`
- `BASE/store/LocalDateTextConverter.java`
- `BASE/store/StoreConfiguration.java`
- `TEST/store/SchemaValidationTest.java`

---

### Task 6: Store upsert service with idempotency tests

**Description:** Add repositories and an `EventStore` that upserts canonical events,
source refs, and occurrences idempotently, and queries occurrences overlapping a window.

**Acceptance criteria:**
- [x] Upsert keyed by `(source, source_event_id)`; repeated identical input creates no
      new rows and only refreshes `last_seen_at`.
- [x] Changed payload updates mutable fields and `payload_hash`.
- [x] Occurrences are added/removed to match the payload without violating
      `unique(event_id, start_utc)`; a source only removes occurrences when it is the
      sole source ref, so merged events keep the other source’s showings.
- [x] `findOccurrencesOverlapping(from, to)` returns occurrences with
      `start_utc < to AND (start_utc >= from OR (end_utc IS NOT NULL AND end_utc >= from))`,
      ordered by start; an end-less occurrence is treated as instantaneous so past
      all-day markers do not reappear.
- [x] Integration tests use a temp SQLite file + Flyway (no mocks for the DB).

**Verification:**
- [x] `./gradlew test --tests 'ch.fribourgevents.store.*'`
- [x] `./gradlew build`

**Dependencies:** Task 5 · **Size:** M

**Files likely touched:**
- `BASE/store/EventRepository.java`
- `BASE/store/EventSourceRefRepository.java`
- `BASE/store/EventOccurrenceRepository.java`
- `BASE/store/EventStore.java`
- `BASE/store/PayloadHash.java`
- `BASE/store/UpsertResult.java`
- `TEST/store/EventStoreTest.java`
- `TEST/store/StoreTestApplication.java`
- `TEST/store/SqliteTestDatabase.java`

---

## Checkpoint A — Foundation

- [x] `cd source && ./gradlew build` green, no network access required
- [x] Schema validates from an empty DB and recreates after deletion
- [x] Running the same fixture twice leaves row counts unchanged
- [ ] Review with human before Phase 2

---

## Phase 2 — First vertical slice (`source-insitu` → `digest` → Telegram)

### Task 7: insitu.live API client and adapter

**Description:** Implement `EventSource` for `insitu.live` using `RestClient`, flattening
the month-grouped JSON-LD response into `SourceEvent`s.

**Acceptance criteria:**
- [x] Pages through `/api/events?limit=100&page=N` until `view.last`/last page; hard cap
      60 requests; fails with `SourceFetchException` on non-2xx or malformed top level.
- [x] Maps id/slug, name, status, place (name, address, zip, city, lat/lon), dates, and
      source URL into `SourceEvent`.
- [x] A malformed item is skipped and logged; it does not abort the page or the source.
- [x] Filters out occurrences wholly outside the requested window.
- [x] WireMock tests use a saved fixture with two pages and one malformed item.

**Verification:**
- [x] `./gradlew test --tests 'ch.fribourgevents.source.insitu.*'`
- [x] `./gradlew test -PincludeTags=live -PliveSource=insitu` (manual, network)
- [x] `./gradlew build`

**Dependencies:** Task 6 · **Size:** M

**Files likely touched:**
- `BASE/source/EventSource.java`
- `BASE/source/SourceFetchException.java`
- `BASE/source/insitu/InsituClient.java`
- `BASE/source/insitu/InsituEventSource.java`
- `TEST/source/insitu/InsituEventSourceTest.java`
- `TEST/resources/fixtures/insitu/events-page-1.json`, `events-page-2.json`

**Notes (implementation findings, 2026-11-03):** rows are open days, not events (1310 rows =
886 events); the run range is mapped to one occurrence so the payload is stable while an
event runs. Details and quirks: `docs/sources.md`.

---

### Task 8: Digest window selection, grouping, and text rendering

**Description:** Select occurrences in the window, group chronologically by local day,
and render a plain-text digest model that any channel can format.

**Acceptance criteria:**
- [x] `DigestService.select(window)` returns events sorted by local start time then
      title; multi-occurrence events appear once per occurrence.
- [x] Output groups by `Europe/Zurich` local date with weekday/date headers.
- [x] Each item includes title, time (or `all day`), venue, city, and source URL.
- [x] Item cap 150 with a "more events" footer; empty window renders a single
      "no events" message.
- [x] Rendering is deterministic and free of HTML (plain text only).

**Verification:**
- [x] `./gradlew test --tests 'ch.fribourgevents.digest.*'`
- [x] `./gradlew build`

**Dependencies:** Task 6 · **Size:** M

**Files likely touched:**
- `BASE/digest/DigestService.java`
- `BASE/digest/DigestMessage.java`
- `BASE/digest/DigestTextRenderer.java`
- `TEST/digest/DigestServiceTest.java`
- `TEST/digest/DigestTextRendererTest.java`

**Notes (implementation, 2026-11-03):** occurrences that started before the window are
grouped under the window's first day and labelled `ongoing`, so a running exhibition
never produces a day header outside the window. `DigestServiceTest` runs against a real
temp SQLite database (the selection reads through `EventStore`), so
`SqliteTestDatabase` moved to test-wide visibility.

---

### Task 9: Notification SPI and message chunker

**Description:** Define the channel-agnostic sender contract and split rendered
messages into transport-safe parts without breaking items.

**Acceptance criteria:**
- [x] `NotificationSender.send(DigestMessage)` returns `DeliveryResult`
      (channel, parts, external ids, status) and declares a checked failure type.
- [x] `MessageChunker` splits at item/day boundaries under a configurable limit
      (default 4000 chars), numbers parts `(n/m)`, and never splits an item line.
- [x] A single over-long item is truncated with an ellipsis and still fits.
- [x] Unit tests cover 0/1/many parts, exact-boundary input, and a giant item.

**Verification:**
- [x] `./gradlew test --tests 'ch.fribourgevents.notify.*'`
- [x] `./gradlew build`

**Dependencies:** Task 8 · **Size:** S

**Files likely touched:**
- `BASE/notify/NotificationSender.java`
- `BASE/notify/DeliveryResult.java`
- `BASE/notify/MessageChunker.java`
- `TEST/notify/MessageChunkerTest.java`

**Notes (implementation, 2026-11-03):** `NotificationException` joins the SPI as the declared
checked failure (a deliberate second exception beside `SourceFetchException`, per this task's
acceptance criteria); failures that reached nothing throw, `DeliveryResult` covers
`DELIVERED` and `PARTIAL`. To make chunking unambiguous the renderer now separates events
of the same day with a blank line, so every event is a paragraph and split points are always
event/day boundaries. The limit applies to a final part *including* its `(n/m)` suffix, and
the chunker re-packs when the suffix grows a digit.

---

### Task 10: Telegram sender

**Description:** Implement `NotificationSender` for the Telegram Bot API with HTML
escaping, chunked sending, and retry policy.

**Acceptance criteria:**
- [x] Posts `sendMessage` with `chat_id`, `parse_mode=HTML`, `disable_web_page_preview`.
- [x] All upstream text is HTML-escaped; only adapter-generated tags are emitted.
- [x] 429 honors `retry_after` (bounded); 5xx retried twice with backoff; 4xx fails
      fast with a redacted error (no token in message/log).
- [x] WireMock tests cover success, 429, 5xx-then-success, 400, and multi-part sending.

**Verification:**
- [x] `./gradlew test --tests 'ch.fribourgevents.notify.telegram.*'`
- [ ] `./gradlew test -PincludeTags=live -PliveSource=telegram` (manual, real chat)
- [x] `./gradlew build`

**Dependencies:** Task 9 · **Size:** M

**Files likely touched:**
- `BASE/notify/telegram/TelegramProperties.java`
- `BASE/notify/telegram/TelegramClient.java`
- `BASE/notify/telegram/TelegramNotificationSender.java`
- `TEST/notify/telegram/TelegramNotificationSenderTest.java`

**Notes (implementation, 2026-11-03):** the sender renders its own Telegram HTML
(`TelegramHtmlRenderer`) because `notify` depends only on the digest *message* type; the
layout mirrors `DigestTextRenderer` field-for-field so the two cannot drift visually
without a test failing. Retries are bounded at two per failure kind, `retry_after` is
capped at 30 s, and the client takes an injectable `Sleeper` so no test sleeps. API error
descriptions are redacted and `TelegramProperties.toString()` prints `***`, keeping tokens
out of logs. `TelegramLiveTest` (tagged `live`) is the manual real-chat smoke.

---

### Task 11: Pipeline orchestration, in-memory run summary, partial failures

**Description:** Wire fetch → upsert → select → render → send into one pipeline that
returns an in-memory run summary, emits structured logs, and isolates per-source
failures. Execution history is not written to SQLite.

**Acceptance criteria:**
- [x] Returns a `PipelineResult` with run status, window, selected occurrence ids,
      per-source outcomes, and delivery result; logs one `digest_run_started`,
      one `source_sync` per source, one `notification_delivery`, and one
      `digest_run_finished` line. No `digest_run`, `digest_run_item`,
      `notification_delivery`, or `source_sync` rows are written.
- [x] Runs enabled sources independently; one throwing → `PARTIAL`, others still
      delivered, failed sources named in the footer.
- [x] All sources failing → run `FAILED`, no misleading empty digest (send a short
      failure notice only if configured).
- [x] `dry-run` renders and logs without sending; `run-on-startup` invokes the pipeline.
- [x] Source outcomes carry timestamps, page/event counts, status, and error in memory
      and logs only.
- [x] Integration test uses fake sources + fake notifier against a real temp SQLite DB
      and asserts that only event data tables change.

**Verification:**
- [x] `./gradlew test --tests 'ch.fribourgevents.pipeline.*'`
- [x] `./gradlew build`

**Dependencies:** Tasks 7, 8, 9, 10 · **Size:** M

**Files likely touched:**
- `BASE/pipeline/DigestPipeline.java`
- `BASE/pipeline/PipelineResult.java`
- `BASE/pipeline/SourceSyncResult.java`
- `TEST/pipeline/DigestPipelineTest.java`
- `TEST/pipeline/FakeEventSource.java`
- `TEST/pipeline/FakeNotificationSender.java`

**Notes (implementation, 2026-11-03):** `dry-run` is done (`PipelineOptions.dryRun` renders
and logs, emits a `dry-run`/`SKIPPED` delivery line); `run-on-startup` was closed by Task 12
(`StartupRunner` + `StartupRunIntegrationTest`). Two contract changes fell out of the `source_sync` log: `EventSource`
now returns `SourceFetch(events, pagesFetched)` (the insitu client exposes its page count), and
`DigestMessage` gained `notes` so the pipeline can add a failed-source footer without breaking
the notify/digest boundary; a notes-only message renders as the failure notice instead of a
misleading "no events" line. `PipelineOptions.sendFailureNotice` is the "only if configured"
switch. The integration test uses a `@DataJpaTest` slice plus a real temp SQLite file (same
setup as `DigestServiceTest`) instead of booting the whole application.

---

## Checkpoint B — Working single-source product

- [x] `./gradlew bootRun --args='--digest.dry-run=true --digest.run-on-startup=true'`
      logs a rendered insitu digest and persists events
- [ ] Dry-run off delivers a real Telegram message
- [x] Forced source failure yields `PARTIAL` + footer, not a crash (covered by
      `DigestPipelineTest`; not re-run against a live source)
- [ ] Review with human before Phase 3

---

## Phase 3 — Runtime and deployment

### Task 12: Scheduler, startup runner, config properties, Actuator

**Description:** Turn the pipeline into the runtime service: validated configuration,
cron scheduling, one startup run, and health endpoints.

**Acceptance criteria:**
- [x] `@ConfigurationProperties` binds cron, zone, window days, source toggles, DB path,
      Telegram settings, rate limits; invalid values fail startup with a clear message.
- [x] `@Scheduled(cron = "${digest.cron}", zone = "${digest.zone}")` uses defaults
      `0 0 7 * * MON` / `Europe/Zurich`.
- [x] Startup runner executes once when enabled and cannot overlap a scheduled run.
- [x] Actuator exposes only `health` and `info`; no inbound digest trigger endpoint.
- [x] Tests cover property binding, invalid config, and startup-run-once semantics.

**Verification:**
- [x] `./gradlew test --tests 'ch.fribourgevents.runtime.*'`
- [x] `./gradlew bootRun --args='--digest.dry-run=true --digest.run-on-startup=true'`
- [x] `curl localhost:8080/actuator/health`
- [x] `./gradlew build`

**Dependencies:** Task 11 · **Size:** M

**Files likely touched:**
- `BASE/runtime/Application.java`
- `BASE/runtime/DigestProperties.java`
- `BASE/runtime/DigestScheduler.java`
- `BASE/runtime/StartupRunner.java`
- `source/src/main/resources/application.yml`

**Notes (implementation, 2026-11-03):** `RuntimeConfiguration` (extra file) wires the
`RestClient`, Telegram sender (a refusing stub in dry-run so the app boots without
credentials), the insitu source, and the filtered `DigestPipeline`. `DigestProperties`
validates every value in its compact constructor — Spring passes `null`/`0` for absent
properties, so default-able fields are boxed and normalised before checks. `zone` stays a
`String` in the record (the `@Scheduled` annotation and `StoreConfiguration` need the raw
value). `DigestScheduler.runOrSkip` owns a `ReentrantLock`, which both prevents overlap and
keeps the scheduler thread alive if a run throws. `bootRun` verification was a real run:
44 pages / 179 insitu events in the 14-day window, 150 selected (cap), dry-run digest
logged, events persisted (`event=174`, `event_source_ref=179`, `event_occurrence=179`),
only canonical tables present. Actuator returned `200 {"status":"UP"}` for health and 404
for `/actuator/env`.

---

### Task 13: Docker image, compose, healthcheck, volume

**Description:** Package the service for Unraid as a single container with Chromium
available for Selenium and SQLite persisted on a bind mount.

**Acceptance criteria:**
- [x] Multi-stage Dockerfile builds with the committed wrapper (Java 25 + Gradle), then
      runs on `eclipse-temurin:25-jre` with Chromium + chromedriver.
- [x] Container runs non-root with configurable UID/GID; `--shm-size` documented.
- [x] Compose maps `./data:/data`, loads `.env`, sets `DB_PATH=/data/digest.db`,
      healthcheck hits `/actuator/health`, restart policy `unless-stopped`.
- [x] Image contains no build cache, no `.env`, no token.
- [ ] Container starts healthy within 60 s; DB survives `docker compose restart`.

**Verification:**
- [ ] `docker build -f docker/Dockerfile -t fribourg-events-digest:dev .`
- [ ] `docker compose up -d --build && docker compose ps`
- [ ] `docker compose exec digest sh -c 'chromium --version && chromedriver --version'`
- [ ] `docker compose restart digest` then confirm rows persist

**Dependencies:** Task 12 · **Size:** M

**Files likely touched:**
- `docker/Dockerfile`
- `docker-compose.yml`
- `.dockerignore`
- `docker/healthcheck.sh`

**Notes (implementation, 2026-11-03 — verification blocked):** the Docker daemon is not
reachable in this environment (`permission denied` on `/var/run/docker.sock`, no compose
plugin), so every `docker build/compose/restart` step above still has to be run by the
operator; the JVM build is green and the compose YAML parses. Layout added:
`docker/entrypoint.sh` (creates PUID:PGID, chowns `/data`, `gosu`-drops root, execs Java)
in addition to the four listed files. The runtime stage is `debian:bookworm-slim` with the
official `eclipse-temurin:25-jre` tree copied in: Temurin publishes no Debian variant, and
Ubuntu Noble's `chromium` is a snap stub, so this is the only way to get real Debian
`chromium` + `chromium-driver` with a Java 25 JRE (the build stage still uses
`eclipse-temurin:25-jdk` and the committed wrapper). Build context is filtered by
`.dockerignore` (no `.env`, `data/`, `**/build`, `**/.gradle`), the final stage only receives
the boot jar and the two scripts, and the build stage's caches stay in the discarded layer.
Compose sets `shm_size: 1gb` (Chromium needs it; documented in the file header), exposes
`${DIGEST_HTTP_PORT:-8080}`, and the healthcheck script polls `/actuator/health` with
`start_period: 20s` so a slow first crawl cannot trip it while Tomcat is already serving.
`PUID`/`PGID`/`DIGEST_HTTP_PORT` are in `.env.example` and `docs/SPEC.md` §3.

---

## Checkpoint C — Deployable

- [ ] Container healthy on the Unraid host with persisted DB
- [ ] One startup digest run visible in logs
- [ ] Cron/timezone override verified via env without rebuild
- [ ] Review with human before Phase 4

---

## Phase 4 — Bern sources

### Task 14: Browser layer (Selenium `PageLoader`, rate limiter)

**Description:** Provide a headless-Chrome page loader behind the `PageLoader` interface
used by HTML sources, with per-host rate limiting and clean shutdown.

**Acceptance criteria:**
- [x] `SeleniumPageLoader.load(URI)` returns `driver.getPageSource()` with a page-load
      timeout and explicit wait for `document.readyState == complete`.
- [x] Headless flags, window size, user agent `fribourg-events-digest/1.0`; driver
      always quit in `finally`.
- [x] `RateLimiter` enforces ≤1 request/second per host using an injectable clock/sleeper.
- [x] Tests use a fake `PageLoader`; no browser launches during `build`.
- [x] One manual live smoke (load `bka.ch/agenda`) passes.

**Verification:**
- [x] `./gradlew test --tests 'ch.fribourgevents.source.browser.*'`
- [x] `./gradlew test -PincludeTags=live -PliveSource=browser` (manual)
- [x] `./gradlew build`

**Dependencies:** Task 12 · **Size:** M

**Files likely touched:**
- `BASE/source/PageLoader.java`
- `BASE/source/browser/SeleniumPageLoader.java`
- `BASE/source/browser/RateLimiter.java`
- `BASE/source/browser/BrowserProperties.java`
- `TEST/source/browser/RateLimiterTest.java`

**Notes (implementation, 2026-11-04):** `PageLoader` is an `AutoCloseable` returning the page
source and throwing `SourceFetchException` (the adapter SPI's only checked failure), so sources
never see Selenium types. `SeleniumPageLoader` opens **one Chrome session per page**, configures the
page-load timeout and window, navigates, waits for `document.readyState == complete`, and quits in
`finally`; a failing `quit()` is logged, never allowed to mask the load result. That is the
"single browser session at a time" rule without pooled-driver lifecycle bugs. Tests inject a
`DriverFactory` (extra file beyond the plan) returning a mocked `WebDriver`, so quit-on-success,
quit-on-failure, the ready-state timeout message, and the Chrome flags are all covered without
launching a browser; the live smoke is the only real Chrome run. `RateLimiter` keys state by
lowercased host, uses an injectable monotonic nano clock + `Sleeper`, and reports interruption via
`InterruptedException` which the loader restores (`Thread.currentThread().interrupt()`) and wraps.
`BrowserProperties` (`digest.browser.*`) validates headless, window size, user agent, and both
timeouts; the new env tunables (`DIGEST_BROWSER_HEADLESS`, `DIGEST_BROWSER_PAGE_LOAD_TIMEOUT`,
`DIGEST_BROWSER_READY_STATE_TIMEOUT`) are in `.env.example` and `docs/SPEC.md` §3. `RuntimeConfiguration`
exposes one `RateLimiter` bean (shared by all HTML sources) and one `PageLoader` bean; the driver
is created lazily, so startup still boots without Chromium. Live smoke: real headless Chrome loaded
`https://bka.ch/agenda`, 110 937 chars, in ~6 s. Max-per-run (60) is unchanged and stays an adapter
concern in Tasks 15/16.

---

### Task 15: bka.ch adapter

**Description:** Read the Berner Kulturagenda's agenda JSON (the endpoint its own React
agenda calls) and map it into `SourceEvent`s. Operator-approved change from the original
HTML/`/event/<slug>` plan: the rendered agenda has no times, descriptions, or addresses,
and one detail request per event cannot fit the 60-request budget (see the notes below).

**Acceptance criteria:**
- [x] Reads `GET /api/events?limit=100&page=N&date_from=YYYY-MM-DD&date_to=YYYY-MM-DD`
      for the fetch window; stops on the first empty `data` page; hard cap 60 requests;
      every request paced through the shared `RateLimiter`; non-2xx, empty body, or a
      malformed top level fail with `SourceFetchException`.
- [x] Groups rows by `mainevent_id` (fallback `id`) so a run of showings is one event;
      merges, dedupes, and start-sorts occurrences.
- [x] Maps title, dates/times, venue and address (`place` or `place_proposed`),
      description, category, price, and canonical URL (`mainevent_slug` or `slug`).
- [x] Yields ≥100 upcoming Bern events against the current site (live smoke: 676 from
      15 pages); skips malformed rows without aborting the page or the source.
- [x] Fixtures: two agenda pages including a main/subevent group, one malformed row, and
      one outside-window row.
- [x] No bka code depends on Selenium; it is a `RestClient` client plus pure mapping.

**Verification:**
- [x] `./gradlew test --tests 'ch.fribourgevents.source.bka.*'`
- [x] `./gradlew test -PincludeTags=live -PliveSource=bka` (manual, logs count)
- [x] `./gradlew build`

**Dependencies:** Task 14 · **Size:** M

**Files likely touched:**
- `BASE/source/bka/BkaClient.java`
- `BASE/source/bka/BkaEventSource.java`
- `BASE/source/bka/BkaEvent.java`
- `TEST/source/bka/BkaEventSourceTest.java`
- `TEST/source/bka/BkaLiveTest.java`
- `TEST/resources/fixtures/bka/*.json`

**Notes (implementation, 2026-11-04):** the live site no longer matches the research note
"HTML only": `/agenda` is a React app whose cards show only title, venue, city, category,
and a date range, while the site's own frontend fetches `/api/events` with full times,
place address/city/coordinates, descriptions, categories, prices, and status. Scraping
each of the ~2000 agenda entries would need ~2000 detail requests against a 60-request
budget, so the operator chose the JSON endpoint (option 1). The client sends
`date_from`/`date_to` for the window (the API uses calendar-day overlap semantics, so the
adapter re-checks instant overlap locally), pages by 100 until an empty page, caps at 60,
and fails loudly at the cap. A live 14-day window needed 15 pages / ~1400 rows → 676
canonical events in ~32 s at 1 req/s. Rows are showings (`subevent` with `mainevent_id`),
so they are grouped into one event per production with every occurrence; a multi-day row
becomes one long occurrence (start at midnight → `allDay`), keeping `payload_hash` stable
while an exhibition runs. German labels win, descriptions are HTML→text (2000-char cap),
and `full`/`postponed` statuses degrade to `UNKNOWN` because the model has no sold-out
state. `RuntimeConfiguration` adds the `bkaEventSource` bean using the shared limiter;
`BkaLiveTest` is the manual smoke. `docs/SPEC.md` §2/§4/§8/§9 and `docs/sources.md` now
record the JSON endpoint, mapping, budget, and gaps.

---

### Task 16: eventfrog.ch adapter

**Description:** Scrape the Fribourg and Bern eventfrog city pages, using embedded JSON
where available and SSR HTML otherwise.

**Acceptance criteria:**
- [x] Fetches `/de/events/fribourg` and `/de/events/bern`, following pagination/load-more.
- [x] Extracts ≥50 events per city; parses embedded `top-localities` JSON when present,
      falls back to SSR list/detail parsing.
- [x] Maps title, start/end, venue, city, URL, categories, price; dedupes repeated links
      within a page.
- [x] Fixtures: one list page per city + one detail page + malformed item.
- [x] Parser is a pure function over page source.

**Verification:**
- [x] `./gradlew test --tests 'ch.fribourgevents.source.eventfrog.*'`
- [x] `./gradlew test -PincludeTags=live -PliveSource=eventfrog` (manual, logs count)
- [x] `./gradlew build`

**Dependencies:** Task 14 · **Size:** M

**Files likely touched:**
- `BASE/source/eventfrog/EventfrogEventSource.java`
- `BASE/source/eventfrog/EventfrogParser.java`
- `TEST/source/eventfrog/EventfrogEventSourceTest.java`
- `TEST/resources/fixtures/eventfrog/*.html`

**Notes (implementation, 2026-11-04):** the research assumption that the page embeds event data
in `top-localities` is wrong: that script is the city navigation dropdown. The adapter therefore
reads the SSR `a.event-list__events__tile` markup, follows the "Mehr Events anzeigen" fragments at
`/de/event-list/<city>.html?shop_recpage=N&perPage=48`, and parses `top-localities` for diagnostics
only. Detail pages carry a schema.org `Event` JSON-LD block (exact instants, address/coordinates,
description, categories, offers); group pages carry every showing in `subEvent[]`, so one request
richly maps a whole run. Because a city page can hold hundreds of events and the source budget is
60 requests, detail enrichment is bounded to the earliest in-window candidates (30 by default)
and the rest keep their list-derived occurrence; a failed detail page falls back to the tile
instead of failing the source. Tile labels omit the year, so dates resolve against the window start
as the next upcoming occurrence; `bis <month>` spans and `Hat bereits begonnen` are handled.
Live smoke: Fribourg 50 events in 2 requests, Bern ~90 events in 2 requests (one-year window,
detail budget 0); a normal 14-day run spends up to 30 detail requests on the nearest events.
`RuntimeConfiguration` adds the `eventfrogEventSource` bean over the shared Selenium `PageLoader`.
Fixtures are trimmed real list fragments plus real JSON-LD detail blocks, including a malformed
tile and a malformed detail block. Full endpoint/selector/limit notes are in `docs/sources.md`.

---

## Checkpoint D — Three sources

- [ ] One run ingests insitu + bka + eventfrog within the 60-request/1-rps budgets
- [ ] Counts meet success criteria 2–4 in `docs/SPEC.md`
- [ ] Each source verified with a manual live smoke; quirks noted in `docs/sources.md`
- [ ] Review with human before Phase 5

---

## Phase 5 — Dedup and polish

### Task 17: Cross-source dedup matcher

**Description:** Implement exact and fuzzy matching so the same event from two sources
maps to one canonical event.

**Acceptance criteria:**
- [ ] Exact match on `dedup_key` wins; otherwise candidates limited to same normalized
      city and local date ±1 day.
- [ ] Fuzzy score combines normalized title and venue similarity (Jaro-Winkler ≥ 0.90
      title, venue empty-or-≥0.85); thresholds configurable.
- [ ] Recurring series on different dates are never merged; same title at different
      venues is never merged.
- [ ] Matcher is pure (given candidates + candidate event → decision) and fixture-tested
      with real cross-source duplicates.

**Verification:**
- [ ] `./gradlew test --tests 'ch.fribourgevents.dedup.*'`
- [ ] `./gradlew build`

**Dependencies:** Tasks 15, 16 · **Size:** M

**Files likely touched:**
- `BASE/dedup/DedupMatcher.java`
- `BASE/dedup/DedupProperties.java`
- `BASE/dedup/MatchDecision.java`
- `TEST/dedup/DedupMatcherTest.java`

---

### Task 18: Pipeline dedup integration and run report

**Description:** Make upsert consult the matcher, attach new source refs to existing
canonical events, and add a run report/footer with source and dedup counts.

**Acceptance criteria:**
- [ ] Upsert finds a canonical match, attaches `(source, source_event_id)`, adds new
      occurrences, and keeps the richer field values (non-null/longer description).
- [ ] Digest shows the merged event once, with the preferred source URL.
- [ ] Run summary carries `events_seen`, `events_inserted`, `events_merged`, per-source status (memory/log only).
- [ ] Message footer lists partial/failed sources and merged counts (configurable).
- [ ] Integration test: same event from insitu + eventfrog fixture → one digest item,
      two `event_source_ref` rows.

**Verification:**
- [ ] `./gradlew test --tests 'ch.fribourgevents.pipeline.*' --tests 'ch.fribourgevents.store.*'`
- [ ] `./gradlew build`

**Dependencies:** Task 17 · **Size:** M

**Files likely touched:**
- `BASE/store/EventStore.java`
- `BASE/pipeline/DigestPipeline.java`
- `BASE/digest/DigestTextRenderer.java`
- `TEST/pipeline/DedupPipelineTest.java`

---

### Task 19: Documentation and final v1 verification

**Description:** Write the operator docs and verify every success criterion in the spec
against a real container run.

**Acceptance criteria:**
- [ ] `docs/README.md`: quickstart, env vars, commands, first run.
- [ ] `docs/runbook.md`: Unraid deployment, UID/GID, volume/backup, logs, manual
      re-run, troubleshooting (SQLite lock, Chromium crash, Telegram 429).
- [ ] `docs/sources.md`: selectors, pagination, rate limits, quirks per source.
- [ ] `docs/architecture.md`: module graph, data model, key decisions.
- [ ] All twelve success criteria in `docs/SPEC.md` §8 checked off with evidence.

**Verification:**
- [ ] `./gradlew clean build` from a fresh clone (offline for tests)
- [ ] `docker compose up -d --build` on Unraid; digest received in Telegram
- [ ] Manual comparison of digest items against all three source websites

**Dependencies:** Task 18 · **Size:** M

**Files likely touched:**
- `docs/README.md`
- `docs/runbook.md`
- `docs/sources.md`
- `docs/architecture.md`

---

## Checkpoint E — v1 complete

- [ ] All success criteria verified and recorded
- [ ] `docs/` complete and accurate
- [ ] Tag `v1.0.0` after human review
