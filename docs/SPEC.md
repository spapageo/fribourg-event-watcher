# Spec: Fribourg & Bern Event Digest

Status: **APPROVED** — 2026-02
Date: 2026-02
Inputs: `docs/research/event-sources.md`

A personal, scheduled Telegram digest of upcoming cultural events in the canton of
Fribourg and the city of Bern, built from three sources (`insitu.live`, `bka.ch`,
`eventfrog.ch`), stored in SQLite, and delivered by a protocol-agnostic notification
interface.

---

## 0. Capability Map (Phase 0 gate)

This initiative bundles several independently testable capabilities, so the module
boundaries below are reviewed **before** any module spec is written. Approval of this
map is a separate gate from approval of this document.

| Module id | Responsibility | Depends on |
|---|---|---|
| `domain` | Canonical `Event` / `EventOccurrence` model, source ids, normalization, dedup key, validation | — |
| `store` | SQLite schema + Flyway migrations, upsert semantics, source refs, canonical event queries | `domain` |
| `source-insitu` | Adapter for the open `insitu.live` JSON-LD API (canton FR) | `domain` |
| `source-bka` | Adapter for `bka.ch` HTML (Bern), Selenium fetch + Jsoup parse | `domain` |
| `source-eventfrog` | Adapter for `eventfrog.ch/de/events/{bern,fribourg}`, Selenium fetch + parse | `domain` |
| `digest` | Window selection, chronological grouping, channel-agnostic `DigestMessage` + rendering | `domain`, `store` |
| `notify` | `NotificationSender` SPI, message chunking, delivery result type | `digest` (message type only) |
| `notify-telegram` | Telegram Bot API implementation of `NotificationSender` | `notify` |
| `dedup` | Cross-source matching and canonical merge (fuzzy title + venue + date) | `domain`, `store` |
| `pipeline` | Orchestrates fetch → normalize → upsert → select → render → send; per-source isolation | all sources, `store`, `digest`, `notify` |
| `runtime` | Spring Boot wiring, configuration properties, cron scheduling, startup run, Actuator, Docker image | `pipeline`, `notify-telegram` |

**Dependency direction:** `domain` ← `store` ← sources ← `pipeline` ← `runtime`.
`digest` depends on `store`; `notify` depends only on `digest`'s message type. No cycles.

**Build order:**

```
domain → store → source-insitu → digest → notify → notify-telegram → pipeline → runtime
                                                                                  ↓
                                                              source-bka → source-eventfrog → dedup
```

`runtime` is reached at step 8 with a **working single-source product** (insitu → digest
→ Telegram). Bern sources and cross-source dedup follow as separately verifiable steps.
`store` ships dedup columns/tables from day one; only the matching logic lands last.

**Recursion:** after this map is approved, each module gets `docs/SPEC-<module-id>.md`
in dependency order, each covering the six areas scoped to that module. This document
remains the initiative-level spec and the index.

**Repository layout rule:** every Markdown file lives under `docs/`; all source code
lives under `source/` (the Gradle project root). Nothing else at the repo root except
the Docker/compose files, `.env.example`, `data/`, and `.gitignore`.

---

## 1. Objective

**What:** an unattended service that emails/chats a chronological list of upcoming
cultural events for canton Fribourg and city Bern.

**Who:** one operator (the project owner) running a single container on an Unraid
server. No accounts, no multi-tenant concerns, no public consumers.

**Why:** the source landscape is fragmented (Fribourg has an open API, Bern does not)
and checking four agendas manually each week is the actual pain.

**Non-goals for v1:** ticketing, reservations, a web UI, public API, user accounts,
recommendations/interest filtering (explicitly deferred), map view, full-text search,
redistribution of source data.

### User stories

