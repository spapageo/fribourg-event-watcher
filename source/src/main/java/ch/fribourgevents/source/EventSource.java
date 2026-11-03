package ch.fribourgevents.source;

import ch.fribourgevents.domain.FetchWindow;
import ch.fribourgevents.domain.SourceId;

/**
 * One upstream agenda. Implementations are stateless and must be safe to call repeatedly.
 *
 * <p>Adapters translate a source-specific wire format into {@link
 * ch.fribourgevents.domain.SourceEvent}s. They own fetching and parsing; they must not persist
 * anything, and they must never throw anything other than {@link SourceFetchException} for an
 * upstream failure.
 */
public interface EventSource {

  /** Stable identifier used as the {@code event_source_ref.source} value. */
  SourceId id();

  /**
   * Returns every event with at least one occurrence intersecting {@code window}, together with the
   * number of upstream pages/requests the fetch needed for the {@code source_sync} log line.
   *
   * <p>The returned events are in upstream order; callers that need another order must sort. A
   * single upstream state that fails to parse is skipped and logged, it does not fail the whole
   * call.
   *
   * @throws SourceFetchException when the source cannot be read at all (transport error, unexpected
   *     status, or an unreadable payload)
   */
  SourceFetch fetch(FetchWindow window) throws SourceFetchException;
}
