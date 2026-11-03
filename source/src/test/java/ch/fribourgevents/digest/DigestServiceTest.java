package ch.fribourgevents.digest;

import static org.assertj.core.api.Assertions.assertThat;

import ch.fribourgevents.digest.DigestMessage.Day;
import ch.fribourgevents.digest.DigestMessage.Item;
import ch.fribourgevents.domain.EventOccurrence;
import ch.fribourgevents.domain.EventStatus;
import ch.fribourgevents.domain.FetchWindow;
import ch.fribourgevents.domain.SourceEvent;
import ch.fribourgevents.domain.SourceId;
import ch.fribourgevents.store.EventStore;
import ch.fribourgevents.store.SqliteTestDatabase;
import ch.fribourgevents.store.StoreConfiguration;
import ch.fribourgevents.store.StoreTestApplication;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Selection tests against a real temporary SQLite database: the digest reads occurrences through
 * the store's window query, so the ordering and the run-range behaviour are asserted end to end
 * rather than against mocks.
 */
@DataJpaTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "spring.flyway.enabled=true",
      "spring.datasource.hikari.maximum-pool-size=1",
      "spring.datasource.hikari.minimum-idle=1"
    })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = StoreTestApplication.class)
@Import({StoreConfiguration.class, EventStore.class, DigestService.class})
class DigestServiceTest {

  private static final ZoneId ZURICH = ZoneId.of("Europe/Zurich");
  private static final Instant FROM = Instant.parse("2026-11-03T00:00:00Z");
  private static final Instant TO = Instant.parse("2026-11-17T00:00:00Z");
  private static final FetchWindow WINDOW = new FetchWindow(FROM, TO, ZURICH);
  private static final Path DB_FILE = SqliteTestDatabase.create("digest");