| # | Story | Acceptance |
|---|---|---|
| U1 | Every Monday morning I receive a Telegram message with the next 14 days of events | Cron `0 0 7 * * MON` (Europe/Zurich) produces one delivery; default window = today..today+14d |
| U2 | The digest is chronological and grouped by day | Items sorted by local start time, day headers with weekday + date, no reordering |
| U3 | Each item tells me what, when, where, and links to the source | Every item has title, local start (and end if known), venue, city, canonical source URL |
| U4 | The same event from two sources appears once | Fixture test with the same event from insitu + eventfrog yields one digest item |
| U5 | A broken source does not silently produce an empty digest | Run marked `PARTIAL`; other sources still delivered; error logged and surfaced in the message footer |
| U6 | I can test the whole pipeline by restarting the container | `digest.run-on-startup=true` runs the full pipeline once at boot and logs the outcome |
| U7 | I can run it on Unraid without babysitting | Single container, SQLite in a mounted volume, healthcheck, restarts do not lose data |
| U8 | Later I can add interest-based filtering without rewriting ingestion | Source adapters and `digest` selection are separate modules; categories are stored per event |

---

## 2. Tech Stack

| Concern | Choice |
|---|---|
| Language / runtime | Java 25 (Gradle toolchain), Spring Boot 4.1.x (4.1.2 at scaffold time) |
| Build | Gradle (Groovy DSL), Spotless + google-java-format, JaCoCo |
| Persistence | SQLite via `org.xerial:sqlite-jdbc`, Spring Data JPA (Hibernate 7) + `hibernate-community-dialects` `SQLiteDialect`, Flyway migrations |
| HTTP clients | Spring `RestClient` (insitu API, Telegram Bot API) |
| Browser automation | Selenium 4 (`selenium-java`) with Selenium Manager, headless Chromium in the image; page source parsed with Jsoup selectors |
| JSON | Jackson (Boot-managed) |
| Scheduling | Spring `@Scheduled` with configurable cron + zone |
| Observability | SLF4J/Logback, Spring Boot Actuator (`health`, `info`) |
| Notification | Telegram Bot API `sendMessage`, `parse_mode=HTML` |
| Tests | JUnit 5, AssertJ, Mockito, WireMock, saved HTML/JSON fixtures |
| Container | Multi-stage Dockerfile: Gradle build → `eclipse-temurin:25-jre` + Debian `chromium` + `chromedriver` |

Notes / accepted trade-offs:

- **SQLite is not first-class in Hibernate.** Use Flyway for all schema changes;
  `ddl-auto=validate` in every profile. Hikari pool size 1, `journal_mode=WAL`,
  `busy_timeout=5000`.
- **Selenium for every HTML source** (operator decision) means a ~600 MB image and
  slower runs (~30–90 s per source). Accepted for uniformity; all parsing still happens
  against `driver.getPageSource()` so parser tests never need a browser.
- `insitu.live` is an open JSON-LD/Hydra API; it must **not** be driven through Selenium.
- Access to all three sources is assumed permitted (operator decision); only the rate
  limits and no-bypass rules in section 7 apply. No outreach or partnership agreement is
  treated as a prerequisite.
- No Lombok. Records, sealed interfaces, explicit code.

---

## 3. Commands

All Gradle commands run from `source/` (the Gradle project root).

```bash
cd source

# Build + all checks (formatting, unit tests, coverage verification)
./gradlew build

# Tests only
./gradlew test

# One test class / one tag
./gradlew test --tests 'ch.fribourgevents.digest.DigestSelectionTest'
./gradlew test -PexcludeTags=live

# Formatting
./gradlew spotlessApply
./gradlew spotlessCheck

# Run locally (reads ../.env, uses ../data/digest.db)
./gradlew bootRun
./gradlew bootRun --args='--digest.run-on-startup=true --digest.dry-run=true'

# Live smoke test (network, not part of CI)
./gradlew test -PincludeTags=live -PliveSource=insitu
```

```bash
# Container — run from the repository root
docker build -f docker/Dockerfile -t fribourg-events-digest:dev .
docker compose up -d --build
docker compose logs -f digest
docker compose down

# Inspect the database (host side, volume mounted at ./data)
sqlite3 data/digest.db '.tables'
sqlite3 data/digest.db 'select count(*) from event; select count(*) from event_occurrence;'
```

