# Spec: `domain` module

Parent: `docs/SPEC.md` §0 · Depends on: none · Depended on by: `store`, all sources, `digest`, `dedup`

## Objective

Provide the pure, dependency-free canonical model that every adapter produces and every
downstream module consumes:

- immutable value types for a source event and its occurrences,
- validated fetch window and closed enums for sources and statuses,
- deterministic text normalization and the dedup key.

The module contains **no** Spring, persistence, HTTP, browser, or clock access. It is the
contract boundary between ingestion and storage; if a field or invariant is missing
here, the fix belongs in this spec before any adapter change.

**Success looks like:** an adapter can be written against these types without importing
anything from `store`, and normalization/dedup-key behavior is fully covered by plain
JUnit tests runnable in milliseconds.

### Types (the module's public API)

```java
public enum SourceId { INSITU("insitu"), BKA("bka"), EVENTFROG("eventfrog") }  // priority(): lower wins event.url on a merge
public enum EventStatus { CONFIRMED, TENTATIVE, CANCELLED, UNKNOWN }

public record FetchWindow(Instant from, Instant to, ZoneId zone)
public record EventOccurrence(Instant start, Instant end, boolean allDay)
public record SourceEvent(SourceId source, String sourceEventId, String title,
        String description, String venueName, String addressLine, String city,
        String canton, String country, BigDecimal latitude, BigDecimal longitude,
        URI url, Set<String> categories, String priceInfo, EventStatus status,
        List<EventOccurrence> occurrences)

public final class Normalizer { public static String title(String); public static String venue(String); public static String city(String); }
public final class DedupKey   { public static String of(SourceEvent event, ZoneId zone); }
```

## Tech Stack

- Java 25, records + enums + final utility classes, `java.time`, `java.net.URI`,
  `java.math.BigDecimal`.
- **No runtime dependencies.** Tests: JUnit 5 + AssertJ (already in the build).
- No Lombok, no JSpecify; nullable components are documented in Javadoc.

## Commands

Run from `source/`:

```bash
./gradlew test --tests 'ch.fribourgevents.domain.*'
./gradlew spotlessApply
./gradlew build
```

## Project Structure

```
source/src/main/java/ch/fribourgevents/domain/
  FetchWindow.java          → window invariants + fromDate()/toDate()
  SourceId.java             → enum + wire-name parsing
  EventStatus.java          → enum + case-insensitive wire mapping
  EventOccurrence.java      → instant interval + localDate(zone)
  SourceEvent.java          → canonical adapter output + validation
  Normalizer.java           → title/venue/city normalization
  DedupKey.java             → SHA-256 key over normalized identity fields

source/src/test/java/ch/fribourgevents/domain/
  FetchWindowTest.java
  SourceIdTest.java
  EventOccurrenceTest.java
  SourceEventTest.java
  NormalizerTest.java
  DedupKeyTest.java
```

## Code Style

```java
package ch.fribourgevents.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

public record EventOccurrence(Instant start, Instant end, boolean allDay) {

    public EventOccurrence {
        Objects.requireNonNull(start, "start");
        if (end != null && end.isBefore(start)) {
            throw new IllegalArgumentException("end must not be before start");
        }
    }

    /** Local calendar date of the start instant in the given zone. */
    public LocalDate localDate(ZoneId zone) {
        return start.atZone(Objects.requireNonNull(zone, "zone")).toLocalDate();
    }
}
```

Conventions:

- Records validate in compact constructors; required strings are non-blank.
- Collections are copied with `List.copyOf` / `Set.copyOf`; never expose mutable state.
- `Normalizer` and `DedupKey` are `final` with a private constructor (static utility).
- `Normalizer` treats `null` as `""`; never throws on odd upstream text.
- Lowercasing always uses `Locale.ROOT`.
- Optional fields are nullable, not `Optional`, and documented as such.
- Javadoc on every public record component group and every public method.

### Normalization rules (normative)

