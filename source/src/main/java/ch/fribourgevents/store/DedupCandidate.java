package ch.fribourgevents.store;

import ch.fribourgevents.domain.EventOccurrence;
import java.util.List;
import java.util.Objects;

/**
 * A stored canonical event offered to the cross-source matcher.
 *
 * <p>Deliberately store-local: the {@code dedup} module maps this to its own candidate type, so
 * {@code store} never depends on matching logic. {@code dedupKey} is the key the event was created
 * with and must not be recomputed from the mutable fields below (a merge may have changed the
 * title, venue, or occurrences since).
 *
 * @param eventId primary key of the canonical event
 * @param dedupKey stored exact-match key, never rewritten
 * @param title current canonical title
 * @param venueName current venue, nullable
 * @param city current city, nullable
 * @param occurrences every stored occurrence of the event, never empty
 */
public record DedupCandidate(
    Long eventId,
    String dedupKey,
    String title,
    String venueName,
    String city,
    List<EventOccurrence> occurrences) {

  public DedupCandidate {
    Objects.requireNonNull(eventId, "eventId");
    dedupKey = requireNonBlank(dedupKey, "dedupKey");
    title = requireNonBlank(title, "title");
    venueName = blankToNull(venueName);
    city = blankToNull(city);
    occurrences = List.copyOf(Objects.requireNonNull(occurrences, "occurrences"));
    if (occurrences.isEmpty()) {
      throw new IllegalArgumentException("at least one occurrence is required");
    }
  }

  private static String requireNonBlank(String value, String name) {
    String text = blankToNull(value);
    if (text == null) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return text;
  }

  private static String blankToNull(String value) {
    if (value == null) {
      return null;
    }
    String stripped = value.strip();
    return stripped.isEmpty() ? null : stripped;
  }
}