Required environment (`.env`, never committed; `.env.example` is committed):

```
TELEGRAM_BOT_TOKEN=...
TELEGRAM_CHAT_ID=...
DIGEST_CRON=0 0 7 * * MON
DIGEST_ZONE=Europe/Zurich
DIGEST_WINDOW_DAYS=14
DIGEST_RUN_ON_STARTUP=true
DIGEST_DRY_RUN=false
DIGEST_SEND_FAILURE_NOTICE=false
SOURCE_INSITU_ENABLED=true
SOURCE_BKA_ENABLED=true
SOURCE_EVENTFROG_ENABLED=true
DB_PATH=../data/digest.db   # local dev (from source/); container uses /data/digest.db

# Optional tuning (defaults shown)
TELEGRAM_API_BASE_URL=https://api.telegram.org
TELEGRAM_PART_LIMIT=4000
DIGEST_HTTP_TIMEOUT=10s
DIGEST_BROWSER_HEADLESS=true
DIGEST_BROWSER_PAGE_LOAD_TIMEOUT=30s
DIGEST_BROWSER_READY_STATE_TIMEOUT=30s
DIGEST_RATE_LIMIT_PER_SECOND=1
DIGEST_MAX_REQUESTS_PER_RUN=60
DIGEST_HTTP_PORT=8080   # compose host port
PUID=1000                # container runs non-root as PUID:PGID
PGID=1000
```

---

## 4. Project Structure

Single Gradle module with enforced package boundaries (ArchUnit test), not a
multi-module build — the modules are review boundaries, not deployment units.

`source/` is the Gradle project root and holds **all** source code, using the standard
Gradle layout: `src/main/java`, `src/main/resources`, `src/test/java`,
`src/test/resources`. Every Markdown file lives in `docs/`.

```
docs/                        → ALL Markdown
  SPEC.md                    → this document (initiative spec + module index)
  SPEC-<module-id>.md        → per-module specs, created after map approval
  README.md                  → operator quickstart
  plan.md                    → implementation plan (Phase 2)
  todo.md                    → task checklist (Phase 2)
  architecture.md            → module boundaries and design notes
  sources.md                 → per-source selectors, rate limits, quirks
  runbook.md                 → Unraid deployment, backup, troubleshooting
  research/event-sources.md  → source research (existing)

source/                      → Gradle project root; ALL source code
  build.gradle               → dependencies, toolchain, spotless, jacoco
  settings.gradle
  gradlew / gradlew.bat
  gradle/libs.versions.toml  → version catalog
  src/main/java/ch/fribourgevents/
    domain/                  → Event, EventOccurrence, SourceId, Normalizer, DedupKey,
                               FetchWindow, SourceEvent, domain exceptions
    store/                   → JPA entities, repositories, upsert service, migrations runner
    source/
      EventSource.java       → adapter SPI
      insitu/                → API client, response mapping
      bka/                   → page walker + HTML parser
      eventfrog/             → page walker + parser (embedded top-localities JSON + SSR list)
      browser/               → Selenium driver factory, rate limiter, page loader
    digest/                  → window selection, grouping, DigestMessage, text renderer
    notify/                  → NotificationSender SPI, chunker, DeliveryResult
    notify/telegram/         → Telegram client + sender
    dedup/                   → matcher, canonical merge policy
    pipeline/                → DigestPipeline, per-source sync isolation, in-memory run summary
    runtime/                 → Application, @ConfigurationProperties, scheduler, startup runner
  src/main/resources/
    application.yml
    db/migration/V1__init.sql
  src/test/java/ch/fribourgevents/...   → mirrors main packages
  src/test/resources/
    fixtures/insitu/*.json
    fixtures/bka/*.html
    fixtures/eventfrog/*.html

docker/Dockerfile            → multi-stage build (Gradle → JRE + Chromium)
docker-compose.yml           → single service, ./data volume, env_file
.env.example
data/                        → local SQLite (gitignored)
```

