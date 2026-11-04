package ch.fribourgevents.source.bka;

import static org.assertj.core.api.Assertions.assertThat;

import ch.fribourgevents.domain.FetchWindow;
import ch.fribourgevents.domain.SourceEvent;
import ch.fribourgevents.source.SourceFetch;
import ch.fribourgevents.source.SourceFetchException;
import ch.fribourgevents.source.browser.RateLimiter;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

/**
 * Manual live smoke against the real bka.ch agenda API. Tagged {@code live}, so it is excluded from
 * {@code ./gradlew build}.
 *
 * <p>Run with: {@code ./gradlew test -PincludeTags=live --tests
 * 'ch.fribourgevents.source.bka.BkaLiveTest'}
 */
@Tag("live")
class BkaLiveTest {

  private static final Logger log = LoggerFactory.getLogger(BkaLiveTest.class);
  private static final ZoneId ZURICH = ZoneId.of("Europe/Zurich");
  private static final String BASE_URL = "https://www.bka.ch";

  @Test
  void ingestsTheLiveAgenda() throws SourceFetchException {
    Instant now = Instant.now();
    FetchWindow window = new FetchWindow(now, now.plus(Duration.ofDays(14)), ZURICH);
    BkaEventSource source =
        new BkaEventSource(new BkaClient(BASE_URL, RestClient.create(), new RateLimiter(1)));

    SourceFetch fetch = source.fetch(window);
    List<SourceEvent> events = fetch.events();

    log.info(
        "bka live: {} events from {} pages inside {}..{}",
        events.size(),
        fetch.pagesFetched(),
        window.from(),
        window.to());
    assertThat(events).hasSizeGreaterThanOrEqualTo(100);
    assertThat(events)
        .allSatisfy(event -> {
          assertThat(event.sourceEventId()).isNotBlank();
          assertThat(event.title()).isNotBlank();
          assertThat(event.occurrences()).isNotEmpty();
          assertThat(event.url()).isNotNull();
        });
  }
}
