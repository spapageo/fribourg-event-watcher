package ch.fribourgevents.source;

import java.net.URI;

/**
 * HTML sources fetch pages through this interface instead of touching Selenium directly.
 *
 * <p>That boundary is what keeps parser tests fast: an adapter takes a {@code PageLoader} (a fake
 * returning a saved fixture in tests, the Selenium implementation in production) and parses the
 * returned page source. Implementations own their transport, rate limiting, and timeouts.
 *
 * <p>Implementations must be safe to call sequentially; the pipeline runs one source at a time.
 */
public interface PageLoader extends AutoCloseable {

  /**
   * Fetches one page and returns its fully rendered source.
   *
   * @param uri absolute page address
   * @return the page's HTML; never blank
   * @throws SourceFetchException when the page cannot be loaded or never finishes rendering
   */
  String load(URI uri) throws SourceFetchException;

  /**
   * Releases any transport resources. A loader that opens and closes a browser per page has nothing
   * to release, so the default is a no-op.
   */
  @Override
  default void close() {}
}