### Data model (v1)

```
event                (id, dedup_key UNIQUE, title, normalized_title, description,
                      venue_name, address_line, city, canton, country, lat, lon,
                      url, categories, price_info, status, created_at, updated_at)
event_source_ref     (id, event_id, source, source_event_id, source_url,
                      payload_hash, first_seen_at, last_seen_at,
                      UNIQUE(source, source_event_id))
event_occurrence     (id, event_id, start_utc, end_utc, all_day, local_date,
                      UNIQUE(event_id, start_utc))
```

All timestamps stored UTC; `local_date` derived in `Europe/Zurich` for grouping. One
canonical `event` can be referenced by multiple sources; occurrences belong to the
canonical event.

**Execution state is not persisted.** SQLite stores only canonical event data. A digest
run, its source-sync outcomes, and notification deliveries exist as an in-memory
`PipelineResult` plus structured log lines (`digest_run_started`, `source_sync`,
`digest_run_finished`, `notification_delivery`). There are no `digest_run`,
`digest_run_item`, `notification_delivery`, or `source_sync` tables.

Consequences, accepted for v1:
- No cross-restart run history, delivery receipts, or "last successful run" queries.
  Operators use container logs instead.
- Delivery is at-most-once per pipeline invocation. If the process dies after Telegram
  accepts a message but before the run completes, a later startup run can send again;
  there is no durable idempotency key.
- Failure isolation still works within a run: each source has its own status, and a
  `PARTIAL` run still delivers a digest with a footer.
- Reintroducing durable run/delivery tracking later requires a new Flyway migration and
  a deliberate retry/idempotency design, not a schema tweak.

**Execution log contract.** These structured lines replace the removed tables:

| Event | Required fields |
|---|---|
| `digest_run_started` | `run_id`, `window_from`, `window_to`, enabled sources |
| `source_sync` | `run_id`, `source`, `status`, `pages_fetched`, `events_seen`, `duration_ms`, `error` |
| `notification_delivery` | `run_id`, `channel`, `status`, `message_count`, `external_id`, `error` |
| `digest_run_finished` | `run_id`, `status` (`SUCCESS`/`PARTIAL`/`FAILED`), `events_selected`, `delivered` |

Logs are the operator-facing record; they are not queryable history and may rotate with
the container. That is accepted for v1.

---

## 5. Code Style

Constructor injection, records for data, sealed types for closed unions, no field
injection, no static mutable state, `Clock` injected for anything time-dependent.
Parsers are pure functions over `String` page sources so they are testable without a
browser.

```java
package ch.fribourgevents.source;

import ch.fribourgevents.domain.FetchWindow;
import ch.fribourgevents.domain.SourceEvent;
import ch.fribourgevents.domain.SourceId;
import java.util.List;

/** One upstream agenda. Implementations are stateless and must be safe to call repeatedly. */
public interface EventSource {

    SourceId id();

    /**
     * Returns every event intersecting {@code window}, plus the upstream page count
     * the pipeline logs on its {@code source_sync} line.
     */
    SourceFetch fetch(FetchWindow window) throws SourceFetchException;
}

/** {@code events} in upstream order; {@code pagesFetched} for the run log. */
public record SourceFetch(List<SourceEvent> events, int pagesFetched) {}
```

```java
public record FetchWindow(Instant from, Instant to, ZoneId zone) {
    public FetchWindow {
        requireNonNull(from, "from");
        requireNonNull(to, "to");
        requireNonNull(zone, "zone");
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("from must be before to");
        }
    }

    public LocalDate fromDate() {
        return from.atZone(zone).toLocalDate();
    }
}
```

Conventions:

- Packages: `ch.fribourgevents.<module>`; no cross-module reach-ins except downward.
- Names: `*Source` adapters, `*Service` orchestration, `*Repository` persistence,
  `*Properties` config, `*Exception` checked for source failures, `*Test` tests.
