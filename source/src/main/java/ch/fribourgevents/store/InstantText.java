package ch.fribourgevents.store;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** Fixed-width ISO-8601 UTC text used for every persisted timestamp. */
public final class InstantText {

  public static final DateTimeFormatter FORMAT =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  private InstantText() {}

  public static String format(Instant instant) {
    return FORMAT.format(instant);
  }

  public static Instant parse(String text) {
    try {
      return Instant.from(FORMAT.parse(text));
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException("Not an ISO-8601 UTC timestamp: " + text, e);
    }
  }
}
