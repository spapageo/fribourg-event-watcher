# Architecture

How the service is put together, why the boundaries are where they are, and what the
stored data means. The initiative spec is [`SPEC.md`](SPEC.md); per-module contracts live
in `SPEC-domain.md`, `SPEC-store.md`, and the other `SPEC-<module>.md` files.

---

## 1. Runtime shape

One Spring Boot process, one SQLite file, one container. There is no inbound trigger:
runs come from a cron trigger or from `digest.run-on-startup=true`. Both go through the
same lock, so a scheduled run that fires while a startup run is still fetching is skipped
and logged rather than overlapping.

```
        cron (digest.cron, digest.zone)
                     │
startup flag ──► DigestScheduler.runOrSkip(trigger)   [ReentrantLock: one run at a time]
                     │
                     ▼
             DigestPipeline.run(window)
                     │
   ┌─────────────────┼──────────────────┬────────────────┐
   ▼                 ▼                  ▼                ▼
EventSource[]   DedupResolver      DigestService    DigestTextRenderer
(fetch)         + EventStore       (window select)  (plain text)
                     │                  │                │
                     └──────────► SQLite ◄───────────────┘
                                        │
                                        ▼
                              NotificationSender
                              (Telegram by default)
```

`FetchWindow` is local midnight today through local midnight `window-days` later in
`digest.zone`, computed from the injected `Clock`. The window is stable regardless of the
time of day the run happens.

---

## 2. Module graph

The Gradle build is a single module; the packages are review boundaries. The dependency
direction is downward-only and is enforced by `ModuleBoundaryTest` (ArchUnit) using the
layers below.

```
                         runtime
                            │
                         pipeline
        ┌───────┬───────────┼───────────┬──────────┐
        ▼       ▼           ▼           ▼          ▼
     source   dedup       digest      notify     store
        │       │           │           │          │
        │       └───────────┴───────────┴──────────┤
        └──────────────────────► domain ◄───────────┘
```

| Package | Owns | May depend on |
|---|---|---|
| `domain` | Immutable model (`SourceEvent`, `EventOccurrence`, `FetchWindow`), `SourceId`, `EventStatus`, `Normalizer`, `DedupKey` | Nothing in the project |
| `store` | Flyway schema, JPA entities/repositories, `EventStore` upsert, window query, candidate query | `domain` |
| `dedup` | `DedupMatcher` (pure decision), `DedupProperties`, `DedupResolver` (store + matcher) | `domain`, `store` |
| `digest` | `DigestService` (window selection), `DigestMessage` (grouped, capped), `DigestTextRenderer` | `domain`, `store` |
| `notify` | `NotificationSender` SPI, `MessageChunker`, delivery result; `notify.telegram` implementation | `domain`, `digest` |
| `source` | `EventSource` SPI, `PageLoader`, rate limiter; `source.insitu`, `source.bka`, `source.eventfrog`, `source.browser` | `domain` |
| `pipeline` | `DigestPipeline` orchestration, `PipelineResult`, `SourceSyncResult`, `PipelineOptions` | all of the above except `runtime` |
| `runtime` | `DigestProperties`/`BrowserProperties`/`DedupProperties`, scheduler, startup runner, Spring bean wiring | everything |

Rules that keep this honest:

- `domain` has no Spring, JDBC, HTTP, browser, or clock access. Normalization and the
  dedup key are deterministic pure functions.
- `store` is the only package that talks to SQLite; no SQL leaks into `digest` or
  `pipeline`.
- `dedup` owns matching and resolution; `store` knows nothing about scoring. The
  resolver lives in `dedup` so the dependency stays `dedup → store`, not the reverse.
- Adapters return `SourceEvent`s and never touch the database, the digest, or the
  notifier.

---

## 3. One run, step by step

1. The scheduler builds the `FetchWindow` and calls `DigestPipeline.run(window)`.
2. For each enabled source, in configuration order:
   - `EventSource.fetch(window)` reads the source (API or a Selenium-backed `PageLoader`)
     and returns `SourceFetch(events, pagesFetched)`.
   - For each event, `DedupResolver.findCanonicalEventId(event)` reads the blocked
     candidate set from the store and runs the matcher.
   - `EventStore.upsert(event, seenAt, canonicalEventId)` writes the canonical event, the
     `(source, source_event_id)` ref, and its occurrences.
   - The outcome is recorded as `SourceSyncResult` with `eventsSeen`,
     `eventsInserted`, `eventsMerged`, and a status.
3. A source failure is caught per source: the run continues and the result becomes
   `PARTIAL`. Only when **every** source fails does the pipeline skip the digest (an empty
   "no events" message would be a lie); it optionally sends a failure notice instead.
4. `DigestService.select(window)` reads occurrences that intersect the window and
   `DigestMessage.build` sorts, applies the item cap (150 by default), and groups by local
   day. Occurrences that started before the window are shown on the first day as
   `ongoing`.
5. The renderer produces plain text. The pipeline appends footer notes: unavailable
   sources, then the merged-event count when `digest.show-merged-count` is on.
