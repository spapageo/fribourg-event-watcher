package ch.fribourgevents.source.insitu;

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
import ch.fribourgevents.source.SourceFetchException;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * WireMock tests for the insitu.live adapter. Two saved pages exercise pagination, field mapping,
 * run-range deduplication, window filtering, and malformed-item isolation.
 */
class InsituEventSourceTest {

  private static final ZoneId ZURICH = ZoneId.of("Europe/Zurich");
  private static final FetchWindow WINDOW =
      new FetchWindow(
          Instant.parse("2026-11-03T00:00:00Z"), Instant.parse("2026-11-17T00:00:00Z"), ZURICH);
  private static final String EVENTS_PATH = "/api/events";
  private static final String USER_AGENT = "fribourg-events-digest/1.0";

  private WireMockServer server;
  private InsituEventSource source;

  @BeforeEach
  void startServer() {
    server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    server.start();
    source = new InsituEventSource(new InsituClient(server.baseUrl(), RestClient.create()));
  }

  @AfterEach
  void stopServer() {
    server.stop();
  }

  @Test
  void mapsListingFieldsIntoSourceEvents() throws SourceFetchException, IOException {
    stubPage(1, "events-page-1.json");
    stubPage(2, "events-page-2.json");

    List<SourceEvent> events = source.fetch(WINDOW);

    assertThat(source.id()).isEqualTo(SourceId.INSITU);
    assertThat(events)
        .extracting(SourceEvent::sourceEventId)
        .containsExactly("12147", "12164", "999002", "12480", "12100");

    SourceEvent recit = event(events, "12164");
    assertThat(recit.source()).isEqualTo(SourceId.INSITU);
    assertThat(recit.title()).isEqualTo("Café-Récit #15");
    assertThat(recit.url()).hasToString("https://insitu.live/fr/evenement/cafe-recit-15-12164");
    assertThat(recit.venueName()).isEqualTo("Café-Récit");
    assertThat(recit.addressLine()).isEqualTo("Rue de l'Hôpital 2, 1700");
    assertThat(recit.city()).isEqualTo("Fribourg");
    assertThat(recit.country()).isEqualTo("CH");
    assertThat(recit.canton()).isNull();
    assertThat(recit.latitude()).isEqualByComparingTo("46.804");
    assertThat(recit.longitude()).isEqualByComparingTo("7.161");
    assertThat(recit.priceInfo()).isNull();
    assertThat(recit.status()).isEqualTo(EventStatus.CONFIRMED);
    assertThat(recit.categories())
        .containsExactlyInAnyOrder("Rencontre & médiation", "Arts visuels & design");
    assertThat(recit.occurrences())
        .containsExactly(
            new EventOccurrence(
                Instant.parse("2026-11-05T17:00:00Z"),
                Instant.parse("2026-11-05T18:30:00Z"),
                false));
  }

  @Test
  void stripsHtmlFromDescription() throws SourceFetchException, IOException {
    stubPage(1, "events-page-1.json");
    stubPage(2, "events-page-2.json");

    List<SourceEvent> events = source.fetch(WINDOW);

    assertThat(event(events, "12164").description())
        .isEqualTo("Une soirée de récits autour du quartier.");
    assertThat(event(events, "12147").description())
        .isEqualTo("Peintures & dessins récents. Entrée libre.");
  }

  @Test
  void collapsesRepeatedRowsForOneEventIntoOneRunOccurrence()
      throws SourceFetchException, IOException {
    stubPage(1, "events-page-1.json");
    stubPage(2, "events-page-2.json");

    List<SourceEvent> events = source.fetch(WINDOW);

    // Event 12147 is listed once per open day across both pages.
    assertThat(events).filteredOn(e -> e.sourceEventId().equals("12147")).hasSize(1);
    assertThat(event(events, "12147").occurrences())
        .containsExactly(
            new EventOccurrence(
                Instant.parse("2026-09-24T12:00:00Z"),
                Instant.parse("2026-11-26T16:30:00Z"),
                false));
  }

  @Test
  void marksMidnightStartsAsAllDay() throws SourceFetchException, IOException {
    stubPage(1, "events-page-1.json");
    stubPage(2, "events-page-2.json");

    List<SourceEvent> events = source.fetch(WINDOW);

    assertThat(event(events, "12100").occurrences())
        .containsExactly(
            new EventOccurrence(
                Instant.parse("2026-10-02T22:00:00Z"),
                Instant.parse("2026-11-17T22:45:00Z"),
                true));
  }

