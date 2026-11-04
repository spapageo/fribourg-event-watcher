# Spec: `store` module

Status: **IMPLEMENTED** — 2026-02
Parent: `docs/SPEC.md` §0 (capability map), §4 (data model)
Depends on: `domain`

---

## 1. Purpose

`store` is the only module that talks to SQLite. It owns the schema, JPA mappings, and
the idempotent write path that turns a stream of `SourceEvent`s into canonical
`event` / `event_source_ref` / `event_occurrence` rows. Read-side queries for the
digest window also live here so no other module writes SQL.

## 2. Public API

```java
package ch.fribourgevents.store;

public record UpsertResult(
    EventEntity event,
    boolean eventCreated,
    boolean sourceRefCreated,
    boolean payloadChanged) {
  boolean merged();  // sourceRefCreated && !eventCreated
}

public record DedupCandidate(
    Long eventId,
    String dedupKey,
    String title,
    String venueName,
    String city,
    List<EventOccurrence> occurrences) {}

public class EventStore {
  UpsertResult upsert(SourceEvent sourceEvent);
  UpsertResult upsert(SourceEvent sourceEvent, Instant seenAt);
  UpsertResult upsert(SourceEvent sourceEvent, Instant seenAt, Long canonicalEventId);
  List<DedupCandidate> findDedupCandidates(SourceEvent input, int dayWindowDays);
  List<EventOccurrenceEntity> findOccurrencesOverlapping(Instant from, Instant to);
  Optional<EventEntity> findEvent(Long id);
}
```

`findDedupCandidates` is the read side of cross-source dedup: it returns stored events
with an occurrence local date within `dayWindowDays` of the input's first occurrence.
`DedupResolver` (module `dedup`) maps those into the pure matcher and passes the matched
`eventId` back into the three-argument `upsert`; this keeps the dependency direction
`dedup → store` instead of making the store depend on scoring logic.

Repositories (`EventRepository`, `EventSourceRefRepository`,
`EventOccurrenceRepository`) are package-facing Spring Data interfaces and are not part
of the cross-module contract.

## 3. Invariants

1. `(source, source_event_id)` is globally unique; one source record maps to exactly one
   canonical event.
2. `event.dedup_key` is unique and is computed once, when the canonical event is first
   created. Later source payload changes never rewrite it.
3. `(event_id, start_utc)` is unique; duplicate starts in one payload collapse to one
   occurrence (last value wins).
4. A repeated identical payload changes **only** `event_source_ref.last_seen_at`.
   `event.updated_at`, `event.created_at`, and all occurrence rows stay untouched.
5. A changed payload updates every mutable event field, `payload_hash`, `source_url`,
   and `last_seen_at`; occurrences are reconciled to the payload.
6. A source may delete occurrences only when it is the sole source ref for the event.
   With multiple refs, removal is skipped so one source cannot erase another's
   showings.
7. All timestamps are fixed-width ISO-8601 UTC text (`yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`),
   so lexicographic SQL comparison equals chronological comparison. `local_date` is
   `YYYY-MM-DD` in `digest.zone` (`Europe/Zurich` by default).
8. Foreign keys cascade on `event` deletion. SQLite enforcement is enabled per
   connection (`foreign_keys=on`).
9. A new source record merged into an existing event never weakens it: blanks are
   filled, description/price keep the longer text, categories are united, and
   `CANCELLED`/`CONFIRMED` survive `UNKNOWN`/tentative contributors. The canonical title
   and `dedup_key` are never rewritten.
10. `event.url` is derived from the attached refs: the lowest `SourceId.priority()`
    (`insitu`, then `bka`, then `eventfrog`) with a non-null `source_url` wins. A
    lower-priority source updating its payload therefore cannot steal the digest link.

## 4. Schema

`V1__init.sql` creates only the three canonical data tables from `docs/SPEC.md` §4:
`event`, `event_source_ref`, and `event_occurrence`. Execution history is deliberately
not persisted; digest runs, source syncs, and notification deliveries are in-memory
results plus structured logs.

SQLite mapping choices:

