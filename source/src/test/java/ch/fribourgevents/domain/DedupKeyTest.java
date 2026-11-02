package ch.fribourgevents.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DedupKeyTest {

  private static final ZoneId ZURICH = ZoneId.of("Europe/Zurich");

  @Test
  void producesStableHashForNormalizedIdentity() {
    SourceEvent event =
        event("Cinéma – Zéro!", "Nouveau Monde", "Fribourg", Instant.parse("2026-03-01T19:00:00Z"));

    assertThat(DedupKey.of(event, ZURICH))
        .isEqualTo("dfa706fd7111a39f7a930733de00d55a0d9cef1679277462a5a393d1a7d4af06");
  }

  @Test
  void ignoresCaseAccentsAndCityAliases() {
    SourceEvent accented =
        event("Cinéma Zéro", "Nouveau Monde", "Fribourg", Instant.parse("2026-03-01T19:00:00Z"));
    SourceEvent plain =
        event("cinema zero", "nouveau monde", "Freiburg", Instant.parse("2026-03-01T19:00:00Z"));

    assertThat(DedupKey.of(accented, ZURICH)).isEqualTo(DedupKey.of(plain, ZURICH));
  }

  @Test
  void changesWhenDateVenueOrCityChanges() {
    String baseline =
        DedupKey.of(
            event("Concert", "Nouveau Monde", "Fribourg", Instant.parse("2026-03-01T19:00:00Z")),
            ZURICH);

    assertThat(
            DedupKey.of(
                event(
                    "Concert", "Nouveau Monde", "Fribourg", Instant.parse("2026-03-02T19:00:00Z")),
                ZURICH))
        .isNotEqualTo(baseline);
    assertThat(
            DedupKey.of(
                event("Concert", "Autre Salle", "Fribourg", Instant.parse("2026-03-01T19:00:00Z")),
                ZURICH))
        .isNotEqualTo(baseline);
    assertThat(
            DedupKey.of(
                event("Concert", "Nouveau Monde", "Bulle", Instant.parse("2026-03-01T19:00:00Z")),
                ZURICH))
        .isNotEqualTo(baseline);
  }

  @Test
  void usesEarliestOccurrenceDate() {
    SourceEvent event =
        new SourceEvent(
            SourceId.INSITU,
            "id-1",
            "Festival",
            null,
            "Place",
            null,
            "Fribourg",
            "FR",
            "CH",
            null,
            null,
            null,
            Set.of(),
            null,
            EventStatus.CONFIRMED,
            List.of(
                new EventOccurrence(Instant.parse("2026-03-05T19:00:00Z"), null, false),
                new EventOccurrence(Instant.parse("2026-03-01T19:00:00Z"), null, false)));

    String fromEarliest = DedupKey.of("Festival", "Place", "Fribourg", LocalDate.of(2026, 3, 1));
    assertThat(DedupKey.of(event, ZURICH)).isEqualTo(fromEarliest);
  }

  private static SourceEvent event(String title, String venue, String city, Instant start) {
    return new SourceEvent(
        SourceId.INSITU,
        "id-1",
        title,
        null,
        venue,
        null,
        city,
        "FR",
        "CH",
        null,
        null,
        null,
        Set.of(),
        null,
        EventStatus.CONFIRMED,
        List.of(new EventOccurrence(start, null, false)));
  }
}
