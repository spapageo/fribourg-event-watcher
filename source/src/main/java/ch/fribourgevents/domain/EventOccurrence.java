package ch.fribourgevents.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

/**
 * One dated showing of an event. {@code end} is unknown for many upstream events and may be {@code
 * null}.
 */
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