- Public API of a module = its package-level interfaces; everything else package-private.
- Nulls: avoid; `Optional` for finders, records validate in compact constructors.
- No checked exceptions across module boundaries except `SourceFetchException`.
- Text output: escape all upstream text before Telegram HTML; never concatenate raw HTML.

---

## 6. Testing Strategy

| Level | Scope | Tooling | Where |
|---|---|---|---|
| Unit | normalization, dedup key/matching, window selection, grouping, chunking, HTML escaping | JUnit 5 + AssertJ | `source/src/test/java/.../domain`, `.../digest`, `.../dedup` |
| Parser | insitu JSON mapping; bka/eventfrog HTML → `SourceEvent` | saved fixtures, no network | `source/src/test/resources/fixtures/**` |
| Client | insitu pagination, Telegram send/retry, rate limiter | WireMock / fake `RestClient` | `.../source/insitu`, `.../notify/telegram` |
| Integration | pipeline end-to-end with fake sources + fake notifier + real SQLite/Flyway | `@SpringBootTest` (no web), temp DB file | `.../pipeline` |
| Architecture | package dependency rules | ArchUnit | `.../ArchitectureTest` |
| Live smoke | real insitu/bka/eventfrog + real Telegram | JUnit tag `live`, excluded from `build` | manual only |

Rules:

- **No test may touch the network by default.** Live tests require
  `-PincludeTags=live` and are never part of `./gradlew build`.
- Selenium is not exercised in tests; adapters accept a `PageLoader` interface, faked in
  tests with fixture page sources. One manual live smoke per HTML source after changes.
- Every new source ships with at least: a happy-path fixture, a malformed-item fixture
  (skipped item, not whole-run failure), and a pagination fixture.
- Coverage gate: **≥80% line** for `domain`, `store`, `digest`, `dedup`, `notify`;
  adapters measured on parser/mapper classes (browser/network wrappers excluded).
- Tests are deterministic: fixed `Clock`, fixed zone, no `Thread.sleep`, no ordering
  assumptions on DB reads (explicit `ORDER BY` everywhere).
- Regression rule: a bug fix lands with a failing-then-passing test.

---

## 7. Boundaries

### Always

- Run `./gradlew spotlessApply build` before every commit; a red build blocks the commit.
- New source = new adapter + fixtures; never bend another adapter to fix one site.
- Rate-limit per host: **≤1 request/second**, **≤60 requests per source per run**,
  single browser session at a time, `robots.txt` checked when adding a source.
- Send a descriptive `User-Agent` without personal contact details:
  `fribourg-events-digest/1.0` (append the instance hostname if useful).
- Idempotent upserts keyed by `(source, source_event_id)`; repeated runs must not
  duplicate rows.
- Store UTC; render `Europe/Zurich`; all date arithmetic through injected `Clock`.
- Emit one structured log line per run and per source with counts, status, and errors;
  do not persist execution history in SQLite.
- Isolate failures: a source that throws is logged and marked `FAILED`; remaining
  sources still run and the digest is sent with a footer noting the failure.
- Escape all source-supplied text before Telegram HTML; truncate long descriptions.
- Read secrets only from environment; never log tokens, chat ids, or tokenized URLs.
- Keep `docs/research/` findings and `docs/sources.md` updated when an adapter changes.

### Ask first

- Adding a fourth source, or scraping a new host (check `robots.txt`, record limits).
- Schema changes after the first production run (new Flyway migration vs. reset).
- Changing dedup thresholds/rules, the default cron, digest window, or item cap.
- Adding runtime dependencies, bumping Spring Boot major, or changing the Java version.
- Exposing any inbound HTTP endpoint beyond Actuator health/info.
- Delivering to more than one chat/subscriber, or any public channel.
- Running a full live crawl of all three sources (rate/volume sanity check).

### Never

