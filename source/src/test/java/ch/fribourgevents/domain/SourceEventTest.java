package ch.fribourgevents.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SourceEventTest {

  private static final URI URL = URI.create("https://example.test/events/1");
  private static final Instant START = Instant.parse("2026-03-01T19:00:00Z");

  @Test
  void rejectsBlankIdentityAndTitle() {
    assertThatThrownBy(() -> event(" ", "id-1")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> event("Concert", " ")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> event(null, "id-1")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> event("Concert", null)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void requiresAtLeastOneOccurrence() {
    assertThatThrownBy(() ->
            new SourceEvent(
                SourceId.INSITU,
                "id-1",
                "Concert",
                null,
                null,
                null,
                "Fribourg",
                "FR",
                null,
                null,
                null,
                URL,
                null,
                null,
                null,
                List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at least one occurrence");
  }

  @Test
  void appliesDefaults() {
    SourceEvent event =
        new SourceEvent(
            SourceId.INSITU,
            "id-1",
            "Concert",
            null,
            null,
            null,
            "Fribourg",
            "FR",
            " ",
            null,
            null,
            URL,
            null,
            null,
            null,
            List.of(occurrence()));

    assertThat(event.country()).isEqualTo("CH");
    assertThat(event.status()).isEqualTo(EventStatus.UNKNOWN);
    assertThat(event.categories()).isEmpty();
  }

  @Test
  void copiesCollectionsDefensively() {
    List<EventOccurrence> occurrences = new ArrayList<>(List.of(occurrence()));
    Set<String> categories = new HashSet<>(Set.of("concert"));
    SourceEvent event =
        new SourceEvent(
            SourceId.INSITU,
            "id-1",
            "Concert",
            null,
            "Nouveau Monde",
            null,
            "Fribourg",
            "FR",
            "CH",
            null,
            null,
            URL,
            categories,
            null,
            EventStatus.CONFIRMED,
            occurrences);

    occurrences.clear();
    categories.add("theatre");

    assertThat(event.occurrences()).hasSize(1);
    assertThat(event.categories()).containsExactly("concert");
  }

  @Test
  void rejectsNullOccurrences() {
    assertThatThrownBy(() ->
            new SourceEvent(
                SourceId.INSITU,
                "id-1",
                "Concert",
                null,
                null,
                null,
                "Fribourg",
                "FR",
                "CH",
                null,
                null,
                URL,
                null,
                null,
                null,
                null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at least one occurrence");
  }

  @Test
  void validatesCoordinates() {
    assertThatThrownBy(() ->
            new SourceEvent(
                SourceId.INSITU,
                "id-1",
                "Concert",
                null,
                null,
                null,
                "Fribourg",
                "FR",
                "CH",
                BigDecimal.valueOf(91),
                null,
                URL,
                null,
                null,
                null,
                List.of(occurrence())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("latitude");

    assertThatThrownBy(() ->
            new SourceEvent(
                SourceId.INSITU,
                "id-1",
                "Concert",
                null,
                null,
                null,
                "Fribourg",
                "FR",
                "CH",
                BigDecimal.valueOf(-91),
                null,
                URL,
                null,
                null,
                null,
                List.of(occurrence())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("latitude");

    assertThatThrownBy(() ->
            new SourceEvent(
                SourceId.INSITU,
                "id-1",
                "Concert",
                null,
                null,
                null,
                "Fribourg",
                "FR",
                "CH",
                null,
                BigDecimal.valueOf(-181),
                URL,
                null,
                null,
                null,
                List.of(occurrence())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("longitude");

    assertThatThrownBy(() ->
            new SourceEvent(
                SourceId.INSITU,
                "id-1",
                "Concert",
                null,
                null,
                null,
                "Fribourg",
                "FR",
                "CH",
                null,
                BigDecimal.valueOf(181),
                URL,
                null,
                null,
                null,
                List.of(occurrence())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("longitude");
  }

  private static SourceEvent event(String title, String sourceEventId) {
    return new SourceEvent(
        SourceId.INSITU,
        sourceEventId,
        title,
        "Description",
        "Nouveau Monde",
        "Rue de la Gare 1",
        "Fribourg",
        "FR",
        "CH",
        BigDecimal.valueOf(46.8065),
        BigDecimal.valueOf(7.1610),
        URL,
        Set.of("concert"),
        "CHF 20",
        EventStatus.CONFIRMED,
        List.of(occurrence()));
  }

  private static EventOccurrence occurrence() {
    return new EventOccurrence(START, null, false);
  }
}