6. The notifier renders the transport format (Telegram HTML), chunks it under the part
   limit, sends, and returns a `DeliveryResult`.
7. `digest_run_finished` records the overall status and aggregate counts. Nothing is
   persisted about the run itself.

Isolation is the point: one broken selector or a timeout yields a `PARTIAL` digest with a
footer, not a crash and not a silent empty message.

---

## 4. Data model

Flyway migration `V1__init.sql` creates three tables. `ddl-auto=validate` means Hibernate
never invents schema; every schema change is a new migration.

```
event 1 ──── * event_source_ref      (one canonical event, one row per upstream record)
  │
  └──── * event_occurrence           (dated showings of that event)
```

### `event` — canonical, source-agnostic

| Column group | Notes |
|---|---|
| `id`, `dedup_key` | `dedup_key` is UNIQUE and written **once** at creation; merges never rewrite it |
| `title`, `normalized_title` | `normalized_title` feeds search/matching and is derived from the title |
| `description`, `venue_name`, `address_line`, `city`, `canton`, `country` | Merged conservatively; optional fields stay nullable |
| `lat`, `lon` | `DOUBLE`; SQLite JDBC reports `DECIMAL` as JDBC `FLOAT`, so `Double` avoids a validation mismatch |
| `url` | Derived from the attached refs by source priority (see below) |
| `categories`, `price_info` | Newline-separated categories, unioned on merge |
| `status` | `CONFIRMED`, `TENTATIVE`, `CANCELLED`, `UNKNOWN` (enum names in `VARCHAR`) |
| `created_at`, `updated_at` | Fixed-width ISO-8601 UTC text |

Indexes: `city`, `normalized_title`.

### `event_source_ref` — provenance

| Column group | Notes |
|---|---|
| `event_id`, `source`, `source_event_id` | UNIQUE `(source, source_event_id)`; one upstream record maps to exactly one canonical event |
| `source_url`, `payload_hash` | `payload_hash` makes replay idempotent: an unchanged payload touches only `last_seen_at` |
| `first_seen_at`, `last_seen_at` | Fixed-width UTC text |

Foreign key `event_id → event` cascades on delete; index on `event_id`.

### `event_occurrence` — showings

| Column group | Notes |
|---|---|
| `event_id`, `start_utc`, `end_utc`, `all_day` | UNIQUE `(event_id, start_utc)`; duplicate starts in one payload collapse |
| `local_date` | `YYYY-MM-DD` in `digest.zone`; the dedup candidate query blocks on it |

Indexes: `start_utc`, `local_date`, `event_id`; foreign key cascades.

### Invariants worth remembering

- A repeated identical payload changes **only** `event_source_ref.last_seen_at`.
- A changed payload updates the canonical fields, the ref URL/hash, and reconciles the
  occurrences of that source. A source may delete occurrences only when it is the sole
  ref; with multiple refs, removal is skipped so one source cannot erase another's
  showings.
- Timestamps are stored as fixed-width UTC text, so lexicographic SQL ordering equals
  chronological ordering.
- SQLite runs with WAL, `busy_timeout=5000`, `foreign_keys=on`, and a Hikari pool of one
  connection. The deployment assumes one process owns the file.

---

## 5. Cross-source deduplication

Exact identity first, then fuzzy matching inside a tightly blocked set.

1. **Exact key.** `DedupKey.of(event, zone)` is
   `SHA-256(normalized title | normalized venue | normalized city | first local date)`.
   An exact stored key wins immediately.
2. **Fuzzy candidates.** Blocked to the same normalized city and a first-occurrence local
   date within `DEDUP_DATE_WINDOW_DAYS` (default ±1). The store's candidate query returns
   events with any occurrence in that date range; the matcher applies the city block.
3. **Scores.** Jaro-Winkler on normalized title (`DEDUP_TITLE_THRESHOLD`, default 0.90)
   and venue (`DEDUP_VENUE_THRESHOLD`, default 0.85) when both sides have one. A missing
   venue passes the venue gate. The combined score is the mean when both venues exist and
   the title score alone otherwise. Ties break deterministically by title, venue, then
   dedup key.
4. **Traps that are never merged.** A recurring series on different dates, or the same
   title at a different venue. The date and city blocks, plus the venue threshold, are what
   enforce this.

### Merge policy

When a new source record attaches to an existing canonical event, the merge never weakens
it:

- blanks are filled from the newcomer;
- descriptions and price info keep the longer text;
- categories are united;
- `CANCELLED` outranks `CONFIRMED`, which outranks `TENTATIVE`, which outranks `UNKNOWN`;
- the canonical title and `dedup_key` are never rewritten (they are the identity);
- new occurrences are added, existing ones updated; none are removed by a merge.

### Preferred URL

`event.url` is recomputed from all attached refs and the lowest `SourceId.priority()` wins:

```
insitu (0) → bka (1) → eventfrog (2)
```

The tie-break is `source_event_id`. This makes the merged item's link stable regardless
of which adapter happened to write last. A lower-priority source updating its payload
cannot steal the link.

