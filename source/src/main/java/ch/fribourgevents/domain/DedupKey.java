package ch.fribourgevents.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Exact-match identity of an event, derived from normalized title, venue, city, and the local date
 * of its first occurrence.
 */
public final class DedupKey {

  private DedupKey() {}

  public static String of(SourceEvent event, ZoneId zone) {
    Objects.requireNonNull(event, "event");
    Objects.requireNonNull(zone, "zone");
    EventOccurrence first =
        event.occurrences().stream()
            .min(Comparator.comparing(EventOccurrence::start))
            .orElseThrow(() -> new IllegalArgumentException("event has no occurrences"));
    return of(event.title(), event.venueName(), event.city(), first.localDate(zone));
  }

  static String of(String title, String venue, String city, LocalDate date) {
    Objects.requireNonNull(date, "date");
    String identity =
        String.join(
            "|",
            Normalizer.title(title),
            Normalizer.venue(venue),
            Normalizer.city(city),
            date.toString());
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(identity.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
