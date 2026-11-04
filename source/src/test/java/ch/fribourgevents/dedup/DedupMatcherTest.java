package ch.fribourgevents.dedup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import ch.fribourgevents.domain.EventOccurrence;
import ch.fribourgevents.domain.EventStatus;
import ch.fribourgevents.domain.SourceEvent;
import ch.fribourgevents.domain.SourceId;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pure matcher tests. Cases mirror real cross-source duplicates observed in the saved insitu, bka
 * and eventfrog fixtures (same production, different punctuation/venue spelling/date-by-a-day) and
 * the false-merge traps: recurring series, different venues and different cities.
 */
class DedupMatcherTest {

  private static final ZoneId ZURICH = ZoneId.of("Europe/Zurich");

  @Test
  void exactDedupKeyWinsBeforeFuzzyScoring() {
    DedupMatcher matcher = new DedupMatcher();
    SourceEvent incoming =
        event(
            SourceId.INSITU,
            "insitu-1",
            "Das Fräulein von Scuderi",
            "Kellerpoche",
            "Fribourg",
            "2026-11-06T19:15:00Z");
    DedupMatcher.Candidate fuzzy =
        DedupMatcher.Candidate.of(
            event(
                SourceId.BKA,
                "bka-1",
                "Das Fräulein von Scuderi",
                "Kellerpoche, Theater am Stalden",
                "Fribourg",
                "2026-11-07T19:15:00Z"),
            ZURICH);
    DedupMatcher.Candidate exact =
        DedupMatcher.Candidate.of(
            event(
                SourceId.EVENTFROG,
                "eventfrog-1",
                "Das Fräulein von Scuderi",
                "Kellerpoche",
                "Fribourg",
                "2026-11-06T19:15:00Z"),
            ZURICH);

    MatchDecision decision = matcher.match(incoming, List.of(fuzzy, exact), ZURICH);

    assertThat(decision.type()).isEqualTo(MatchDecision.MatchType.EXACT);
    assertThat(decision.candidate()).isEqualTo(exact);
    assertThat(decision.titleScore()).isEqualTo(1.0);
    assertThat(decision.venueScore()).isEqualTo(1.0);
    assertThat(decision.score()).isEqualTo(1.0);
  }

  @Test
  void fuzzyMatchesAccentAndPunctuationVariantsOnAdjacentDays() {
    DedupMatcher matcher = new DedupMatcher();
    SourceEvent incoming =
        event(
            SourceId.INSITU,
            "insitu-2",
            "Cinéma – Zéro!",
            "Nouveau Monde",
            "Fribourg",
            "2026-11-06T19:30:00Z");
    DedupMatcher.Candidate candidate =
        DedupMatcher.Candidate.of(
            event(
                SourceId.EVENTFROG,
                "eventfrog-2",
                "Cinema Zero",
                "Nouveau Monde",
                "Fribourg",
                "2026-11-07T19:30:00Z"),
            ZURICH);

    MatchDecision decision = matcher.match(incoming, List.of(candidate), ZURICH);

    assertThat(decision.type()).isEqualTo(MatchDecision.MatchType.FUZZY);
    assertThat(decision.candidate()).isEqualTo(candidate);
    assertThat(decision.titleScore()).isEqualTo(1.0);
    assertThat(decision.venueScore()).isEqualTo(1.0);
    assertThat(decision.score()).isEqualTo(1.0);
  }

  @Test
  void fuzzyMatchesAnAbbreviatedVenue() {
    DedupMatcher matcher = new DedupMatcher();
    SourceEvent incoming =
        event(
            SourceId.BKA,
            "bka-3",
            "Das Fräulein von Scuderi",
            "Kellerpoche",
            "Fribourg",
            "2026-11-06T19:15:00Z");
    DedupMatcher.Candidate candidate =
        DedupMatcher.Candidate.of(
            event(
                SourceId.EVENTFROG,
                "eventfrog-3",
                "Das Fräulein von Scuderi",
                "Kellerpoche, Theater am Stalden",
                "Fribourg",
                "2026-11-07T19:15:00Z"),
            ZURICH);

    MatchDecision decision = matcher.match(incoming, List.of(candidate), ZURICH);

    assertThat(decision.type()).isEqualTo(MatchDecision.MatchType.FUZZY);
    assertThat(decision.titleScore()).isEqualTo(1.0);
    assertThat(decision.venueScore()).isBetween(0.85, 1.0);
  }

