package ch.fribourgevents.domain;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Canonical event as produced by an upstream adapter.
 *
 * <p>Required: {@code source}, {@code sourceEventId}, {@code title}, and at least one occurrence.
 * Optional fields are nullable and documented per component.
 */
public record SourceEvent(
    SourceId source,
    String sourceEventId,
    String title,
    String description,
    String venueName,
    String addressLine,
    String city,
    String canton,
    String country,
    BigDecimal latitude,
    BigDecimal longitude,
    URI url,
    Set<String> categories,
    String priceInfo,
    EventStatus status,
    List<EventOccurrence> occurrences) {

  private static final BigDecimal MIN_LATITUDE = BigDecimal.valueOf(-90);
  private static final BigDecimal MAX_LATITUDE = BigDecimal.valueOf(90);
  private static final BigDecimal MIN_LONGITUDE = BigDecimal.valueOf(-180);
  private static final BigDecimal MAX_LONGITUDE = BigDecimal.valueOf(180);

  public SourceEvent {
    Objects.requireNonNull(source, "source");
    sourceEventId = requireNonBlank(sourceEventId, "sourceEventId");
    title = requireNonBlank(title, "title");
    if (latitude != null
        && (latitude.compareTo(MIN_LATITUDE) < 0 || latitude.compareTo(MAX_LATITUDE) > 0)) {
      throw new IllegalArgumentException("latitude out of range: " + latitude);
    }
    if (longitude != null
        && (longitude.compareTo(MIN_LONGITUDE) < 0 || longitude.compareTo(MAX_LONGITUDE) > 0)) {
      throw new IllegalArgumentException("longitude out of range: " + longitude);
    }
    country = (country == null || country.isBlank()) ? "CH" : country;
    status = status == null ? EventStatus.UNKNOWN : status;
    categories = categories == null ? Set.of() : Set.copyOf(categories);
    occurrences = occurrences == null ? List.of() : List.copyOf(occurrences);
    if (occurrences.isEmpty()) {
      throw new IllegalArgumentException("at least one occurrence is required");
    }
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
