package ch.fribourgevents.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

/** Inclusive-from, exclusive-to time window with the zone used for calendar grouping. */
public record FetchWindow(Instant from, Instant to, ZoneId zone) {

  public FetchWindow {
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    Objects.requireNonNull(zone, "zone");
    if (!from.isBefore(to)) {
      throw new IllegalArgumentException("from must be before to");
    }
  }

  public LocalDate fromDate() {
    return from.atZone(zone).toLocalDate();
  }

  public LocalDate toDate() {
    return to.atZone(zone).toLocalDate();
  }
}