| Concern | Choice | Why |
|---|---|---|
| Primary keys | `INTEGER PRIMARY KEY AUTOINCREMENT` + `@JdbcTypeCode(INTEGER)` | SQLite requires exactly `INTEGER` for a rowid alias; Hibernate otherwise expects `bigint` for `Long` |
| Coordinates | `DOUBLE` / `Double` | SQLite JDBC reports `DECIMAL` as JDBC `FLOAT`, which fails Hibernate validation against `numeric` |
| Text timestamps | `VARCHAR` + `AttributeConverter` | Keeps the fixed-width UTC text contract |
| Enums | `VARCHAR` + `@Enumerated(STRING)` | Names, not ordinals |
| Categories | newline-separated `VARCHAR` | Readable in `sqlite3`; avoids a JSON dependency in `store`; values have newlines stripped |
| Timestamps | `VARCHAR`, nullable end | Compare as text |

`ddl-auto=validate` is used in the application and in tests. Flyway is the only source
of schema changes.

## 5. Upsert algorithm

```
hash = PayloadHash(SourceEvent)
ref  = findBy(source, sourceEventId)

if ref exists:
    if hash == ref.payloadHash:
        ref.lastSeenAt = seenAt              # nothing else
    else:
        update event fields + updatedAt
        if ref is sole owner: reconcile occurrences (add/update/delete)
        else:                 reconcile occurrences (add/update only)
        ref.payloadHash = hash; ref.sourceUrl = url; ref.lastSeenAt = seenAt
else:
    target   = resolver match (canonicalEventId) or findByDedupKey(dedupKey)
    event    = target or create event
    if target existed: merge source fields into it (see invariant 9)
    reconcile occurrences (add/update only for an existing event)
    insert ref(firstSeenAt = lastSeenAt = seenAt)
    event.url = preferred ref url (see invariant 10)
```

`PayloadHash` covers title, description, venue, address, city, canton, country,
coordinates, URL, sorted categories, price, status, and sorted occurrences
(start/end/all-day). It is a SHA-256 hex digest with length-prefixed fields to avoid
delimiter ambiguity.

## 6. Window query

```sql
start_utc < :to
AND (start_utc >= :from OR (end_utc IS NOT NULL AND end_utc >= :from))
ORDER BY start_utc, event.title
```

An occurrence with no end is treated as instantaneous at `start_utc`. This prevents a
past event with an unknown end from appearing in every future digest. Multi-day events
with an explicit end still appear while they overlap the window.

## 7. Boundaries

**Always**
- Persist timestamps through `InstantText` / converters; never `toString()` ad hoc.
- Mutate occurrences only inside `EventStore`.
- Keep `ddl-auto=validate`; add a new Flyway migration for every schema change.
- Use a real SQLite file (temp) in integration tests, with Flyway and no DB mocks.

**Ask first**
- Changing the dedup key inputs or making it mutable.
- Adding a second write path outside `EventStore`.
- Storing categories as JSON instead of newline text.

**Never**
- `ddl-auto=create`, `update`, or `create-drop` in the application.
- Editing `V1__init.sql` after it has shipped; add `V2__...` instead.
- Letting a source delete occurrences owned by other sources.
- Persisting digest runs, source syncs, or notification deliveries; those are in-memory
  results and structured logs only.

## 8. Tests

| Test | Covers |
|---|---|
| `SchemaValidationTest` | Flyway creates all tables/indexes, `ddl-auto=validate` passes, migrations recorded, FK pragma on |
| `EventStoreTest` | insert, idempotent replay, payload change, occurrence reconciliation, cross-source merge (exact and resolved/fuzzy), richer-field merge, preferred URL, window query/order, fixed UTC text, duplicate starts, invalid window, candidate date window |

Verification:

```bash
cd source
./gradlew test --tests 'ch.fribourgevents.store.*'
./gradlew build
```

## 9. Success criteria

1. A fresh database migrates and validates with no manual SQL.
2. Running the same fixture twice leaves all row counts unchanged and only advances
   `last_seen_at`.
3. A changed title/URL/time is reflected in the canonical event and source ref.
4. The same event from two sources shares one `event` row and keeps both refs.
5. The window query returns each qualifying occurrence exactly once, ordered by start.
6. Tests pass offline against a temporary SQLite file.
