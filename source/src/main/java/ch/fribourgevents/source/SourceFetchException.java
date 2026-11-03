package ch.fribourgevents.source;

/**
 * The only checked exception an {@link EventSource} may throw: the source could not be read.
 *
 * <p>Single malformed items are not reported this way; adapters skip and log those. A {@code
 * SourceFetchException} means the source produced nothing usable, and the pipeline must mark it
 * failed instead of silently contributing zero events.
 */
public class SourceFetchException extends Exception {

  public SourceFetchException(String message) {
    super(message);
  }

  public SourceFetchException(String message, Throwable cause) {
    super(message, cause);
  }
}
