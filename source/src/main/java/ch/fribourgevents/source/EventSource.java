package ch.fribourgevents.source;

import ch.fribourgevents.domain.FetchWindow;
import ch.fribourgevents.domain.SourceEvent;
import ch.fribourgevents.domain.SourceId;
import java.util.List;

/**
 * One upstream agenda. Implementations are stateless and must be safe to call repeatedly.
 *
 * <p>Adapters translate a source-specific wire format into {@link SourceEvent}s. They own fetching
 * and parsing; they must not persist anything, and they must never throw anything other than {@link
 * SourceFetchException} for an upstream failure.
 */
public interface EventSource {

  /** Stable identifier used as the {@code event_source_ref.source} value. */
  SourceId id();

  /**
   * Returns every event with at least one occurrence intersecting {@code window}.
   *
   * <p>The returned list is in upstream order; callers that need another order must sort. A single
   * upstream state that fails to parse is skipped and logged, it does not fail the whole call.
   *
   * @throws SourceFetchException when the source cannot be read at all (transport error, unexpected
   *     status, or an unreadable payload)
   */
  List<SourceEvent> fetch(FetchWindow window) throws SourceFetchException;
}
