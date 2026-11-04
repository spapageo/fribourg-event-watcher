package ch.fribourgevents.source.bka;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.fribourgevents.domain.EventOccurrence;
import ch.fribourgevents.domain.EventStatus;
import ch.fribourgevents.domain.FetchWindow;
import ch.fribourgevents.domain.SourceEvent;
import ch.fribourgevents.domain.SourceId;
import ch.fribourgevents.source.SourceFetch;
import ch.fribourgevents.source.SourceFetchException;
import ch.fribourgevents.source.browser.RateLimiter;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * WireMock tests for the bka.ch JSON agenda adapter. Two saved pages exercise paging, date-window
 * filtering, main-event/subevent grouping, field mapping, rate limiting, and malformed-item
 * isolation; a third, empty page is what stops the crawl.
 */
class BkaEventSourceTest {

  private static final ZoneId ZURICH = ZoneId.of("Europe/Zurich");
  private static final FetchWindow WINDOW =
      new FetchWindow(
          Instant.parse("2026-11-03T00:00:00Z"), Instant.parse("2026-11-17T00:00:00Z"), ZURICH);
  private static final String EVENTS_PATH = "/api/events";
  private static final String USER_AGENT = "fribourg-events-digest/1.0";

  private final FakeTime time = new FakeTime();

  private WireMockServer server;
  private BkaEventSource source;

  @BeforeEach
  void startServer() {
    server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    server.start();
    source = source(server.baseUrl(), 60);
  }

  @AfterEach
  void stopServer() {
    server.stop();
  }

  @Test
  void mapsAgendaFieldsIntoSourceEvents() throws SourceFetchException {
    stubFullCrawl();

    List<SourceEvent> events = source.fetch(WINDOW).events();

    assertThat(source.id()).isEqualTo(SourceId.BKA);
    assertThat(events)
        .extracting(SourceEvent::sourceEventId)
        .containsExactly("31250", "40001", "41555");

    SourceEvent exhibition = event(events, "31250");
    assertThat(exhibition.source()).isEqualTo(SourceId.BKA);
    assertThat(exhibition.title()).isEqualTo("Bergtöne im Lauterbrunnental");
    assertThat(exhibition.url())
        .hasToString("https://www.bka.ch/event/bergtone-im-lauterbrunnental");
    assertThat(exhibition.venueName()).isEqualTo("MiniMuseum Mürren");
    assertThat(exhibition.addressLine()).isEqualTo("Ägerten, 3825");
    assertThat(exhibition.city()).isEqualTo("Lauterbrunnen");
    assertThat(exhibition.canton()).isNull();
    assertThat(exhibition.country()).isEqualTo("CH");
    assertThat(exhibition.latitude()).isEqualByComparingTo("46.563704");
    assertThat(exhibition.longitude()).isEqualByComparingTo("7.89702");
    assertThat(exhibition.priceInfo()).isEqualTo("Free entry");
    assertThat(exhibition.status()).isEqualTo(EventStatus.CONFIRMED);
    assertThat(exhibition.categories()).containsExactly("Ausstellungen & Kulturerbe");
    assertThat(exhibition.description())
        .isEqualTo("MiniMuseum Mürren: Bergtöne im Lauterbrunnental. Eintritt frei.");
    assertThat(exhibition.occurrences())
        .containsExactly(
            new EventOccurrence(
                Instant.parse("2025-12-28T19:00:00Z"),
                Instant.parse("2026-11-30T22:30:00Z"),
                false));
  }

  @Test
  void fallsBackToTheProposedPlaceWhenTheVenueHasNoPlaceRecord() throws SourceFetchException {
    stubFullCrawl();

    SourceEvent concert = event(source.fetch(WINDOW).events(), "40001");

    assertThat(concert.venueName()).isEqualTo("Schiff Bern");
    assertThat(concert.addressLine()).isEqualTo("Schiffweg 2, 3005");
    assertThat(concert.city()).isEqualTo("Bern");
    assertThat(concert.latitude()).isEqualByComparingTo("46.948");
    assertThat(concert.description()).isNull();
    assertThat(concert.categories()).containsExactlyInAnyOrder("Musik", "Bühne");
    assertThat(concert.priceInfo()).isEqualTo("Erwachsene: CHF 13, Kinder (ab 6 Jahren): CHF 5");
    assertThat(concert.occurrences())
        .containsExactly(
            new EventOccurrence(
                Instant.parse("2026-11-05T19:00:00Z"),
                Instant.parse("2026-11-05T21:00:00Z"),
                false));
  }

  @Test
  void groupsMainEventAndSubEventsIntoOneEventWithEveryShowing() throws SourceFetchException {
    stubFullCrawl();

    List<SourceEvent> events = source.fetch(WINDOW).events();

    SourceEvent comedy = event(events, "41555");
    assertThat(events).filteredOn(e -> e.title().equals(comedy.title())).hasSize(1);
    assertThat(comedy.url())
        .hasToString("https://www.bka.ch/event/michael-elsener-gute-entscheidung-2");
    assertThat(comedy.occurrences())
        .containsExactly(
            new EventOccurrence(
                Instant.parse("2026-11-04T19:00:00Z"),
                Instant.parse("2026-11-04T21:00:00Z"),
                false),
            new EventOccurrence(
                Instant.parse("2026-11-05T19:00:00Z"),
                Instant.parse("2026-11-05T21:00:00Z"),
                false));
  }

