# Implementation Plan: Fribourg & Bern Event Digest

## Overview

Build a single Spring Boot service (Java 25, Groovy Gradle DSL, SQLite) that fetches
events from `insitu.live`, `bka.ch`, and `eventfrog.ch`, normalizes and deduplicates
them, renders a chronological 14-day digest, and delivers it to Telegram on a
configurable cron (default Monday 07:00 Europe/Zurich). It runs as one container on an
Unraid server, with a run-on-startup flag for testing. Approved spec: `docs/SPEC.md`.

## Architecture Decisions

1. **Single Gradle module with package boundaries.** Modules in the capability map are
   review boundaries, not deployment units. An ArchUnit test enforces downward-only
   dependencies (`domain` ← `store` ← sources ← `pipeline` ← `runtime`).
2. **`source/` is the Gradle root** with the standard `src/main/java` layout; all
   Markdown lives in `docs/`.
3. **Domain is pure.** No Spring, Jackson, JDBC, IO, or system clock; normalization and
   dedup-key generation are deterministic pure functions, unit-tested without context.
4. **SQLite via Flyway + JPA with `ddl-auto=validate`.** Hikari pool size 1, WAL,
   `busy_timeout=5000`; schema changes only through migrations.
5. **Canonical event model.** `event` is source-agnostic; `event_source_ref` links each
   upstream record; `event_occurrence` holds dated showings. Cross-source dedup merges
   refs onto one canonical event.
6. **Adapter SPI + `PageLoader`.** Sources return `SourceEvent`s and never touch the DB.
   Selenium sits behind `PageLoader`; parsers are pure functions over page source, so
   tests never launch a browser.
7. **Channel-agnostic notifications.** `NotificationSender` receives a rendered
   `DigestMessage`; Telegram is the only v1 implementation. Chunking and escaping are
   shared, not Telegram-specific.
8. **Failure isolation, no execution persistence.** Each source sync runs independently
   inside the pipeline and contributes to an in-memory `PipelineResult`; a failure yields
   a `PARTIAL` summary/log and a footer, never an empty digest. Runs, per-source syncs,
   and deliveries are logged, not written to SQLite.
9. **Config over rebuild.** Cron, window, sources, DB path, and Telegram credentials are
   environment-driven `@ConfigurationProperties`.
10. **Vertical first slice.** insitu → store → digest → Telegram is working end-to-end at
    Checkpoint B; Bern sources and dedup land after the runtime is deployable.

## Dependency Graph

```
domain ──► store ──► source-insitu ──┐
   │          │                      ├──► pipeline ──► runtime
   │          ├──► digest ──► notify ┴          ▲
   │          │                └──► notify-telegram
   │          │
   └──────────┴──► source-bka ──► source-eventfrog ──► dedup
```

`source-bka`, `source-eventfrog`, and `dedup` feed `pipeline` after `runtime` exists.
`dedup` also changes `store` upsert behavior, so it comes last.

## Task List

Detailed acceptance criteria, verification, and file lists: `docs/todo.md`.

### Phase 1 — Foundation (`domain`, `store`)
- [x] Task 1: Scaffold Gradle project, toolchain, and quality gates
- [x] Task 2: Domain value types and invariants
- [x] Task 3: Event and occurrence model
- [x] Task 4: Normalization and dedup key
- [x] Task 5: Flyway V1 schema and JPA entities
- [x] Task 6: Store upsert service with idempotency tests

### Checkpoint A — Foundation
- [x] `cd source && ./gradlew build` green, no network
- [x] Schema validates from an empty temp DB
- [x] Re-running upserts creates no duplicates

### Phase 2 — First vertical slice (insitu → digest → Telegram)
- [x] Task 7: insitu.live API client and adapter
- [x] Task 8: Digest window selection, grouping, and text rendering
- [x] Task 9: Notification SPI and message chunker
- [x] Task 10: Telegram sender
- [x] Task 11: Pipeline orchestration, in-memory run summary, partial-failure handling