  @Test
  void acceptsAMissingVenueAsVenueEmptyOrSimilar() {
    DedupMatcher matcher = new DedupMatcher();
    SourceEvent incoming =
        event(
            SourceId.INSITU,
            "insitu-4",
            "Fête de la musique",
            null,
            "Fribourg",
            "2026-11-06T19:30:00Z");
    DedupMatcher.Candidate candidate =
        DedupMatcher.Candidate.of(
            event(
                SourceId.EVENTFROG,
                "eventfrog-4",
                "Fete de la musique",
                "Place de la Cathédrale",
                "Fribourg",
                "2026-11-06T20:00:00Z"),
            ZURICH);

    MatchDecision decision = matcher.match(incoming, List.of(candidate), ZURICH);

    assertThat(decision.type()).isEqualTo(MatchDecision.MatchType.FUZZY);
    assertThat(decision.titleScore()).isEqualTo(1.0);
    assertThat(decision.venueScore()).isEqualTo(1.0);
  }

  @Test
  void neverMergesTheSameTitleAtDifferentVenues() {
    DedupMatcher matcher = new DedupMatcher();
    SourceEvent incoming =
        event(
            SourceId.INSITU,
            "insitu-5",
            "Nuit du jazz",
            "Kellerpoche",
            "Fribourg",
            "2026-11-06T19:30:00Z");
    DedupMatcher.Candidate candidate =
        DedupMatcher.Candidate.of(
            event(
                SourceId.EVENTFROG,
                "eventfrog-5",
                "Nuit du jazz",
                "Espace Jean Tinguely",
                "Fribourg",
                "2026-11-06T19:30:00Z"),
            ZURICH);

    MatchDecision decision = matcher.match(incoming, List.of(candidate), ZURICH);

    assertThat(decision.matched()).isFalse();
    assertThat(decision.type()).isEqualTo(MatchDecision.MatchType.NONE);
  }

  @Test
  void neverMergesARecurringSeriesOnDifferentDates() {
    DedupMatcher matcher = new DedupMatcher();
    SourceEvent incoming =
        event(
            SourceId.INSITU,
            "insitu-6",
            "Karaoke Donnerstag",
            "Bäregrotte Bar",
            "Bern",
            "2026-11-05T19:30:00Z");
    DedupMatcher.Candidate candidate =
        DedupMatcher.Candidate.of(
            event(
                SourceId.EVENTFROG,
                "eventfrog-6",
                "Karaoke Donnerstag",
                "Bäregrotte Bar",
                "Bern",
                "2026-11-19T19:30:00Z"),
            ZURICH);

    MatchDecision decision = matcher.match(incoming, List.of(candidate), ZURICH);

    assertThat(decision.type()).isEqualTo(MatchDecision.MatchType.NONE);
  }

  @Test
  void neverMergesCandidatesFromDifferentCities() {
    DedupMatcher matcher = new DedupMatcher();
    SourceEvent incoming =
        event(
            SourceId.INSITU,
            "insitu-7",
            "Nuit du jazz",
            "Kellerpoche",
            "Fribourg",
            "2026-11-06T19:30:00Z");
    DedupMatcher.Candidate candidate =
        DedupMatcher.Candidate.of(
            event(
                SourceId.EVENTFROG,
                "eventfrog-7",
                "Nuit du jazz",
                "Kellerpoche",
                "Bern",
                "2026-11-06T19:30:00Z"),
            ZURICH);

    assertThat(matcher.match(incoming, List.of(candidate), ZURICH).matched()).isFalse();
  }

  @Test
  void neverMergesTitlesBelowTheTitleThreshold() {
    DedupMatcher matcher = new DedupMatcher();
    SourceEvent incoming =
        event(
            SourceId.INSITU,
            "insitu-8",
            "Jazz Night",
            "Kellerpoche",
            "Fribourg",
            "2026-11-06T19:30:00Z");
    DedupMatcher.Candidate candidate =
        DedupMatcher.Candidate.of(
            event(
                SourceId.EVENTFROG,
                "eventfrog-8",
                "Jazz Brunch",
                "Kellerpoche",
                "Fribourg",
                "2026-11-06T19:30:00Z"),
            ZURICH);

    assertThat(matcher.match(incoming, List.of(candidate), ZURICH).matched()).isFalse();
  }