  @Test
  void skipsMalformedRowWithoutAbortingThePage() throws SourceFetchException, IOException {
    stubPage(1, "events-page-1.json");
    stubPage(2, "events-page-2.json");

    List<SourceEvent> events = source.fetch(WINDOW);

    assertThat(events).extracting(SourceEvent::sourceEventId).doesNotContain("999001");
    // The next row of the same page is still ingested.
    assertThat(events).extracting(SourceEvent::sourceEventId).contains("999002");
  }

  @Test
  void toleratesMissingPlaceAndLabels() throws SourceFetchException, IOException {
    stubPage(1, "events-page-1.json");
    stubPage(2, "events-page-2.json");

    List<SourceEvent> events = source.fetch(WINDOW);

    SourceEvent withoutPlace = event(events, "999002");
    assertThat(withoutPlace.venueName()).isNull();
    assertThat(withoutPlace.city()).isNull();
    assertThat(withoutPlace.addressLine()).isNull();
    assertThat(withoutPlace.description()).isNull();
    assertThat(withoutPlace.categories()).isEmpty();
    assertThat(withoutPlace.status()).isEqualTo(EventStatus.TENTATIVE);
  }

  @Test
  void filtersEventsWhollyOutsideTheWindow() throws SourceFetchException, IOException {
    stubPage(1, "events-page-1.json");
    stubPage(2, "events-page-2.json");

    List<SourceEvent> events = source.fetch(WINDOW);

    assertThat(events).extracting(SourceEvent::sourceEventId).doesNotContain("999003", "999004");
  }

  @Test
  void mapsUnrecognisedStatusToUnknown() throws SourceFetchException, IOException {
    stubPage(1, "events-page-1.json");
    stubPage(2, "events-page-2.json");

    List<SourceEvent> events = source.fetch(WINDOW);

    assertThat(event(events, "12480").status()).isEqualTo(EventStatus.UNKNOWN);
  }

  @Test
  void pagesUntilViewHasNoNextAndSendsExpectedRequestShape()
      throws SourceFetchException, IOException {
    stubPage(1, "events-page-1.json");
    stubPage(2, "events-page-2.json");

    source.fetch(WINDOW);

    server.verify(2, getRequestedFor(urlPathEqualTo(EVENTS_PATH)));
    server.verify(
        getRequestedFor(urlPathEqualTo(EVENTS_PATH))
            .withQueryParam("limit", equalTo("100"))
            .withQueryParam("page", equalTo("1"))
            .withHeader("User-Agent", equalTo(USER_AGENT)));
  }

  @Test
  void wrapsServerErrorInSourceFetchException() {
    server.stubFor(
        get(urlPathEqualTo(EVENTS_PATH)).willReturn(aResponse().withStatus(500).withBody("boom")));

    assertThatThrownBy(() -> source.fetch(WINDOW))
        .isInstanceOf(SourceFetchException.class)
        .hasMessageContaining("insitu");
  }

  @Test
  void rejectsMalformedTopLevelPayload() {
    server.stubFor(get(urlPathEqualTo(EVENTS_PATH)).willReturn(okJson("{}")));

    assertThatThrownBy(() -> source.fetch(WINDOW))
        .isInstanceOf(SourceFetchException.class)
        .hasMessageContaining("insitu");
  }

  @Test
  void returnsEmptyListForEmptyAgenda() throws SourceFetchException {
    server.stubFor(
        get(urlPathEqualTo(EVENTS_PATH))
            .willReturn(okJson("{\"member\":[],\"view\":{\"last\":\"page=1\"}}")));

    assertThat(source.fetch(WINDOW)).isEmpty();
  }

  @Test
  void abortsAfterRequestCap() throws IOException {
    // Every page advertises a next page: only the hard cap can stop the crawl.
    server.stubFor(
        get(urlPathEqualTo(EVENTS_PATH)).willReturn(okJson(fixture("events-page-1.json"))));

    assertThatThrownBy(() -> source.fetch(WINDOW))
        .isInstanceOf(SourceFetchException.class)
        .hasMessageContaining("cap");
    assertThat(server.findAll(getRequestedFor(urlPathEqualTo(EVENTS_PATH)))).hasSize(60);
  }

  private void stubPage(int page, String fixture) throws IOException {
    server.stubFor(
        get(urlPathEqualTo(EVENTS_PATH))
            .withQueryParam("page", equalTo(Integer.toString(page)))
            .willReturn(okJson(fixture(fixture))));
  }

  private static SourceEvent event(List<SourceEvent> events, String sourceEventId) {
    return events.stream()
        .filter(e -> e.sourceEventId().equals(sourceEventId))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no event " + sourceEventId));
  }

  private static String fixture(String name) {
    try (InputStream in =
        InsituEventSourceTest.class.getResourceAsStream("/fixtures/insitu/" + name)) {
      if (in == null) {
        throw new IllegalStateException("missing fixture " + name);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
