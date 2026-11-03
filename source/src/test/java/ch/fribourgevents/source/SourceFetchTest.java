package ch.fribourgevents.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import ch.fribourgevents.domain.EventOccurrence;
import ch.fribourgevents.domain.SourceEvent;
import ch.fribourgevents.domain.SourceId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SourceFetchTest {

  @Test
  void copiesEventsAndRejectsNegativePageCounts() {
    List<SourceEvent> events = new ArrayList<>();
    events.add(event("1"));

    SourceFetch fetch = SourceFetch.of(events, 2);
    events.clear();

    assertThat(fetch.eventCount()).isEqualTo(1);
    assertThat(fetch.pagesFetched()).isEqualTo(2);
    assertThatIllegalArgumentException().isThrownBy(() -> SourceFetch.of(List.of(), -1));
  }

  private static SourceEvent event(String id) {
    return new SourceEvent(
        SourceId.INSITU,
        id,
        "Title " + id,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        List.of(new EventOccurrence(Instant.parse("2026-11-03T19:00:00Z"), null, false)));
  }
}
