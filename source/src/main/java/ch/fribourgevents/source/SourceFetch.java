package ch.fribourgevents.source;

import ch.fribourgevents.domain.SourceEvent;
import java.util.List;
import java.util.Objects;

/**
 * What one {@link EventSource#fetch} call returned: the events and how many upstream pages it read.
 *
 * <p>{@code pagesFetched} exists for the pipeline's {@code source_sync} log line. It counts listing
 * pages or requests actually issued, never a guess: an adapter that reads a single endpoint without
 * paging reports {@code 1}, and an adapter that failed before its first page would not produce a
 * result at all.
 */
public record SourceFetch(List<SourceEvent> events, int pagesFetched) {

  public SourceFetch {
    events = List.copyOf(Objects.requireNonNull(events, "events"));
    if (pagesFetched < 0) {
      throw new IllegalArgumentException("pagesFetched must not be negative");
    }
  }

  public static SourceFetch of(List<SourceEvent> events, int pagesFetched) {
    return new SourceFetch(events, pagesFetched);
  }

  public int eventCount() {
    return events.size();
  }
}