- Bypass paywalls, logins, CAPTCHAs, or bot protections.
- Run concurrent browser sessions or tight retry loops against a source.
- Commit secrets, `.env`, the SQLite file, or raw scraped dumps containing personal data.
- Republish or redistribute source content publicly; the digest is personal, and every
  item links back to the source. Respect `insitu.live`'s content signals and API terms.
- Delete, skip, or weaken failing tests to get green; no `@Disabled` without a linked issue.
- Use `ddl-auto: update` outside tests; no manual writes to the production database.
- Add a source without recording its selectors, rate limit, and quirks in `docs/sources.md`.

---

## 8. Success Criteria

v1 is done when all of the following hold:

1. From a clean clone, `./gradlew build` passes offline (no network, live tests excluded).
2. `insitu.live` adapter ingests ≥1000 upcoming canton-FR events into SQLite; city
   filter yields Fribourg, Bulle, Gruyères, Murten, Romont, Estavayer, etc.
3. `bka.ch` adapter yields ≥100 upcoming Bern events from `/agenda` + event pages.
4. `eventfrog.ch` adapter yields ≥50 events per city page (`bern`, `fribourg`).
5. Digest for a 14-day window is chronological, grouped by local day, and each item
   contains title, start time, venue, city, and a working source URL.
6. Telegram delivery works with messages split under 4096 chars, each part numbered
   `(n/m)`; the delivery outcome (channel, status, part count, external id/error) is
   logged in a `notification_delivery` log line.
7. Duplicate event supplied by two sources appears exactly once (fixture test).
8. One failing source produces a `PARTIAL` run summary and a delivered digest with a
   footer listing the failed source — never an empty digest and never a crash.
9. `docker compose up -d --build` on the Unraid host yields a healthy container,
   a persisted DB under the mounted volume, and one startup digest run in the logs.
10. Restarting the container preserves events; deleting the DB recreates the schema
    via Flyway and repopulates on the next run. Execution history is intentionally not
    preserved (logs only).
11. Cron default `0 0 7 * * MON`, zone `Europe/Zurich`, overridable by env without rebuild.
12. No secret appears in logs or the image (verified by grep over the built image env and
    a sample run log).

---

## 9. Assumptions & Defaults

### Resolved assumptions (operator decision, 2026-02)

1. **Telegram credentials** — bot token and chat id are available and supplied via
   environment at deploy time; a private chat is the default target.
2. **Source access** — `insitu.live`, `bka.ch`, and `eventfrog.ch` are all assumed OK to
   use and scrape for this personal digest. No per-source ToS negotiation blocks
   implementation. Rate limits, `robots.txt` signals, and the no-bypass rules in
   section 7 still apply.
3. **insitu filtering** — client-side city filtering and up to 44 paginated API pages
   per run are accepted.
4. **bka.ch** — a once-weekly scrape of `/agenda` plus event pages is accepted; no
   robots.txt is published (404).
5. **eventfrog.ch** — using the embedded `top-localities` JSON and/or SSR HTML is
   accepted; robots.txt allows `/de/events`.
6. **Scraper identity** — no contact email in the `User-Agent`.

### Non-blocking defaults (change later)

7. Digest window default is 14 days; item cap per message and total cap TBD after the
   first real run (start with 150 items max, truncate with a "more events" footer).
8. Multi-day/ongoing events are included if their run intersects the window, with the
   start date shown and an "ongoing until" note.
9. Bilingual titles are kept as published (FR for Fribourg, DE for Bern); no translation.
10. A failed source is retried once after 30 s within the same run, then marked failed.
11. Interest-based filtering is explicitly post-v1; categories/venues are stored now so
    it can be added without re-ingestion.

---

## Approval

- [x] Capability map (section 0) approved — module ids, dependency direction, build order
- [x] Initiative spec approved — objective, stack, commands, structure, style, testing, boundaries
- [x] Telegram bot token + chat id available (confirmed)

On approval: write `docs/plan.md` + `docs/todo.md`, then per-module specs
`docs/SPEC-<module-id>.md` in build order, starting with `docs/SPEC-domain.md`.