  @DynamicPropertySource
  static void sqlite(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> SqliteTestDatabase.url(DB_FILE));
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add(
        "spring.jpa.database-platform", () -> "org.hibernate.community.dialect.SQLiteDialect");
  }

  @Autowired private EventStore store;
  @Autowired private DigestService service;

  @AfterAll
  static void deleteDatabase() {
    SqliteTestDatabase.delete(DB_FILE);
  }

  @Test
  void selectsInWindowOccurrencesOrderedByStartThenTitle() {
    upsert("z", "Zeta", occurrence("2026-11-05T20:00:00Z"));
    upsert("a", "Alpha", occurrence("2026-11-05T20:00:00Z"));
    upsert("c", "Charlie", occurrence("2026-11-06T08:00:00Z"));
    upsert("p", "Past", occurrence("2026-10-01T20:00:00Z"));

    DigestMessage message = service.select(WINDOW);

    assertThat(titles(message)).containsExactly("Alpha", "Zeta", "Charlie");
    assertThat(message.days())
        .extracting(Day::date)
        .containsExactly(LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 6));
    assertThat(message.itemCount()).isEqualTo(3);
    assertThat(message.omittedCount()).isZero();
    assertThat(message.isEmpty()).isFalse();
  }

  @Test
  void expandsOneMultiOccurrenceEventOncePerOccurrence() {
    Long eventId =
        upsert(
            "multi",
            "Theatre run",
            occurrence("2026-11-05T20:00:00Z"),
            occurrence("2026-11-12T20:00:00Z"));

    DigestMessage message = service.select(WINDOW);

    assertThat(message.itemCount()).isEqualTo(2);
    assertThat(message.days())
        .extracting(Day::date)
        .containsExactly(LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 12));
    assertThat(items(message))
        .allSatisfy(item -> assertThat(item.eventId()).isEqualTo(eventId))
        .extracting(Item::occurrenceId)
        .doesNotHaveDuplicates();
  }

  @Test
  void groupsByTheWindowZoneNotUtc() {
    // 23:30 UTC is already the next calendar day in Zurich.
    upsert("late", "Late show", occurrence("2026-11-05T23:30:00Z"));

    DigestMessage message = service.select(WINDOW);

    assertThat(message.days()).extracting(Day::date).containsExactly(LocalDate.of(2026, 11, 6));
  }

  @Test
  void capsItemsAndReportsHowManyWereOmitted() {
    upsert("one", "One", occurrence("2026-11-03T18:00:00Z"));
    upsert("two", "Two", occurrence("2026-11-04T18:00:00Z"));
    upsert("three", "Three", occurrence("2026-11-05T18:00:00Z"));
    upsert("four", "Four", occurrence("2026-11-06T18:00:00Z"));

    DigestMessage message = service.select(WINDOW, 2);

    assertThat(message.itemCount()).isEqualTo(2);
    assertThat(message.omittedCount()).isEqualTo(2);
    assertThat(titles(message)).containsExactly("One", "Two");
  }

  @Test
  void returnsAnEmptyMessageWhenNothingIntersectsTheWindow() {
    upsert("p", "Past", occurrence("2026-10-01T20:00:00Z"));

    DigestMessage message = service.select(WINDOW);

    assertThat(message.isEmpty()).isTrue();
    assertThat(message.days()).isEmpty();
    assertThat(message.omittedCount()).isZero();
  }

  @Test
  void mapsEventAndOccurrenceFieldsIntoItems() {
    upsert(
        "fields",
        "Field test",
        new EventOccurrence(
            Instant.parse("2026-11-04T23:00:00Z"), Instant.parse("2026-11-04T23:45:00Z"), true));

    Item item = items(service.select(WINDOW)).getFirst();

    assertThat(item.eventId()).isNotNull();
    assertThat(item.occurrenceId()).isNotNull();
    assertThat(item.title()).isEqualTo("Field test");
    assertThat(item.venueName()).isEqualTo("Nouveau Monde");
    assertThat(item.city()).isEqualTo("Fribourg");
    assertThat(item.url()).hasToString("https://example.test/fields");
    assertThat(item.allDay()).isTrue();
    assertThat(item.start()).isEqualTo(Instant.parse("2026-11-04T23:00:00Z"));
  }

  @Test
  void groupsEventsThatStartedBeforeTheWindowUnderTheFirstWindowDay() {
    upsert(
        "expo",
        "Ongoing expo",
        new EventOccurrence(
            Instant.parse("2026-05-26T08:00:00Z"), Instant.parse("2026-11-26T16:00:00Z"), false));

    DigestMessage message = service.select(WINDOW);
    Item item = items(message).getFirst();

    assertThat(message.days()).extracting(Day::date).containsExactly(LocalDate.of(2026, 11, 3));
    assertThat(item.start()).isEqualTo(Instant.parse("2026-05-26T08:00:00Z"));
  }

  @Test
  void treatsTheWindowEndAsExclusiveAndTheWindowStartAsInclusive() {
    upsert("start", "At start", occurrence(FROM.toString()));
    upsert("end", "At end", occurrence(TO.toString()));

    assertThat(titles(service.select(WINDOW))).containsExactly("At start");
  }

  private Long upsert(String sourceEventId, String title, EventOccurrence... occurrences) {
    return store
        .upsert(
            new SourceEvent(
                SourceId.INSITU,
                sourceEventId,
                title,
                "Description",
                "Nouveau Monde",
                "Rue de la Gare 1",
                "Fribourg",
                "FR",
                "CH",
                null,
                null,
                URI.create("https://example.test/" + sourceEventId),
                Set.of("concert"),
                null,
                EventStatus.CONFIRMED,
                List.of(occurrences)))
        .event()
        .getId();
  }

  private static EventOccurrence occurrence(String start) {
    return new EventOccurrence(Instant.parse(start), null, false);
  }

  private static List<Item> items(DigestMessage message) {
    return message.days().stream().flatMap(day -> day.items().stream()).toList();
  }

  private static List<String> titles(DigestMessage message) {
    return items(message).stream().map(Item::title).toList();
  }
}
