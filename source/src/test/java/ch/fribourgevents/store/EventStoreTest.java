package ch.fribourgevents.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.fribourgevents.domain.EventOccurrence;
import ch.fribourgevents.domain.EventStatus;
import ch.fribourgevents.domain.SourceEvent;
import ch.fribourgevents.domain.SourceId;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@DataJpaTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "spring.flyway.enabled=true",
      "spring.datasource.hikari.maximum-pool-size=1",
      "spring.datasource.hikari.minimum-idle=1"
    })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({StoreConfiguration.class, EventStore.class})
class EventStoreTest {

  private static final Path DB_FILE = SqliteTestDatabase.create("store");

  @DynamicPropertySource
  static void sqlite(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> SqliteTestDatabase.url(DB_FILE));
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add(
        "spring.jpa.database-platform", () -> "org.hibernate.community.dialect.SQLiteDialect");
  }

  @Autowired private EventStore store;
  @Autowired private EventRepository events;
  @Autowired private EventSourceRefRepository sourceRefs;
  @Autowired private EventOccurrenceRepository occurrences;
  @Autowired private TestEntityManager entityManager;
  @Autowired private JdbcTemplate jdbc;

  @AfterAll
  static void deleteDatabase() {
    SqliteTestDatabase.delete(DB_FILE);
  }

  @Test
  void insertsCanonicalEventRefAndOccurrences() {
    Instant seenAt = Instant.parse("2026-02-01T10:00:00Z");
    SourceEvent input =
        event(SourceId.INSITU, "i-1", "Cinéma Zéro", occurrence("2026-03-01T19:00:00Z"));

    UpsertResult result = store.upsert(input, seenAt);
    flushAndClear();

    assertThat(result.eventCreated()).isTrue();
    assertThat(result.sourceRefCreated()).isTrue();
    assertThat(result.payloadChanged()).isTrue();
    assertThat(events.count()).isEqualTo(1);
    assertThat(sourceRefs.count()).isEqualTo(1);
    assertThat(occurrences.count()).isEqualTo(1);

    EventEntity event = events.findById(result.event().getId()).orElseThrow();
    assertThat(event.getTitle()).isEqualTo("Cinéma Zéro");
    assertThat(event.getNormalizedTitle()).isEqualTo("cinema zero");
    assertThat(event.getCountry()).isEqualTo("CH");
    assertThat(event.getStatus()).isEqualTo(EventStatus.CONFIRMED);
    assertThat(event.getCategories()).isEqualTo("concert\ntheatre");
    assertThat(event.getDedupKey()).hasSize(64);
    assertThat(event.getCreatedAt()).isEqualTo(seenAt);
    assertThat(event.getUpdatedAt()).isEqualTo(seenAt);

    EventOccurrenceEntity occurrence = occurrences.findAll().getFirst();
    assertThat(occurrence.getLocalDate()).isEqualTo(LocalDate.of(2026, 3, 1));
    assertThat(occurrence.isAllDay()).isFalse();
  }

  @Test
  void repeatedIdenticalUpsertOnlyTouchesLastSeenAt() {
    Instant first = Instant.parse("2026-02-01T10:00:00Z");
    Instant second = Instant.parse("2026-02-02T10:00:00Z");
    SourceEvent input =
        event(SourceId.INSITU, "i-1", "Concert", occurrence("2026-03-01T19:00:00Z"));

    UpsertResult initial = store.upsert(input, first);
    UpsertResult repeated = store.upsert(input, second);
    flushAndClear();

    assertThat(events.count()).isEqualTo(1);
    assertThat(sourceRefs.count()).isEqualTo(1);
    assertThat(occurrences.count()).isEqualTo(1);
    assertThat(repeated.payloadChanged()).isFalse();
    assertThat(repeated.eventCreated()).isFalse();
    assertThat(repeated.sourceRefCreated()).isFalse();

    EventEntity event = events.findById(initial.event().getId()).orElseThrow();
    assertThat(event.getCreatedAt()).isEqualTo(first);
    assertThat(event.getUpdatedAt()).isEqualTo(first);

    EventSourceRefEntity ref = sourceRefs.findAll().getFirst();
    assertThat(ref.getFirstSeenAt()).isEqualTo(first);
    assertThat(ref.getLastSeenAt()).isEqualTo(second);
  }

  @Test
  void changedPayloadUpdatesFieldsRefAndOccurrences() {
    Instant first = Instant.parse("2026-02-01T10:00:00Z");
    Instant second = Instant.parse("2026-02-03T10:00:00Z");
    SourceEvent initial =
        event(
            SourceId.INSITU,
            "i-1",
            "Concert",
            occurrences("2026-03-01T19:00:00Z", "2026-03-02T19:00:00Z"));
    SourceEvent changed =
        event(
            SourceId.INSITU,
            "i-1",
            "Concert déplacé",
            occurrences("2026-03-02T19:00:00Z", "2026-03-03T19:00:00Z"));

    UpsertResult created = store.upsert(initial, first);
    String oldHash = sourceRefs.findAll().getFirst().getPayloadHash();
    UpsertResult updated = store.upsert(changed, second);
    flushAndClear();

    assertThat(events.count()).isEqualTo(1);
    assertThat(sourceRefs.count()).isEqualTo(1);
    assertThat(updated.payloadChanged()).isTrue();
    assertThat(events.findById(created.event().getId()).orElseThrow().getTitle())
        .isEqualTo("Concert déplacé");
    assertThat(events.findById(created.event().getId()).orElseThrow().getUpdatedAt())
        .isEqualTo(second);
    assertThat(occurrences.findByEvent_Id(created.event().getId()))
        .extracting(EventOccurrenceEntity::getStartUtc)
        .containsExactly(
            Instant.parse("2026-03-02T19:00:00Z"), Instant.parse("2026-03-03T19:00:00Z"));

    EventSourceRefEntity ref = sourceRefs.findAll().getFirst();
    assertThat(ref.getPayloadHash()).isNotEqualTo(oldHash);
    assertThat(ref.getLastSeenAt()).isEqualTo(second);
  }

  @Test
  void mergesCrossSourceDuplicateIntoOneCanonicalEvent() {
    SourceEvent insitu =
        event(SourceId.INSITU, "i-1", "Concert", occurrence("2026-03-01T19:00:00Z"));
    SourceEvent bka = event(SourceId.BKA, "b-1", "Concert", occurrence("2026-03-01T19:00:00Z"));

    UpsertResult first = store.upsert(insitu, Instant.parse("2026-02-01T10:00:00Z"));
    UpsertResult second = store.upsert(bka, Instant.parse("2026-02-02T10:00:00Z"));
    flushAndClear();

    assertThat(second.eventCreated()).isFalse();
    assertThat(second.sourceRefCreated()).isTrue();
    assertThat(second.merged()).isTrue();
    assertThat(first.merged()).isFalse();
    assertThat(second.event().getId()).isEqualTo(first.event().getId());
    assertThat(events.count()).isEqualTo(1);
    assertThat(sourceRefs.count()).isEqualTo(2);
    assertThat(occurrences.count()).isEqualTo(1);
  }

  @Test
  void doesNotRemoveOccurrencesOwnedByAnotherSource() {
    SourceEvent insitu =
        event(SourceId.INSITU, "i-1", "Concert", occurrence("2026-03-01T19:00:00Z"));
    SourceEvent bkaBoth =
        event(
            SourceId.BKA,
            "b-1",
            "Concert",
            occurrences("2026-03-01T19:00:00Z", "2026-03-02T19:00:00Z"));
    SourceEvent bkaShrunk =
        event(SourceId.BKA, "b-1", "Concert", occurrence("2026-03-01T19:00:00Z"));

    UpsertResult created = store.upsert(insitu, Instant.parse("2026-02-01T10:00:00Z"));
    store.upsert(bkaBoth, Instant.parse("2026-02-02T10:00:00Z"));
    store.upsert(bkaShrunk, Instant.parse("2026-02-03T10:00:00Z"));
    flushAndClear();

    assertThat(occurrences.findByEvent_Id(created.event().getId())).hasSize(2);
  }

  @Test
  void findsOccurrencesOverlappingWindowOrderedByStart() {
    store.upsert(
        event(SourceId.INSITU, "before", "Before", occurrence("2026-02-01T19:00:00Z")),
        Instant.parse("2026-02-01T10:00:00Z"));
    store.upsert(
        event(SourceId.INSITU, "inside", "Inside", occurrence("2026-03-05T19:00:00Z")),
        Instant.parse("2026-02-01T10:00:00Z"));
    store.upsert(
        event(SourceId.INSITU, "after", "After", occurrence("2026-03-20T19:00:00Z")),
        Instant.parse("2026-02-01T10:00:00Z"));
    store.upsert(
        new SourceEvent(
            SourceId.INSITU,
            "spanning",
            "Spanning",
            null,
            "Nouveau Monde",
            null,
            "Fribourg",
            "FR",
            "CH",
            null,
            null,
            URI.create("https://example.test/spanning"),
            Set.of("concert"),
            null,
            EventStatus.CONFIRMED,
            List.of(
                new EventOccurrence(
                    Instant.parse("2026-02-28T19:00:00Z"),
                    Instant.parse("2026-03-02T19:00:00Z"),
                    false))),
        Instant.parse("2026-02-01T10:00:00Z"));
    flushAndClear();

    List<EventOccurrenceEntity> found =
        store.findOccurrencesOverlapping(
            Instant.parse("2026-03-01T00:00:00Z"), Instant.parse("2026-03-15T00:00:00Z"));

    assertThat(found)
        .extracting(o -> o.getEvent().getTitle())
        .containsExactly("Spanning", "Inside");
  }

  @Test
  void storesUtcTextFixedWidthAndLocalDateInDigestZone() {
    UpsertResult result =
        store.upsert(
            event(SourceId.INSITU, "i-1", "Late", occurrence("2026-03-01T23:30:00Z")),
            Instant.parse("2026-02-01T10:00:00.123Z"));
    flushAndClear();

    String createdAt =
        jdbc.queryForObject(
            "select created_at from event where id = ?", String.class, result.event().getId());
    String startUtc = jdbc.queryForObject("select start_utc from event_occurrence", String.class);
    String localDate = jdbc.queryForObject("select local_date from event_occurrence", String.class);

    assertThat(createdAt).isEqualTo("2026-02-01T10:00:00.123Z");
    assertThat(startUtc).isEqualTo("2026-03-01T23:30:00.000Z");
    assertThat(localDate).isEqualTo("2026-03-02");
  }

  @Test
  void deduplicatesRepeatedOccurrenceStartsInOnePayload() {
    SourceEvent input =
        event(
            SourceId.INSITU,
            "i-1",
            "Concert",
            occurrences("2026-03-01T19:00:00Z", "2026-03-01T19:00:00Z"));

    store.upsert(input, Instant.parse("2026-02-01T10:00:00Z"));
    flushAndClear();

    assertThat(occurrences.count()).isEqualTo(1);
  }

  @Test
  void attachesResolvedCandidateAndKeepsTheRicherSourceFields() {
    Instant firstSeen = Instant.parse("2026-02-01T10:00:00Z");
    Instant mergedSeen = Instant.parse("2026-02-02T10:00:00Z");
    SourceEvent insitu =
        event(SourceId.INSITU, "i-1", "Cinema Zero", occurrence("2026-03-01T19:00:00Z"));
    SourceEvent eventfrog =
        new SourceEvent(
            SourceId.EVENTFROG,
            "e-1",
            "Cinéma – Zéro!",
            "A much longer description from eventfrog",
            "Nouveau Monde",
            "Rue de la Gare 1",
            "Fribourg",
            "FR",
            "CH",
            BigDecimal.valueOf(46.8065),
            BigDecimal.valueOf(7.1610),
            URI.create("https://example.test/eventfrog/e-1"),
            Set.of("cinema"),
            "CHF 30 (reduced 20)",
            EventStatus.CONFIRMED,
            List.of(
                new EventOccurrence(Instant.parse("2026-03-01T19:00:00Z"), null, false),
                new EventOccurrence(Instant.parse("2026-03-02T19:00:00Z"), null, false)));

    UpsertResult first = store.upsert(insitu, firstSeen);
    UpsertResult merged = store.upsert(eventfrog, mergedSeen, first.event().getId());
    flushAndClear();

    assertThat(first.merged()).isFalse();
    assertThat(merged.eventCreated()).isFalse();
    assertThat(merged.sourceRefCreated()).isTrue();
    assertThat(merged.merged()).isTrue();
    assertThat(merged.event().getId()).isEqualTo(first.event().getId());
    assertThat(events.count()).isEqualTo(1);
    assertThat(sourceRefs.count()).isEqualTo(2);
    assertThat(occurrences.count()).isEqualTo(2);

    EventEntity event = events.findById(first.event().getId()).orElseThrow();
    assertThat(event.getDedupKey()).isEqualTo(first.event().getDedupKey());
    assertThat(event.getTitle()).isEqualTo("Cinema Zero");
    assertThat(event.getDescription()).isEqualTo("A much longer description from eventfrog");
    assertThat(event.getCategories()).isEqualTo("cinema\nconcert\ntheatre");
    assertThat(event.getPriceInfo()).isEqualTo("CHF 30 (reduced 20)");
    assertThat(event.getUrl()).isEqualTo("https://example.test/insitu/i-1");
    assertThat(event.getUpdatedAt()).isEqualTo(mergedSeen);
  }

  @Test
  void keepsThePreferredSourceUrlWhenANonPreferredSourceChangesItsPayload() {
    Instant firstSeen = Instant.parse("2026-02-01T10:00:00Z");
    Instant mergedSeen = Instant.parse("2026-02-02T10:00:00Z");
    Instant laterSeen = Instant.parse("2026-02-03T10:00:00Z");
    SourceEvent insitu =
        event(SourceId.INSITU, "i-1", "Concert", occurrence("2026-03-01T19:00:00Z"));
    SourceEvent eventfrog =
        event(SourceId.EVENTFROG, "e-1", "Concert", occurrence("2026-03-01T19:00:00Z"));
    UpsertResult first = store.upsert(insitu, firstSeen);
    store.upsert(eventfrog, mergedSeen, first.event().getId());

    SourceEvent eventfrogMoved =
        moved(
            SourceId.EVENTFROG,
            "e-1",
            "https://example.test/eventfrog/moved",
            "2026-03-01T20:00:00Z");
    store.upsert(eventfrogMoved, laterSeen);
    flushAndClear();

    assertThat(events.findById(first.event().getId()).orElseThrow().getUrl())
        .isEqualTo("https://example.test/insitu/i-1");

    SourceEvent insituMoved =
        moved(SourceId.INSITU, "i-1", "https://example.test/insitu/moved", "2026-03-01T21:00:00Z");
    store.upsert(insituMoved, laterSeen);
    flushAndClear();

    assertThat(events.findById(first.event().getId()).orElseThrow().getUrl())
        .isEqualTo("https://example.test/insitu/moved");
  }

  @Test
  void findsDedupCandidatesWithinTheFirstOccurrenceDateWindow() {
    store.upsert(
        event(SourceId.INSITU, "early", "Early", occurrence("2026-03-01T19:00:00Z")),
        Instant.parse("2026-02-01T10:00:00Z"));
    store.upsert(
        event(SourceId.INSITU, "late", "Late", occurrence("2026-03-10T19:00:00Z")),
        Instant.parse("2026-02-01T10:00:00Z"));
    flushAndClear();

    List<DedupCandidate> candidates =
        store.findDedupCandidates(
            event(SourceId.EVENTFROG, "probe", "Probe", occurrence("2026-03-02T19:00:00Z")), 1);

    assertThat(candidates).extracting(DedupCandidate::title).containsExactly("Early");
    assertThat(candidates.getFirst().occurrences()).hasSize(1);
    assertThat(candidates.getFirst().dedupKey()).hasSize(64);
  }

  @Test
  void rejectsInvalidWindow() {
    assertThatThrownBy(() ->
            store.findOccurrencesOverlapping(
                Instant.parse("2026-03-01T00:00:00Z"), Instant.parse("2026-03-01T00:00:00Z")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("from must be before to");
  }

  private void flushAndClear() {
    entityManager.flush();
    entityManager.clear();
  }

  private static SourceEvent event(
      SourceId source, String id, String title, EventOccurrence occurrence) {
    return event(source, id, title, List.of(occurrence));
  }

  private static SourceEvent event(
      SourceId source, String id, String title, List<EventOccurrence> occurrences) {
    return new SourceEvent(
        source,
        id,
        title,
        "Description",
        "Nouveau Monde",
        "Rue de la Gare 1",
        "Fribourg",
        "FR",
        "CH",
        BigDecimal.valueOf(46.8065),
        BigDecimal.valueOf(7.1610),
        URI.create("https://example.test/" + source.wireName() + "/" + id),
        Set.of("theatre", "concert"),
        "CHF 20",
        EventStatus.CONFIRMED,
        occurrences);
  }

  private static EventOccurrence occurrence(String start) {
    return new EventOccurrence(Instant.parse(start), null, false);
  }

  private static List<EventOccurrence> occurrences(String... starts) {
    return java.util.Arrays.stream(starts).map(EventStoreTest::occurrence).toList();
  }

  private static SourceEvent moved(SourceId source, String id, String url, String start) {
    return new SourceEvent(
        source,
        id,
        "Concert",
        "Description",
        "Nouveau Monde",
        "Rue de la Gare 1",
        "Fribourg",
        "FR",
        "CH",
        BigDecimal.valueOf(46.8065),
        BigDecimal.valueOf(7.1610),
        URI.create(url),
        Set.of("theatre", "concert"),
        "CHF 20",
        EventStatus.CONFIRMED,
        List.of(occurrence(start)));
  }
}
