package ch.fribourgevents.dedup;

import ch.fribourgevents.dedup.DedupMatcher.Candidate;
import ch.fribourgevents.domain.SourceEvent;
import ch.fribourgevents.store.DedupCandidate;
import ch.fribourgevents.store.EventStore;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Joins the pure {@link DedupMatcher} to the stored candidates: pulls the blocked candidate set
 * from the store, runs the match, and returns the canonical event id to attach the source record
 * to.
 *
 * <p>Lives in {@code dedup} rather than {@code store} so the dependency stays {@code dedup → store}
 * as the capability map requires; the store keeps owning writes and knows nothing about scoring.
 * Resolution is read-only and runs once per incoming source event, inside the pipeline's upsert
 * loop.
 */
public final class DedupResolver {

  private final EventStore store;
  private final DedupMatcher matcher;
  private final ZoneId zone;

  public DedupResolver(EventStore store, DedupMatcher matcher, ZoneId zone) {
    this.store = Objects.requireNonNull(store, "store");
    this.matcher = Objects.requireNonNull(matcher, "matcher");
    this.zone = Objects.requireNonNull(zone, "zone");
  }

  /**
   * Returns the canonical event {@code incoming} belongs to, empty when it is a new event.
   *
   * <p>An exact stored dedup key wins; otherwise the matcher applies its city/date blocking and
   * title/venue thresholds. The id travels by dedup key because keys are unique and never
   * rewritten.
   */
  public Optional<Long> findCanonicalEventId(SourceEvent incoming) {
    Objects.requireNonNull(incoming, "incoming");
    List<DedupCandidate> stored = store.findDedupCandidates(incoming, matcher.dateWindowDays());
    if (stored.isEmpty()) {
      return Optional.empty();
    }

    Map<String, Long> idsByKey =
        stored.stream()
            .collect(
                Collectors.toMap(
                    DedupCandidate::dedupKey, DedupCandidate::eventId, (left, right) -> left));
    List<Candidate> candidates = stored.stream().map(DedupResolver::toCandidate).toList();

    MatchDecision decision = matcher.match(incoming, candidates, zone);
    return decision.matchedCandidate().map(Candidate::dedupKey).map(idsByKey::get);
  }

  private static Candidate toCandidate(DedupCandidate stored) {
    return new Candidate(
        stored.dedupKey(), stored.title(), stored.venueName(), stored.city(), stored.occurrences());
  }
}