  @Test
  void picksTheHighestScoringFuzzyCandidateDeterministically() {
    DedupMatcher matcher = new DedupMatcher();
    SourceEvent incoming =
        event(
            SourceId.INSITU,
            "insitu-9",
            "Concert de Noël",
            "Cathédrale Saint-Nicolas",
            "Fribourg",
            "2026-11-06T19:30:00Z");
    DedupMatcher.Candidate weaker =
        DedupMatcher.Candidate.of(
            event(
                SourceId.BKA,
                "bka-9",
                "Concert de Noël 2026",
                "Cathedrale Saint Nicolas",
                "Fribourg",
                "2026-11-07T19:30:00Z"),
            ZURICH);
    DedupMatcher.Candidate stronger =
        DedupMatcher.Candidate.of(
            event(
                SourceId.EVENTFROG,
                "eventfrog-9",
                "Concert de Noel",
                "Cathédrale Saint-Nicolas",
                "Fribourg",
                "2026-11-07T20:00:00Z"),
            ZURICH);

    MatchDecision decision = matcher.match(incoming, List.of(weaker, stronger), ZURICH);

    assertThat(decision.type()).isEqualTo(MatchDecision.MatchType.FUZZY);
    assertThat(decision.candidate()).isEqualTo(stronger);
  }

  @Test
  void respectsConfiguredThresholdsAndDateWindow() {
    SourceEvent incoming =
        event(
            SourceId.INSITU,
            "insitu-10",
            "Martha",
            "Kellerpoche",
            "Fribourg",
            "2026-11-06T19:30:00Z");
    DedupMatcher.Candidate candidate =
        DedupMatcher.Candidate.of(
            event(
                SourceId.EVENTFROG,
                "eventfrog-10",
                "Marhta",
                "Kellerpoche",
                "Fribourg",
                "2026-11-07T19:30:00Z"),
            ZURICH);

    assertThat(new DedupMatcher().match(incoming, List.of(candidate), ZURICH).matched()).isTrue();
    assertThat(
            new DedupMatcher(new DedupProperties(0.99, 0.85, 1))
                .match(incoming, List.of(candidate), ZURICH)
                .matched())
        .isFalse();
    assertThat(
            new DedupMatcher(new DedupProperties(0.90, 0.85, 0))
                .match(incoming, List.of(candidate), ZURICH)
                .matched())
        .isFalse();
  }

  @Test
  void returnsNoMatchForAnEmptyCandidateList() {
    SourceEvent incoming =
        event(
            SourceId.INSITU,
            "insitu-11",
            "Concert",
            "Kellerpoche",
            "Fribourg",
            "2026-11-06T19:30:00Z");

    MatchDecision decision = new DedupMatcher().match(incoming, List.of(), ZURICH);

    assertThat(decision.type()).isEqualTo(MatchDecision.MatchType.NONE);
    assertThat(decision.candidate()).isNull();
    assertThat(decision.matchedCandidate()).isEmpty();
  }

  @Test
  void jaroWinklerMatchesKnownReferenceValues() {
    assertThat(DedupMatcher.jaroWinkler("martha", "marhta")).isCloseTo(0.961, within(0.001));
    assertThat(DedupMatcher.jaroWinkler("dixon", "dicksonx")).isCloseTo(0.813, within(0.001));
    assertThat(DedupMatcher.jaroWinkler("concert", "concert")).isEqualTo(1.0);
    assertThat(DedupMatcher.jaroWinkler("", "")).isEqualTo(1.0);
    assertThat(DedupMatcher.jaroWinkler("concert", "")).isEqualTo(0.0);
  }

  @Test
  void dedupPropertiesDefaultAndRejectInvalidValues() {
    DedupProperties defaults = new DedupProperties(null, null, null);

    assertThat(defaults.titleThreshold()).isEqualTo(0.90);
    assertThat(defaults.venueThreshold()).isEqualTo(0.85);
    assertThat(defaults.dateWindowDays()).isEqualTo(1);

    assertThatThrownBy(() -> new DedupProperties(1.1, 0.85, 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("title-threshold");
    assertThatThrownBy(() -> new DedupProperties(0.9, -0.1, 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("venue-threshold");
    assertThatThrownBy(() -> new DedupProperties(0.9, 0.85, -1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("date-window-days");
  }

  private static SourceEvent event(
      SourceId source,
      String sourceEventId,
      String title,
      String venue,
      String city,
      String start) {
    return new SourceEvent(
        source,
        sourceEventId,
        title,
        null,
        venue,
        null,
        city,
        null,
        "CH",
        null,
        null,
        URI.create("https://example.test/" + sourceEventId),
        Set.of(),
        null,
        EventStatus.CONFIRMED,
        List.of(new EventOccurrence(Instant.parse(start), null, false)));
  }
}