### Checkpoint B — Working single-source product
- [x] `./gradlew bootRun --args='--digest.dry-run=true --digest.run-on-startup=true'`
  fetches insitu, persists events, logs the rendered digest
- [ ] With `--digest.dry-run=false` a real Telegram message is delivered
- [x] A forced failing source produces a `PARTIAL` run summary, not a crash

### Phase 3 — Runtime and deployment (`runtime`)
- [x] Task 12: Scheduler, startup runner, config properties, Actuator
- [x] Task 13: Docker image (JRE + Chromium), compose, healthcheck, volume

### Checkpoint C — Deployable
- [ ] `docker compose up -d --build` (or imported-image template) → healthy container on Unraid
- [ ] DB survives container restart
- [ ] Cron default is Monday 07:00 Europe/Zurich and env-overridable

### Phase 4 — Bern sources
- [x] Task 14: Browser layer (Selenium `PageLoader`, rate limiter)
- [x] Task 15: bka.ch adapter
- [x] Task 16: eventfrog.ch adapter

### Checkpoint D — Three sources
- [x] insitu full upcoming FR agenda (886 distinct events at 2026-11-03), bka ≥100 Bern events, eventfrog ≥50/city
- [ ] All three run in one pipeline run within rate limits
- [x] One manual live smoke per source documented

### Phase 5 — Dedup and polish
- [x] Task 17: Cross-source dedup matcher
- [x] Task 18: Pipeline dedup integration and run report footer
- [ ] Task 19: Documentation and final v1 verification

### Checkpoint E — v1 complete
- [ ] All twelve success criteria in `docs/SPEC.md` verified
- [ ] `docs/README.md`, `docs/runbook.md`, `docs/sources.md` accurate
- [ ] Build green from a clean clone

## Parallelization

| Safe in parallel | Must stay sequential | Needs a shared contract first |
|---|---|---|
| Tasks 7, 8, 9 after Task 6 | Tasks 1→2→3→4→5→6 | `EventSource` SPI (Task 2) before Tasks 7/15/16 |
| Tasks 9/10 after Task 8 | Tasks 11→12→13 | `DigestMessage` (Task 8) before Tasks 9/10 |
| Tasks 15 and 16 after Task 14 | Task 17 after 15+16, then 18 | `PageLoader` (Task 14) before 15/16 |
| Task 19 after 18 | | SQLite schema (Task 5) before any store work |

## Risks and Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Scraper selectors break (bka, eventfrog) | High | Pure parsers + saved fixtures; adapter isolation; live smoke tag; failure footer |
| Selenium/Chromium image size and Unraid permissions | Med | Pin Debian Chromium, run non-root with configurable UID/GID, `--shm-size=1g`, document in runbook |
| SQLite + Hibernate 7 community dialect quirks | Med | Flyway only, `ddl-auto=validate`, pool 1, WAL, store timestamps as ISO text and enums as names, integration tests on a temp file DB |
| Spring Boot 4 + Java 25 ecosystem gaps | Med | Boot 4.1.2 + Hibernate 7.4.13 pinned in the version catalog; Task 1 build verified |
| insitu 44-page crawl time | Med | 1 req/s + 60-request cap; ~1 min budget; single run per week; per-request timeout |
| Telegram 4096-char limit / HTML escaping | Low | Chunker splits on item boundaries and numbers parts; escaping unit-tested |
| Dedup false merges (recurring series) | Med | Match on title + venue + city + date (±1 day), threshold configurable, never merge across different source ids on weak signals; fixture suite |
| No git repository yet | Low | `git init` + `.gitignore` in Task 1; commit at every checkpoint |
| Unraid volume path/ownership | Low | Configurable `DB_PATH`; compose bind `./data`; document UID/GID mapping |

## Verification Cadence

- After every task: focused tests + `./gradlew build`.
- After every phase: checkpoint checklist in `docs/todo.md`, then a git commit.
- Live network tests only via `-PincludeTags=live`, never in `build`.
- Before Checkpoint E: run the container against the real sites and compare the digest
  against the three source websites manually.

## Open Questions

None blocking. Defaults live in `docs/SPEC.md` §9; change them in one place.
