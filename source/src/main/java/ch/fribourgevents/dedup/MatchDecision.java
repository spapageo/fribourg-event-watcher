package ch.fribourgevents.dedup;

import java.util.Objects;
import java.util.Optional;

/**
 * Result of one {@link DedupMatcher} decision.
 *
 * <p>{@link MatchType#EXACT} means the stored dedup key matched and no fuzzy score was needed.
 * {@link MatchType#FUZZY} carries the title/venue Jaro-Winkler scores and their combined score.
 * {@link MatchType#NONE} carries no candidate and zero scores.
 *
 * @param type how the decision was reached
 * @param candidate the matched canonical candidate, or {@code null} for a non-match
 * @param titleScore normalized-title Jaro-Winkler score (1.0 for an exact match)
 * @param venueScore normalized-venue Jaro-Winkler score, or 1.0 when either venue is absent
 * @param score combined score used to rank fuzzy candidates
 */
public record MatchDecision(
    MatchType type,
    DedupMatcher.Candidate candidate,
    double titleScore,
    double venueScore,
    double score) {

  public MatchDecision {
    Objects.requireNonNull(type, "type");
    if (type == MatchType.NONE) {
      if (candidate != null) {
        throw new IllegalArgumentException("a non-match must not carry a candidate");
      }
      if (titleScore != 0.0 || venueScore != 0.0 || score != 0.0) {
        throw new IllegalArgumentException("a non-match must not carry scores");
      }
    } else {
      Objects.requireNonNull(candidate, "candidate");
      requireScore(titleScore, "titleScore");
      requireScore(venueScore, "venueScore");
      requireScore(score, "score");
    }
  }

  public boolean matched() {
    return type != MatchType.NONE;
  }

  /** The matched candidate, empty for {@link MatchType#NONE}. */
  public Optional<DedupMatcher.Candidate> matchedCandidate() {
    return Optional.ofNullable(candidate);
  }

  static MatchDecision exact(DedupMatcher.Candidate candidate) {
    return new MatchDecision(MatchType.EXACT, candidate, 1.0, 1.0, 1.0);
  }

  static MatchDecision fuzzy(
      DedupMatcher.Candidate candidate, double titleScore, double venueScore, double score) {
    return new MatchDecision(MatchType.FUZZY, candidate, titleScore, venueScore, score);
  }

  static MatchDecision none() {
    return new MatchDecision(MatchType.NONE, null, 0.0, 0.0, 0.0);
  }

  private static void requireScore(double value, String name) {
    if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
      throw new IllegalArgumentException(name + " must be between 0 and 1, was " + value);
    }
  }

  /** How the decision was reached. */
  public enum MatchType {
    EXACT,
    FUZZY,
    NONE
  }
}
