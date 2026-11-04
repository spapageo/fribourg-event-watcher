package ch.fribourgevents.dedup;

import ch.fribourgevents.domain.DedupKey;
import ch.fribourgevents.domain.EventOccurrence;
import ch.fribourgevents.domain.Normalizer;
import ch.fribourgevents.domain.SourceEvent;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure cross-source matcher: exact key first, then Jaro-Winkler title/venue similarity inside a
 * strictly blocked candidate set.
 *
 * <p>Exact matching uses the stored {@link Candidate#dedupKey()} rather than recomputing a key from
 * the candidate's current fields: a canonical event's key is created once and never rewritten, even
 * after a merge changes its title or venue. Fuzzy matching is only attempted when
 *
 * <ul>
 *   <li>the normalized city is the same (an empty city never fuzzily matches),
 *   <li>the first occurrences' local dates are within {@code date-window-days} (default ±1), and
 *   <li>the normalized title clears the configured Jaro-Winkler threshold; when both venues exist,
 *       the venue must clear its threshold too. A missing venue passes the venue gate.
 * </ul>
 *
 * <p>Those blocks are what keep a recurring series on different dates, and the same title at
 * different venues, from being merged. The combined fuzzy score is the mean of the title and venue
 * scores when both venues exist, and the title score alone when either venue is missing. The
 * matcher owns no clock, database, or mutable state; the caller supplies the zone and candidates.
 */
public final class DedupMatcher {

  private final DedupProperties properties;

  /** Production matcher with spec defaults (0.90 title, 0.85 venue, ±1 day). */
  public DedupMatcher() {
    this(new DedupProperties(null, null, null));
  }

  public DedupMatcher(DedupProperties properties) {
    this.properties = Objects.requireNonNull(properties, "properties");
  }

  /**
   * Decides whether {@code incoming} belongs to one of the canonical {@code candidates}.
   *
   * @param incoming a source event that does not yet have a canonical event
   * @param candidates canonical events already stored; order does not affect the result
   * @param zone the digest zone used for local dates
   */
  public MatchDecision match(SourceEvent incoming, List<Candidate> candidates, ZoneId zone) {
    Objects.requireNonNull(incoming, "incoming");
    Objects.requireNonNull(candidates, "candidates");
    Objects.requireNonNull(zone, "zone");

    String incomingKey = DedupKey.of(incoming, zone);
    for (Candidate candidate : candidates) {
      if (incomingKey.equals(candidate.dedupKey())) {
        return MatchDecision.exact(candidate);
      }
    }

    String incomingCity = Normalizer.city(incoming.city());
    String incomingTitle = Normalizer.title(incoming.title());
    if (incomingCity.isEmpty() || incomingTitle.isEmpty()) {
      return MatchDecision.none();
    }
    String incomingVenue = Normalizer.venue(incoming.venueName());
    LocalDate incomingDate = firstDate(incoming.occurrences(), zone);

    Candidate best = null;
    double bestTitleScore = 0.0;
    double bestVenueScore = 0.0;
    double bestScore = 0.0;
    for (Candidate candidate : candidates) {
      if (!incomingCity.equals(Normalizer.city(candidate.city()))) {
        continue;
      }
      LocalDate candidateDate =
          candidate
              .firstOccurrence()
              .orElseThrow(() -> new IllegalArgumentException("candidate has no occurrences"))
              .localDate(zone);
      long dayDistance = Math.abs(ChronoUnit.DAYS.between(incomingDate, candidateDate));
      if (dayDistance > properties.dateWindowDays()) {
        continue;
      }

      double titleScore = jaroWinkler(incomingTitle, Normalizer.title(candidate.title()));
      if (titleScore < properties.titleThreshold()) {
        continue;
      }

      String candidateVenue = Normalizer.venue(candidate.venueName());
      double venueScore;
      double score;
      if (incomingVenue.isEmpty() || candidateVenue.isEmpty()) {
        venueScore = 1.0;
        score = titleScore;
      } else {
        venueScore = jaroWinkler(incomingVenue, candidateVenue);
        if (venueScore < properties.venueThreshold()) {
          continue;
        }
        score = (titleScore + venueScore) / 2.0;
      }

      if (isBetter(
          candidate,
          titleScore,
          venueScore,
          score,
          best,
          bestTitleScore,
          bestVenueScore,
          bestScore)) {
        best = candidate;
        bestTitleScore = titleScore;
        bestVenueScore = venueScore;
        bestScore = score;
      }
    }

    return best == null
        ? MatchDecision.none()
        : MatchDecision.fuzzy(best, bestTitleScore, bestVenueScore, bestScore);
  }

  /** The earliest occurrence's local date; events always carry at least one occurrence. */
  private static LocalDate firstDate(List<EventOccurrence> occurrences, ZoneId zone) {
    return occurrences.stream()
        .min((left, right) -> left.start().compareTo(right.start()))
        .orElseThrow(() -> new IllegalArgumentException("event has no occurrences"))
        .localDate(zone);
  }

  private static boolean isBetter(
      Candidate candidate,
      double titleScore,
      double venueScore,
      double score,
      Candidate best,
      double bestTitleScore,
      double bestVenueScore,
      double bestScore) {
    if (best == null) {
      return true;
    }
    int comparison = Double.compare(score, bestScore);
    if (comparison != 0) {
      return comparison > 0;
    }
    comparison = Double.compare(titleScore, bestTitleScore);
    if (comparison != 0) {
      return comparison > 0;
    }
    comparison = Double.compare(venueScore, bestVenueScore);
    if (comparison != 0) {
      return comparison > 0;
    }
    return candidate.dedupKey().compareTo(best.dedupKey()) < 0;
  }

  /**
   * Jaro-Winkler similarity on already-normalized strings. Standard prefix scaling (up to four
   * characters, 0.1) applies only above the usual 0.7 Jaro boost threshold.
   */
  static double jaroWinkler(String left, String right) {
    String first = left == null ? "" : left;
    String second = right == null ? "" : right;
    if (first.equals(second)) {
      return 1.0;
    }
    if (first.isEmpty() || second.isEmpty()) {
      return 0.0;
    }

    int firstLength = first.length();
    int secondLength = second.length();
    int matchDistance = Math.max(0, Math.max(firstLength, secondLength) / 2 - 1);
    boolean[] firstMatches = new boolean[firstLength];
    boolean[] secondMatches = new boolean[secondLength];

    int matches = 0;
    for (int i = 0; i < firstLength; i++) {
      int start = Math.max(0, i - matchDistance);
      int end = Math.min(i + matchDistance + 1, secondLength);
      for (int j = start; j < end; j++) {
        if (secondMatches[j] || first.charAt(i) != second.charAt(j)) {
          continue;
        }
        firstMatches[i] = true;
        secondMatches[j] = true;
        matches++;
        break;
      }
    }
    if (matches == 0) {
      return 0.0;
    }

    int mismatches = 0;
    int secondIndex = 0;
    for (int i = 0; i < firstLength; i++) {
      if (!firstMatches[i]) {
        continue;
      }
      while (!secondMatches[secondIndex]) {
        secondIndex++;
      }
      if (first.charAt(i) != second.charAt(secondIndex)) {
        mismatches++;
      }
      secondIndex++;
    }

    double jaro =
        ((double) matches / firstLength
                + (double) matches / secondLength
                + ((double) matches - mismatches / 2.0) / matches)
            / 3.0;
    if (jaro <= 0.7) {
      return Math.min(1.0, jaro);
    }

    int prefixLength = 0;
    int maxPrefix = Math.min(4, Math.min(firstLength, secondLength));
    while (prefixLength < maxPrefix && first.charAt(prefixLength) == second.charAt(prefixLength)) {
      prefixLength++;
    }
    return Math.min(1.0, jaro + prefixLength * 0.1 * (1.0 - jaro));
  }

  /**
   * A canonical event the incoming source event may belong to. {@code dedupKey} is the stored event
   * key (created once, never recomputed); the remaining fields are what fuzzy blocking and scoring
   * need.
   */
  public record Candidate(
      String dedupKey,
      String title,
      String venueName,
      String city,
      List<EventOccurrence> occurrences) {

    public Candidate {
      dedupKey = requireNonBlank(dedupKey, "dedupKey");
      title = requireNonBlank(title, "title");
      venueName = trimToNull(venueName);
      city = trimToNull(city);
      occurrences = List.copyOf(Objects.requireNonNull(occurrences, "occurrences"));
      if (occurrences.isEmpty()) {
        throw new IllegalArgumentException("at least one occurrence is required");
      }
    }

    /** Candidate backed by a source event that has not been stored yet (tests, fixtures). */
    public static Candidate of(SourceEvent event, ZoneId zone) {
      Objects.requireNonNull(event, "event");
      Objects.requireNonNull(zone, "zone");
      return new Candidate(
          DedupKey.of(event, zone),
          event.title(),
          event.venueName(),
          event.city(),
          event.occurrences());
    }

    public Optional<EventOccurrence> firstOccurrence() {
      return occurrences.stream().min((left, right) -> left.start().compareTo(right.start()));
    }

    private static String requireNonBlank(String value, String name) {
      String text = trimToNull(value);
      if (text == null) {
        throw new IllegalArgumentException(name + " must not be blank");
      }
      return text;
    }

    private static String trimToNull(String value) {
      if (value == null) {
        return null;
      }
      String trimmed = value.strip();
      return trimmed.isEmpty() ? null : trimmed;
    }
  }
}