### Run-report counters

- `events_seen`: rows the adapter returned this run
- `events_inserted`: canonical events created this run
- `events_merged`: new source refs attached to an existing canonical event

They are aggregated on `PipelineResult`, repeated on the `source_sync` and
`digest_run_finished` log lines, and the merged total optionally appears in the message
footer. Nothing is persisted.

---

## 6. Sources and transport

| Adapter | Access | Limits and behaviour |
|---|---|---|
| `insitu.live` | JSON API (`/api/events`), no Selenium | Paging stops on `view.next`; the 60-request cap fails loudly rather than truncating. `limit` is ignored upstream (30 rows/page). Window and city filtering are client-side. Rows are open days grouped into one event per id. |
| `bka.ch` | JSON API (`/api/events`), no Selenium | Date filters are calendar days with overlap semantics; the adapter re-checks instants client-side. Paging stops on an empty `data`. Showings grouped by `mainevent_id` into one event with many occurrences. |
| `eventfrog.ch` | SSR HTML via Selenium `PageLoader` | Reads the Fribourg and Bern city pages, then a bounded number of JSON-LD detail pages for the earliest in-window candidates. Parsers are pure functions over page source, so tests never launch a browser. |
| `notify.telegram` | Bot API `sendMessage` | HTML escaped by the Telegram renderer, split under the part limit (4000 by default), each part numbered `(n/m)`. Delivery outcome is logged, not stored. |

All scraped requests share one `RateLimiter` (1 req/s per host) and a 60-request budget
per run. See [`sources.md`](sources.md) for selectors, field mappings, and per-source
quirks.

---

## 7. Observability: logs instead of run history

Execution history is deliberately **not** persisted. Operators read the container log;
the contract is four structured lines per run plus a few lifecycle lines:

| Line | Required fields |
|---|---|
| `digest_run_started` | `run_id`, `window_from`, `window_to`, enabled sources, `dry_run` |
| `source_sync` | `run_id`, `source`, `status`, `pages_fetched`, `events_seen`, `events_inserted`, `events_merged`, `duration_ms`, `error` |
| `notification_delivery` | `run_id`, `channel`, `status`, `message_count`, `external_id`, `error` |
| `digest_run_finished` | `run_id`, `status`, `events_selected`, `events_seen`, `events_inserted`, `events_merged`, `delivered` |

Additional lifecycle lines: `digest_run_triggered`, `digest_run_skipped` (overlap),
`digest_run_crashed`, `digest_run_dry_run`, `startup_run_started`/`skipped`. Errors are
single-line, bounded, and never include credentials.

Accepted consequences of not persisting runs: no cross-restart history, at-most-once
delivery per invocation, and a restart re-sends if the process dies after Telegram accepts
a message. See [`SPEC.md`](SPEC.md) §4.

---

## 8. Key decisions and trade-offs

| Decision | Why | Cost |
|---|---|---|
| Single Gradle module, package layers | Build is simpler than a multi-module reactor; package boundaries are enough at this size | Boundary violations must be caught by `ModuleBoundaryTest`, not the compiler |
| SQLite + one connection | A weekly personal digest has no write concurrency; a file is trivial to back up | No concurrent processes on the same DB; a long write blocks reads |
| Flyway only, `ddl-auto=validate` | Schema is explicit and reviewable; no silent drift | Every schema change is a new migration |
| Pure domain, adapters return `SourceEvent` | Matching, storage, and rendering are testable without network/browser | Adapters must fit the shared model; missing fields stay null |
| Selenium behind `PageLoader` | Only `eventfrog.ch` needs a browser; tests use page-source fixtures | Image ships Chromium and needs `/dev/shm` and a writable home |
| Two APIs, one browser source | `insitu`/`bka` JSON is richer and cheaper than HTML; eventfrog is SSR-only | Three adapters with different paging models |
| Exact dedup key then fuzzy matcher | The key is cheap and deterministic; fuzzy handles punctuation/venue variants | False merges are possible at low thresholds; blocked by city/date/venue gates |
| `event.url` from source priority | A merged item must link somewhere stable and useful | Ties to the priority order in `SourceId` |
| In-memory run summary, structured logs | Avoids run-history tables and their retention/design questions | No queries over past runs; operators grep logs |
| Channel-agnostic `DigestMessage` | Telegram is one implementation; chunking/escaping are transport concerns | A second channel needs a renderer/sender, not pipeline changes |
| Env-only configuration | One image, deploy-time overrides, no rebuild to change schedule | Startup validation must be thorough (it is) |

---

## 9. Quality gates

- `./gradlew spotlessApply build` before every commit: google-java-format, JUnit 5,
  JaCoCo line coverage ≥80% overall (application bootstrap excluded), `bootJar`.
- Tests never touch the network; live smokes use the `live` tag and
  `-PincludeTags=live`.
- Integration tests use a real temporary SQLite file with Flyway and
  `ddl-auto=validate`, never DB mocks.
- `ModuleBoundaryTest` enforces the layer graph above.
- A bug fix lands with a failing-then-passing test.
