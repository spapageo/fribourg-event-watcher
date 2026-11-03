package ch.fribourgevents.pipeline;

import ch.fribourgevents.domain.FetchWindow;
import ch.fribourgevents.domain.SourceEvent;
import ch.fribourgevents.domain.SourceId;
import ch.fribourgevents.source.EventSource;
import ch.fribourgevents.source.SourceFetch;
import ch.fribourgevents.source.SourceFetchException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Scripted {@link EventSource} for pipeline tests: fixed events, fixed page count, or a failure.
 */
public final class FakeEventSource implements EventSource {

  private final SourceId id;
  private final List<SourceEvent> events;
  private final int pagesFetched;
  private final SourceFetchException failure;

  private FetchWindow lastWindow;

  private FakeEventSource(
      SourceId id, List<SourceEvent> events, int pagesFetched, SourceFetchException failure) {
    this.id = Objects.requireNonNull(id, "id");
    this.events = List.copyOf(events);
    this.pagesFetched = pagesFetched;
    this.failure = failure;
  }

  public static FakeEventSource returning(SourceId id, List<SourceEvent> events) {
    return returning(id, events, 1);
  }

  public static FakeEventSource returning(SourceId id, List<SourceEvent> events, int pagesFetched) {
    return new FakeEventSource(id, events, pagesFetched, null);
  }

  public static FakeEventSource failing(SourceId id, String message) {
    return new FakeEventSource(id, List.of(), 0, new SourceFetchException(message));
  }

  @Override
  public SourceId id() {
    return id;
  }

  @Override
  public SourceFetch fetch(FetchWindow window) throws SourceFetchException {
    lastWindow = window;
    if (failure != null) {
      throw failure;
    }
    return new SourceFetch(events, pagesFetched);
  }

  /** The window the pipeline asked for, empty when never called. */
  public Optional<FetchWindow> lastWindow() {
    return Optional.ofNullable(lastWindow);
  }
}