  @Test
  void keepsOnlyEventsOverlappingTheWindow() throws SourceFetchException {
    stubFullCrawl();

    List<SourceEvent> events = source.fetch(WINDOW).events();

    // The exhibition started long before the window but still runs inside it.
    assertThat(events).extracting(SourceEvent::sourceEventId).contains("31250");
    // This one starts after the window ends.
    assertThat(events).extracting(SourceEvent::sourceEventId).doesNotContain("40003");
  }

  @Test
  void skipsMalformedItemWithoutAbortingThePage() throws SourceFetchException {
    stubFullCrawl();

    List<SourceEvent> events = source.fetch(WINDOW).events();

    assertThat(events).extracting(SourceEvent::sourceEventId).doesNotContain("40002");
    assertThat(events).extracting(SourceEvent::sourceEventId).contains("40001");
  }

  @Test
  void sendsTheWindowAndPagingParametersAndStopsOnTheEmptyPage() throws SourceFetchException {
    stubFullCrawl();

    SourceFetch fetch = source.fetch(WINDOW);

    assertThat(fetch.pagesFetched()).isEqualTo(3);
    server.verify(3, getRequestedFor(urlPathEqualTo(EVENTS_PATH)));
    server.verify(
        getRequestedFor(urlPathEqualTo(EVENTS_PATH))
            .withQueryParam("limit", equalTo("100"))
            .withQueryParam("page", equalTo("1"))
            .withQueryParam("date_from", equalTo("2026-11-03"))
            .withQueryParam("date_to", equalTo("2026-11-17"))
            .withHeader("User-Agent", equalTo(USER_AGENT)));
  }

  @Test
  void pacesEveryPageThroughTheRateLimiter() throws SourceFetchException {
    stubFullCrawl();

    source.fetch(WINDOW);

    assertThat(time.sleeps).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(1));
  }

  @Test
  void wrapsServerErrorInSourceFetchException() {
    server.stubFor(
        get(urlPathEqualTo(EVENTS_PATH)).willReturn(aResponse().withStatus(500).withBody("boom")));

    assertThatThrownBy(() -> source.fetch(WINDOW))
        .isInstanceOf(SourceFetchException.class)
        .hasMessageContaining("bka")
        .hasMessageContaining("page 1");
  }

  @Test
  void rejectsMalformedTopLevelPayload() {
    server.stubFor(get(urlPathEqualTo(EVENTS_PATH)).willReturn(okJson("{\"code\":200}")));

    assertThatThrownBy(() -> source.fetch(WINDOW))
        .isInstanceOf(SourceFetchException.class)
        .hasMessageContaining("malformed top level");
  }

  @Test
  void failsAtTheRequestCapInsteadOfTruncatingSilently() {
    // Every page looks like page one and none is empty: only the hard cap can stop the crawl.
    server.stubFor(
        get(urlPathEqualTo(EVENTS_PATH)).willReturn(okJson(fixture("agenda-page-1.json"))));

    BkaEventSource capped = source(server.baseUrl(), 2);

    assertThatThrownBy(() -> capped.fetch(WINDOW))
        .isInstanceOf(SourceFetchException.class)
        .hasMessageContaining("cap");
    server.verify(2, getRequestedFor(urlPathEqualTo(EVENTS_PATH)));
  }

  @Test
  void returnsAnEmptyListForAnEmptyWindow() throws SourceFetchException {
    stubEmptyPage(1);

    SourceFetch fetch = source.fetch(WINDOW);

    assertThat(fetch.events()).isEmpty();
    assertThat(fetch.pagesFetched()).isEqualTo(1);
  }

  private BkaEventSource source(String baseUrl, int maxRequests) {
    return new BkaEventSource(
        new BkaClient(baseUrl, RestClient.create(), new RateLimiter(1, time, time), maxRequests));
  }

  private void stubFullCrawl() {
    stubPage(1, "agenda-page-1.json");
    stubPage(2, "agenda-page-2.json");
    stubEmptyPage(3);
  }

  private void stubPage(int page, String fixture) {
    server.stubFor(
        get(urlPathEqualTo(EVENTS_PATH))
            .withQueryParam("page", equalTo(Integer.toString(page)))
            .willReturn(okJson(fixture(fixture))));
  }

  private void stubEmptyPage(int page) {
    server.stubFor(
        get(urlPathEqualTo(EVENTS_PATH))
            .withQueryParam("page", equalTo(Integer.toString(page)))
            .willReturn(okJson("{\"code\":200,\"message\":\"Success\",\"data\":[],\"total\":0}")));
  }

  private static SourceEvent event(List<SourceEvent> events, String sourceEventId) {
    return events.stream()
        .filter(e -> e.sourceEventId().equals(sourceEventId))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no event " + sourceEventId));
  }

  private static String fixture(String name) {
    try (InputStream in = BkaEventSourceTest.class.getResourceAsStream("/fixtures/bka/" + name)) {
      if (in == null) {
        throw new IllegalStateException("missing fixture " + name);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Fake monotonic clock that advances only with the limiter's sleeps. */
  private static final class FakeTime implements LongSupplier, RateLimiter.Sleeper {

    private final List<Duration> sleeps = new ArrayList<>();
    private long nanos;

    @Override
    public long getAsLong() {
      return nanos;
    }

    @Override
    public void sleep(Duration duration) {
      sleeps.add(duration);
      nanos += duration.toNanos();
    }
  }
}
