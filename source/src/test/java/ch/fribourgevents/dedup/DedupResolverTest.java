package ch.fribourgevents.dedup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.fribourgevents.domain.DedupKey;
import ch.fribourgevents.domain.EventOccurrence;
import ch.fribourgevents.domain.SourceEvent;
import ch.fribourgevents.domain.SourceId;
import ch.fribourgevents.store.DedupCandidate;
import ch.fribourgevents.store.EventStore;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Resolver tests: the stored candidate set is mapped into the pure matcher, and the returned id is
 * the canonical event the pipeline must attach the new source record to.
 */
class DedupResolverTest {

  private static final ZoneId ZURICH = ZoneId.of("Europe/Zurich");

  private final EventStore store = mock(EventStore.class);
  private final DedupResolver resolver = new DedupResolver(store, new DedupMatcher(), ZURICH);

  @BeforeEach
  void stubCandidates() {
    when(store.findDedupCandidates(any(), eq(1))).thenReturn(List.of());
  }

  @Test
  void findsAnExactDedupKeyMatch() {
    SourceEvent stored =
        event(SourceId.INSITU, "i-1", "Cinéma Zéro", "Nouveau Monde", "2026-11-06", "19:30");
    when(store.findDedupCandidates(any(), eq(1))).thenReturn(List.of(candidate(41L, stored)));
    SourceEvent incoming =
        event(SourceId.EVENTFROG, "e-1", "Cinema Zero", "Nouveau Monde", "2026-11-06", "19:30");

    assertThat(resolver.findCanonicalEventId(incoming)).contains(41L);
  }

  @Test
  void findsAFuzzyVariantOnAnAdjacentDay() {
    SourceEvent stored =
        event(
            SourceId.BKA, "b-1", "Das Fräulein von Scuderi", "Kellerpoche", "2026-11-06", "19:15");
    when(store.findDedupCandidates(any(), eq(1))).thenReturn(List.of(candidate(7L, stored)));
    SourceEvent incoming =
        event(
            SourceId.EVENTFROG,
            "e-1",
            "Das Fraulein von Scuderi",
            "Kellerpoche, Theater am Stalden",
            "2026-11-07",
            "19:15");

    assertThat(resolver.findCanonicalEventId(incoming)).contains(7L);
  }

  @Test
  void doesNotMergeTheSameTitleAtADifferentVenue() {
    SourceEvent stored =
        event(SourceId.INSITU, "i-1", "Concert", "Nouveau Monde", "2026-11-06", "19:30");
    when(store.findDedupCandidates(any(), eq(1))).thenReturn(List.of(candidate(41L, stored)));
    SourceEvent incoming =
        event(SourceId.EVENTFROG, "e-1", "Concert", "Kellerpoche", "2026-11-06", "19:30");

    assertThat(resolver.findCanonicalEventId(incoming)).isEmpty();
  }

  @Test
  void neverMergesARecurringSeriesOnDifferentDates() {
    SourceEvent stored =
        event(SourceId.INSITU, "i-1", "Concert", "Nouveau Monde", "2026-11-06", "19:30");
    when(store.findDedupCandidates(any(), eq(1))).thenReturn(List.of(candidate(41L, stored)));
    SourceEvent incoming =
        event(SourceId.EVENTFROG, "e-1", "Concert", "Nouveau Monde", "2026-11-20", "19:30");

    assertThat(resolver.findCanonicalEventId(incoming)).isEmpty();
  }

  @Test
  void returnsEmptyWhenTheStoreHasNoCandidates() {
    SourceEvent incoming =
        event(SourceId.EVENTFROG, "e-1", "Concert", "Nouveau Monde", "2026-11-06", "19:30");

    assertThat(resolver.findCanonicalEventId(incoming)).isEmpty();
  }

  private static DedupCandidate candidate(long eventId, SourceEvent event) {
    return new DedupCandidate(
        eventId,
        DedupKey.of(event, ZURICH),
        event.title(),
        event.venueName(),
        event.city(),
        event.occurrences());
  }

  private static SourceEvent event(
      SourceId source, String id, String title, String venue, String date, String time) {
    return new SourceEvent(
        source,
        id,
        title,
        null,
        venue,
        null,
        "Fribourg",
        null,
        null,
        null,
        null,
        URI.create("https://example.test/" + id),
        Set.of("culture"),
        null,
        null,
        List.of(new EventOccurrence(Instant.parse(date + "T" + time + ":00Z"), null, false)));
  }
}
