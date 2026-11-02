package ch.fribourgevents.domain;

import java.util.Locale;

/** Publication status as reported upstream. Unknown values degrade to {@link #UNKNOWN}. */
public enum EventStatus {
  CONFIRMED,
  TENTATIVE,
  CANCELLED,
  UNKNOWN;

  public static EventStatus fromWire(String value) {
    if (value == null || value.isBlank()) {
      return UNKNOWN;
    }
    return switch (value.strip().toLowerCase(Locale.ROOT)) {
      case "confirmed", "bestätigt", "confirme", "confirmé" -> CONFIRMED;
      case "tentative", "vorläufig" -> TENTATIVE;
      case "cancelled", "canceled", "abgesagt", "annule", "annulé" -> CANCELLED;
      default -> UNKNOWN;
    };
  }
}
