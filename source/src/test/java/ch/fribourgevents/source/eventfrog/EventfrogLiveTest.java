package ch.fribourgevents.source.eventfrog;

import static org.assertj.core.api.Assertions.assertThat;

import ch.fribourgevents.domain.FetchWindow;
import ch.fribourgevents.domain.SourceEvent;
import ch.fribourgevents.source.PageLoader;
import ch.fribourgevents.source.SourceFetch;
import ch.fribourgevents.source.SourceFetchException;
import ch.fribourgevents.source.browser.BrowserProperties;
import ch.fribourgevents.source.browser.RateLimiter;
import ch.fribourgevents.source.browser.SeleniumPageLoader;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manual live smoke against the real eventfrog.ch city pages. Tagged {@code live}, so {@code
 * ./gradlew build} never launches a browser or touches the network.
 *
 * <p>Run with: {@code ./gradlew test -PincludeTags=live --tests
 * 'ch.fribourgevents.source.eventfrog.EventfrogLiveTest'}
 *
 * <p>A one-year window keeps every currently listed event inside the window, so each city must
 * yield the spec's ≥50 events. Detail enrichment is off here: the smoke is about the
 * list/pagination path and stays fast (~4 page loads).
 */
@Tag("live")
class EventfrogLiveTest {

  private static final Logger log = LoggerFactory.getLogger(EventfrogLiveTest.class);
  private static final ZoneId ZURICH = ZoneId.of("Europe/Zurich");
  private static final String BASE_URL = "https://eventfrog.ch";

  @Test
  void ingestsAtLeastFiftyEventsFromEachCityPage() throws SourceFetchException {
    Instant now = Instant.now();
    FetchWindow window = new FetchWindow(now, now.plus(Duration.ofDays(365)), ZURICH);
    BrowserProperties browser = new BrowserProperties(null, null, null, null, null, null);

    try (PageLoader loader = new SeleniumPageLoader(browser, new RateLimiter(1))) {
      assertCity(loader, window, "fribourg");
      assertCity(loader, window, "bern");
    }
  }

  private static void assertCity(PageLoader loader, FetchWindow window, String city)
      throws SourceFetchException {
    EventfrogEventSource source = new EventfrogEventSource(loader, BASE_URL, List.of(city), 60, 0);

    SourceFetch fetch = source.fetch(window);
    List<SourceEvent> events = fetch.events();

    log.info(
        "eventfrog live: {} events from {} inside {}..{} ({} requests)",
        events.size(),
        city,
        window.from(),
        window.to(),
        fetch.pagesFetched());
    assertThat(events).hasSizeGreaterThanOrEqualTo(50);
    assertThat(events)
        .allSatisfy(event -> {
          assertThat(event.sourceEventId()).isNotBlank();
          assertThat(event.title()).isNotBlank();
          assertThat(event.occurrences()).isNotEmpty();
          assertThat(event.url()).isNotNull();
        });
  }
}
