package ch.fribourgevents.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class EventOccurrenceTest {

  private static final ZoneId ZURICH = ZoneId.of("Europe/Zurich");

  @Test
  void rejectsMissingStart() {
    assertThatThrownBy(() -> new EventOccurrence(null, null, false))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void rejectsEndBeforeStart() {
    Instant start = Instant.parse("2026-03-01T19:00:00Z");
    assertThatThrownBy(() -> new EventOccurrence(start, start.minusSeconds(1), false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("end must not be before start");
  }

  @Test
  void acceptsZeroLengthOccurrence() {
    Instant start = Instant.parse("2026-03-01T19:00:00Z");
    EventOccurrence occurrence = new EventOccurrence(start, start, false);
    assertThat(occurrence.end()).isEqualTo(start);
  }

  @Test
  void acceptsUnknownEndAndMapsLocalDate() {
    EventOccurrence occurrence =
        new EventOccurrence(Instant.parse("2026-03-01T23:30:00Z"), null, false);

    assertThat(occurrence.end()).isNull();
    assertThat(occurrence.localDate(ZURICH)).isEqualTo(LocalDate.of(2026, 3, 2));
  }
}
