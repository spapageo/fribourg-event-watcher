package ch.fribourgevents.dedup;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Fuzzy-match thresholds, bound from {@code digest.dedup.*}.
 *
 * <p>Title similarity is Jaro-Winkler on normalized text. A candidate must clear {@code
 * title-threshold}; when both sides have a venue it must also clear {@code venue-threshold}. An
 * event with no venue on either side passes the venue gate ("venue empty-or-similar"). Candidate
 * blocking also requires the same normalized city and a first occurrence within {@code
 * date-window-days} days.
 *
 * <p>Defaults follow the spec: 0.90 title, 0.85 venue, ±1 day. Every value is validated here so a
 * bad env var fails startup with a message naming the key.
 *
 * @param titleThreshold minimum normalized title Jaro-Winkler score (0..1)
 * @param venueThreshold minimum normalized venue Jaro-Winkler score (0..1), when both are present
 * @param dateWindowDays maximum day difference between the first occurrences, inclusive
 */
@ConfigurationProperties(prefix = "digest.dedup")
public record DedupProperties(
    Double titleThreshold, Double venueThreshold, Integer dateWindowDays) {

  public static final double DEFAULT_TITLE_THRESHOLD = 0.90;
  public static final double DEFAULT_VENUE_THRESHOLD = 0.85;
  public static final int DEFAULT_DATE_WINDOW_DAYS = 1;

  public DedupProperties {
    titleThreshold = titleThreshold == null ? DEFAULT_TITLE_THRESHOLD : titleThreshold;
    venueThreshold = venueThreshold == null ? DEFAULT_VENUE_THRESHOLD : venueThreshold;
    dateWindowDays = dateWindowDays == null ? DEFAULT_DATE_WINDOW_DAYS : dateWindowDays;

    requireThreshold(titleThreshold, "title-threshold");
    requireThreshold(venueThreshold, "venue-threshold");
    if (dateWindowDays < 0) {
      throw new IllegalArgumentException(
          "digest.dedup.date-window-days must not be negative, was " + dateWindowDays);
    }
  }

  private static void requireThreshold(double value, String name) {
    if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
      throw new IllegalArgumentException(
          "digest.dedup." + name + " must be between 0 and 1, was " + value);
    }
  }
}
