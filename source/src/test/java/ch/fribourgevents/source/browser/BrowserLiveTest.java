package ch.fribourgevents.source.browser;

import static org.assertj.core.api.Assertions.assertThat;

import ch.fribourgevents.source.PageLoader;
import ch.fribourgevents.source.SourceFetchException;
import java.net.URI;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manual live smoke for the Selenium layer: loads the real bka.ch agenda through headless Chromium.
 * Tagged {@code live}, so {@code ./gradlew build} never launches a browser.
 *
 * <p>Run with: {@code ./gradlew test -PincludeTags=live -PliveSource=browser}
 */
@Tag("live")
class BrowserLiveTest {

  private static final Logger log = LoggerFactory.getLogger(BrowserLiveTest.class);
  private static final URI BKA_AGENDA = URI.create("https://bka.ch/agenda");

  @Test
  void loadsTheLiveBkaAgenda() throws SourceFetchException {
    BrowserProperties properties = new BrowserProperties(null, null, null, null, null, null);

    try (PageLoader loader = new SeleniumPageLoader(properties, new RateLimiter(1))) {
      String page = loader.load(BKA_AGENDA);

      log.info("browser live: {} chars from {}", page.length(), BKA_AGENDA);
      assertThat(page).containsIgnoringCase("<html").hasSizeGreaterThan(1000);
    }
  }
}