1. Unicode NFD, remove combining marks (`é → e`, `ü → u`).
2. `ß → ss` (NFD does not decompose it).
3. Lowercase with `Locale.ROOT`.
4. Replace every non-`[a-z0-9]` run with a single space; trim.
5. Collapse repeated spaces (covered by step 4).
6. Idempotent: applying twice equals applying once.

City aliases applied after step 5, before key generation (safe within our scope):

| Input | Canonical |
|---|---|
| `freiburg` | `fribourg` |
| `berne` | `bern` |
| `morat` | `murten` |

### Dedup key (normative)

```
sha256Hex( title | venue | city | firstOccurrence.localDate(zone) )
```

- `title`, `venue`, `city` are normalized; missing venue/city become empty strings.
- `firstOccurrence` is the earliest start instant in the event's occurrence list.
- Output is 64 lowercase hex characters; stable across JVM runs (no randomized hashing).
- The key is an **optimization for exact matches**, not the final dedup decision; the
  `dedup` module owns fuzzy matching.

## Testing Strategy

| Concern | Test |
|---|---|
| `FetchWindow` null/reversed zone handling | `FetchWindowTest` |
| enum wire mapping incl. unknown/case | `SourceIdTest` |
| occurrence end-before-start, `localDate` across DST | `EventOccurrenceTest` |
| required fields, empty occurrences, defensive copies, defaults | `SourceEventTest` |
| normalization table, idempotence, nulls | `NormalizerTest` |
| key stability, accent/case equivalence, date/venue sensitivity | `DedupKeyTest` |

Table cases that must pass:

| Raw | Normalized |
|---|---|
| `Cinéma – Zéro!` | `cinema zero` |
| `L'ÉTÉ  à Fribourg` | `l ete a fribourg` |
| `Straße` | `strasse` |
| `  Café  de la  Gare ` | `cafe de la gare` |
| `null` | `` (empty) |

Rules:

- Pure unit tests only; no Spring context, no DB, no network, no `Thread.sleep`.
- Fixed zones (`Europe/Zurich`, `UTC`) and fixed instants; no `Instant.now()`.
- Coverage target ≥90% line/branch for this module (above the project’s 80% floor).
- A normalization change must update the table above and `docs/SPEC.md` if it alters the
  dedup contract.

## Boundaries

**Always**
- Keep the module free of Spring, Jackson, JDBC, IO, logging frameworks, and clocks.
- Validate in compact constructors; fail fast on programmer errors.
- Use `Locale.ROOT`, immutable copies, and deterministic functions.
- Keep `equals`/`hashCode`/`toString` from records; do not override for identity.

**Ask first**
- Changing normalization rules, the city alias table, or the dedup-key composition.
- Adding a field to `SourceEvent` or `EventOccurrence` (touches every adapter and the
  schema).
- Introducing a runtime dependency (even annotations).

**Never**
- Read the system clock, environment, locale, or files inside this module.
- Throw checked exceptions (adapters wrap failures as `SourceFetchException`).
- Expose mutable lists/sets or make required fields nullable.
- Put source-specific parsing quirks (selectors, JSON field names) in `domain`.

## Success Criteria

1. `./gradlew test --tests 'ch.fribourgevents.domain.*'` passes with no Spring context.
2. `SourceEvent` rejects blank id/title and empty occurrence lists; `EventOccurrence`
   rejects `end < start`; both copy collections defensively.
3. Normalization is idempotent and passes the table above.
4. `DedupKey.of` is stable across JVM runs and differs when title, venue, city, or date
   differs.
5. `FetchWindow.fromDate()` honors the supplied zone and rejects `from >= to`.
6. No `domain` class imports anything outside `java.*`.
7. Coverage ≥90% for the package.

## Open Questions

None blocking. Deferred: abbreviation expansion (`St.` → `saint`), street-address
normalization, and cross-language title matching — all belong to the fuzzy `dedup`
module or a later revision, not v1.
