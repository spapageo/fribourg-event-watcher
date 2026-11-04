package ch.fribourgevents.source.eventfrog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.fribourgevents.domain.EventOccurrence;
import ch.fribourgevents.domain.EventStatus;
import ch.fribourgevents.domain.FetchWindow;
import ch.fribourgevents.domain.SourceEvent;
import ch.fribourgevents.domain.SourceId;
import ch.fribourgevents.source.PageLoader;
import ch.fribourgevents.source.SourceFetch;
import ch.fribourgevents.source.SourceFetchException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Parser and adapter tests for eventfrog.ch. Saved list fragments exercise pagination, duplicate
 * links, window filtering and malformed tiles; saved JSON-LD detail pages exercise exact instants,
 * addresses, categories, prices and group showings. Nothing here touches the network or a browser.
 */
class EventfrogEventSourceTest {

  private static final String BASE = "https://eventfrog.ch";
  private static final ZoneId ZURICH = ZoneId.of("Europe/Zurich");
  private static final FetchWindow WINDOW =
      new FetchWindow(
          Instant.parse("2026-11-04T00:00:00Z"), Instant.parse("2026-11-18T00:00:00Z"), ZURICH);

  @Test
  void extractsAtLeastFiftyUniqueEventsPerCityPage() throws SourceFetchException {
    FakePageLoader loader =
        new FakePageLoader()
            .on(landing("fribourg"), fixture("fribourg-list-page-1.html"))
            .on(listPage("fribourg", 2), fixture("fribourg-list-page-2.html"))
            .on(landing("bern"), fixture("bern-list-page-1.html"))
            .on(listPage("bern", 2), fixture("bern-list-page-2.html"));
    EventfrogEventSource source =
        new EventfrogEventSource(loader, BASE, List.of("fribourg", "bern"), 60, 0);

    FetchWindow wide =
        new FetchWindow(
            Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2027-12-31T00:00:00Z"), ZURICH);
    SourceFetch fetch = source.fetch(wide);

    assertThat(fetch.events()).hasSizeGreaterThanOrEqualTo(100);
    assertThat(fetch.events()).extracting(SourceEvent::sourceEventId).doesNotHaveDuplicates();
    assertThat(fetch.events())
        .noneMatch(event -> event.title().equals("Defektes Event ohne Datum"));
    assertThat(fetch.pagesFetched()).isEqualTo(4);
  }

  @Test
  void parsesTheEmbeddedTopLocalitiesNavigationJson() {
    EventfrogParser parser = new EventfrogParser();

    List<EventfrogParser.Locality> localities =
        parser.parseTopLocalities(fixture("fribourg-list-page-1.html"));

    assertThat(localities)
        .extracting(EventfrogParser.Locality::title)
        .containsExactly("Fribourg", "Bern");
    assertThat(localities.getFirst().href())
        .hasToString("https://eventfrog.ch/de/events/fribourg.html");
  }

  @Test
  void mapsDetailFieldsIntoTheCanonicalEvent() throws SourceFetchException {
    String tile = tile("fribourg-list-page-1.html", "7513514820114607331");
    URI detailUrl =
        URI.create(
            BASE + "/de/p/kino/kino/film-abend-icf-fribourg-fribourg-7513514820114607331.html");
    FakePageLoader loader =
        new FakePageLoader()
            .on(landing("fribourg"), listPage(tile))
            .on(detailUrl, fixture("fribourg-detail-event.html"));
    EventfrogEventSource source =
        new EventfrogEventSource(loader, BASE, List.of("fribourg"), 60, 1);

    List<SourceEvent> events = source.fetch(WINDOW).events();

    assertThat(events).hasSize(1);
    SourceEvent event = events.getFirst();
    assertThat(event.source()).isEqualTo(SourceId.EVENTFROG);
    assertThat(event.sourceEventId()).isEqualTo("7513514820114607331");
    assertThat(event.title()).isEqualTo("Film-Abend ICF Fribourg");
    assertThat(event.description()).contains("Real Life");
    assertThat(event.venueName()).isEqualTo("Chem. Montrevers 1");
    assertThat(event.addressLine()).isEqualTo("Chemin Montrevers 1, 1700");
    assertThat(event.city()).isEqualTo("Fribourg");
    assertThat(event.canton()).isNull();
    assertThat(event.country()).isEqualTo("CH");
    assertThat(event.latitude()).isEqualByComparingTo("46.81198759999999");
    assertThat(event.longitude()).isEqualByComparingTo("7.1575161");
    assertThat(event.url()).hasToString(detailUrl.toString());
    assertThat(event.categories()).containsExactly("Kino");
    assertThat(event.priceInfo()).isEqualTo("Free entry");
    assertThat(event.status()).isEqualTo(EventStatus.CONFIRMED);
    assertThat(event.occurrences())
        .containsExactly(
            new EventOccurrence(
                Instant.parse("2026-11-06T18:30:00Z"),
                Instant.parse("2026-11-06T21:00:00Z"),
                false));
  }

  @Test
  void expandsAGroupDetailIntoEveryShowing() throws SourceFetchException {
    String tile = tile("fribourg-list-page-1.html", "7508426103402291566");
    URI detailUrl =
        URI.create(BASE + "/de/p/gruppen/das-fraeulein-von-scuderi-7508426103402291566.html");
    FakePageLoader loader =
        new FakePageLoader()
            .on(landing("fribourg"), listPage(tile))
            .on(detailUrl, fixture("fribourg-detail-group.html"));
    EventfrogEventSource source =
        new EventfrogEventSource(loader, BASE, List.of("fribourg"), 60, 1);

    SourceEvent event = source.fetch(WINDOW).events().getFirst();

    assertThat(event.title()).isEqualTo("Das Fräulein von Scuderi");
    assertThat(event.venueName()).isEqualTo("Kellerpoche, Theater am Stalden");
    assertThat(event.addressLine()).isEqualTo("Rue de la Samaritaine 3, 1700");
    assertThat(event.city()).isEqualTo("Fribourg");
    assertThat(event.categories()).containsExactly("Gruppen");
    assertThat(event.priceInfo()).isNull();
    assertThat(event.occurrences()).hasSize(6);
    assertThat(event.occurrences().getFirst())
        .isEqualTo(new EventOccurrence(Instant.parse("2026-11-06T19:15:00Z"), null, false));
    assertThat(event.occurrences().getLast().start())
        .isEqualTo(Instant.parse("2026-11-15T16:00:00Z"));
  }

  @Test
  void mapsAPaidOfferPrice() throws SourceFetchException {
    String tile = tile("fribourg-list-page-1.html", "7494865309125361084");
    URI detailUrl =
        URI.create(
            BASE
                + "/de/p/comedy/comedy-kabarett/"
                + "er-will-nur-spielen-simon-chen-fribourg-7494865309125361084.html");
    FakePageLoader loader =
        new FakePageLoader()
            .on(landing("fribourg"), listPage(tile))
            .on(detailUrl, fixture("fribourg-detail-paid-event.html"));
    EventfrogEventSource source =
        new EventfrogEventSource(loader, BASE, List.of("fribourg"), 60, 1);
    FetchWindow window =
        new FetchWindow(
            Instant.parse("2026-11-20T00:00:00Z"), Instant.parse("2026-12-01T00:00:00Z"), ZURICH);

    SourceEvent event = source.fetch(window).events().getFirst();

    assertThat(event.priceInfo()).isEqualTo("CHF 25.00–30.00");
    assertThat(event.categories()).containsExactlyInAnyOrder("Comedy", "Comedy & Kabarett");
    assertThat(event.occurrences())
        .containsExactly(new EventOccurrence(Instant.parse("2026-11-28T19:15:00Z"), null, false));
  }

  @Test
  void fallsBackToTheListTileWhenTheDetailPageIsUnreadable() throws SourceFetchException {
    String tile = tile("fribourg-list-page-1.html", "7513514820114607331");
    URI detailUrl =
        URI.create(
            BASE + "/de/p/kino/kino/film-abend-icf-fribourg-fribourg-7513514820114607331.html");
    FakePageLoader loader =
        new FakePageLoader()
            .on(landing("fribourg"), listPage(tile))
            .on(detailUrl, fixture("malformed-detail.html"));
    EventfrogEventSource source =
        new EventfrogEventSource(loader, BASE, List.of("fribourg"), 60, 1);

    SourceEvent event = source.fetch(WINDOW).events().getFirst();

    assertThat(event.title()).isEqualTo("Film-Abend ICF Fribourg");
    assertThat(event.venueName()).isEqualTo("Chem. Montrevers 1");
    assertThat(event.city()).isEqualTo("Fribourg");
    assertThat(event.categories()).containsExactly("Kino");
    assertThat(event.priceInfo()).isNull();
    assertThat(event.status()).isEqualTo(EventStatus.UNKNOWN);
    assertThat(event.occurrences())
        .containsExactly(new EventOccurrence(Instant.parse("2026-11-06T18:30:00Z"), null, false));
  }

  @Test
  void dropsTilesWhoseOccurrenceIsOutsideTheWindow() throws SourceFetchException {
    FakePageLoader loader =
        new FakePageLoader()
            .on(landing("fribourg"), fixture("fribourg-list-page-1.html"))
            .on(listPage("fribourg", 2), fixture("fribourg-list-page-2.html"));
    EventfrogEventSource source =
        new EventfrogEventSource(loader, BASE, List.of("fribourg"), 60, 0);
    FetchWindow localDay =
        new FetchWindow(
            Instant.parse("2026-11-06T00:00:00Z"), Instant.parse("2026-11-07T00:00:00Z"), ZURICH);

    List<SourceEvent> events = source.fetch(localDay).events();

    assertThat(events)
        .extracting(SourceEvent::title)
        .contains("Film-Abend ICF Fribourg", "Das Fräulein von Scuderi")
        .doesNotContain("Er will nur spielen – Simon Chen");
    assertThat(events)
        .allSatisfy(event ->
            assertThat(event.occurrences())
                .anyMatch(occurrence ->
                    occurrence.start().isBefore(localDay.to())
                        && (occurrence.end() == null
                            || !occurrence.end().isBefore(localDay.from()))));
  }

  @Test
  void deduplicatesRepeatedLinksWithinAPage() {
    String tile = tile("fribourg-list-page-1.html", "7513514820114607331");
    EventfrogParser parser = new EventfrogParser();

    assertThat(parser.parseTiles(listPage(tile, tile))).hasSize(1);
  }

  @Test
  void fetchesDetailsOnlyForInWindowCandidates() throws SourceFetchException {
    URI film =
        URI.create(
            BASE + "/de/p/kino/kino/film-abend-icf-fribourg-fribourg-7513514820114607331.html");
    URI group =
        URI.create(BASE + "/de/p/gruppen/das-fraeulein-von-scuderi-7508426103402291566.html");
    FakePageLoader loader =
        new FakePageLoader()
            .on(landing("fribourg"), fixture("fribourg-list-page-1.html"))
            .on(listPage("fribourg", 2), fixture("fribourg-list-page-2.html"))
            .on(film, fixture("fribourg-detail-event.html"))
            .on(group, fixture("fribourg-detail-group.html"));
    EventfrogEventSource source =
        new EventfrogEventSource(loader, BASE, List.of("fribourg"), 60, 10);
    FetchWindow localDay =
        new FetchWindow(
            Instant.parse("2026-11-06T00:00:00Z"), Instant.parse("2026-11-07T00:00:00Z"), ZURICH);

    source.fetch(localDay);

    List<URI> detailRequests =
        loader.requests().stream().filter(uri -> uri.getPath().startsWith("/de/p/")).toList();
    assertThat(detailRequests).containsExactly(group, film);
  }

  @Test
  void failsWhenTheRequestCapIsReachedBeforeTheCityPagesEnd() {
    FakePageLoader loader =
        new FakePageLoader().on(landing("fribourg"), fixture("fribourg-list-page-1.html"));
    EventfrogEventSource source = new EventfrogEventSource(loader, BASE, List.of("fribourg"), 1, 0);

    assertThatThrownBy(() -> source.fetch(WINDOW))
        .isInstanceOf(SourceFetchException.class)
        .hasMessageContaining("request cap");
    assertThat(loader.requests()).hasSize(1);
  }

  @Test
  void skipsAMalformedTileWithoutFailingTheCityPage() throws SourceFetchException {
    FakePageLoader loader =
        new FakePageLoader()
            .on(landing("fribourg"), fixture("fribourg-list-page-1.html"))
            .on(listPage("fribourg", 2), fixture("fribourg-list-page-2.html"));
    EventfrogEventSource source =
        new EventfrogEventSource(loader, BASE, List.of("fribourg"), 60, 0);

    List<SourceEvent> events = source.fetch(WINDOW).events();

    assertThat(events).noneMatch(event -> event.title().equals("Defektes Event ohne Datum"));
    assertThat(events).isNotEmpty();
  }

  private static String fixture(String name) {
    try (InputStream in =
        EventfrogEventSourceTest.class.getResourceAsStream("/fixtures/eventfrog/" + name)) {
      Objects.requireNonNull(in, "fixture " + name);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String tile(String fixture, String needle) {
    Matcher matcher =
        Pattern.compile(
                "<a href=\"/de/p/[^\"]*\"[^>]*class=\"event-list__events__tile[^\"]*\"[^>]*>.*?</a>",
                Pattern.DOTALL)
            .matcher(fixture(fixture));
    while (matcher.find()) {
      if (matcher.group().contains(needle)) {
        return matcher.group();
      }
    }
    throw new IllegalArgumentException("no tile containing " + needle + " in " + fixture);
  }

  private static String listPage(String... tiles) {
    return "<!DOCTYPE html><html><body>" + String.join("\n", tiles) + "</body></html>";
  }

  private static URI landing(String city) {
    return URI.create(BASE + "/de/events/" + city);
  }

  private static URI listPage(String city, int page) {
    return URI.create(
        BASE + "/de/event-list/" + city + ".html?shop_recpage=" + page + "&perPage=48");
  }

  /** Deterministic fake loader: exact URI mappings, an empty per-page fallback. */
  private static final class FakePageLoader implements PageLoader {

    private final Map<String, String> pages = new HashMap<>();
    private final List<URI> requests = new ArrayList<>();
    private String fallback = "";

    FakePageLoader on(URI uri, String body) {
      pages.put(uri.toString(), body);
      return this;
    }

    @Override
    public String load(URI uri) {
      requests.add(uri);
      String body = pages.get(uri.toString());
      return body == null ? fallback : body;
    }

    List<URI> requests() {
      return List.copyOf(requests);
    }
  }
}
