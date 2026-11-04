package ch.fribourgevents.source.eventfrog;

import ch.fribourgevents.domain.EventOccurrence;
import ch.fribourgevents.domain.EventStatus;
import ch.fribourgevents.domain.FetchWindow;
import ch.fribourgevents.domain.SourceEvent;
import ch.fribourgevents.domain.SourceId;
import ch.fribourgevents.source.EventSource;
import ch.fribourgevents.source.PageLoader;
import ch.fribourgevents.source.SourceFetch;
import ch.fribourgevents.source.SourceFetchException;
import ch.fribourgevents.source.eventfrog.EventfrogParser.Detail;
import ch.fribourgevents.source.eventfrog.EventfrogParser.Offer;
import ch.fribourgevents.source.eventfrog.EventfrogParser.Place;
import ch.fribourgevents.source.eventfrog.EventfrogParser.Tile;
import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adapter for eventfrog.ch's two city agendas, {@code /de/events/fribourg} and {@code
 * /de/events/bern}.
 *
 * <p>The landing page is server-rendered: its {@code a.event-list__events__tile} anchors already
 * carry the title, local date/time label and {@code "venue, city (CH)"} line. The site's "Mehr
 * Events anzeigen" button loads {@code /de/event-list/<city>.html?shop_recpage=N&perPage=48}
 * fragments with the same markup, so pagination is a sequence of list pages rather than a browser
 * interaction.
 *
 * <p>Detail pages are static HTML with a schema.org {@code Event} JSON-LD block: exact instants,
 * address/coordinates, description, categories and prices. They are optional enrichment. The spec
 * caps a source at 60 requests per run, and a city page can list hundreds of events, so the adapter
 * spends a bounded detail budget on the earliest in-window candidates and keeps the list-derived
 * occurrence for the rest. A detail page that fails or cannot be parsed never fails the source;
 * that one event falls back to its tile.
 *
 * <p>Every request goes through the injected {@link PageLoader}, which owns Selenium and the
 * per-host rate limit. A source that cannot read a list page fails with {@link
 * SourceFetchException}; a malformed tile is skipped and logged.
 */
public final class EventfrogEventSource implements EventSource {

  private static final Logger log = LoggerFactory.getLogger(EventfrogEventSource.class);

  static final String DEFAULT_BASE_URL = EventfrogParser.BASE_URL;

  /** The two city agendas this source owns, in fetch order. */
  static final List<String> DEFAULT_CITIES = List.of("fribourg", "bern");

  /** Spec allowance for every scraped source. */
  static final int MAX_REQUESTS = 60;

  /** The site's fixed page size for the list fragments. */
  static final int EVENTS_PER_PAGE = 48;

  /** Success criterion: at least this many events per city page. */
  static final int MIN_EVENTS_PER_CITY = 50;

  /**
   * Detail pages are one browser page each and a list can hold hundreds of events, so enrichment is
   * capped at the earliest in-window candidates. The rest keep their list-derived occurrence.
   */
  static final int DEFAULT_DETAIL_BUDGET = 30;

  private static final int MAX_DESCRIPTION_CHARS = 2000;
  private static final Pattern EVENT_ID = Pattern.compile("-(\\d+)\\.html$");

  private final PageLoader pageLoader;
  private final EventfrogParser parser;
  private final String baseUrl;
  private final List<String> cities;
  private final int maxRequests;
  private final int detailBudget;

  /** Production adapter: both city pages, the spec's 60-request cap, bounded detail enrichment. */
  public EventfrogEventSource(PageLoader pageLoader) {
    this(pageLoader, DEFAULT_BASE_URL, DEFAULT_CITIES, MAX_REQUESTS, DEFAULT_DETAIL_BUDGET);
  }

  EventfrogEventSource(
      PageLoader pageLoader,
      String baseUrl,
      List<String> cities,
      int maxRequests,
      int detailBudget) {
    this.pageLoader = Objects.requireNonNull(pageLoader, "pageLoader");
    this.parser = new EventfrogParser();
    this.baseUrl = stripTrailingSlash(Objects.requireNonNull(baseUrl, "baseUrl"));
    this.cities = List.copyOf(Objects.requireNonNull(cities, "cities"));
    if (this.cities.isEmpty()) {
      throw new IllegalArgumentException("cities must not be empty");
    }
    if (maxRequests <= 0) {
      throw new IllegalArgumentException("maxRequests must be positive, was " + maxRequests);
    }
    if (detailBudget < 0) {
      throw new IllegalArgumentException("detailBudget must not be negative, was " + detailBudget);
    }
    this.maxRequests = maxRequests;
    this.detailBudget = detailBudget;
  }

  @Override
  public SourceId id() {
    return SourceId.EVENTFROG;
  }

  @Override
  public SourceFetch fetch(FetchWindow window) throws SourceFetchException {
    Objects.requireNonNull(window, "window");
    PageSession session = new PageSession();
    Map<URI, Tile> tiles = new LinkedHashMap<>();

    for (String city : cities) {
      Set<URI> citySeen = new LinkedHashSet<>();
      List<Tile> pageTiles = readLandingPage(session, tiles, city, citySeen);
      int page = 2;
      while (!pageTiles.isEmpty() && citySeen.size() < MIN_EVENTS_PER_CITY) {
        pageTiles = parser.parseTiles(session.load(listPageUri(city, page)));
        int before = citySeen.size();
        addTiles(tiles, citySeen, pageTiles);
        if (pageTiles.isEmpty() || citySeen.size() == before) {
          break;
        }
        page++;
      }
      log.debug("eventfrog: {} unique events from {}", citySeen.size(), city);
    }

    List<Candidate> candidates = new ArrayList<>();
    for (Tile tile : tiles.values()) {
      parser
          .listOccurrence(tile, window)
          .filter(occurrence -> overlaps(occurrence, window))
          .ifPresent(occurrence -> candidates.add(new Candidate(tile, occurrence)));
    }
    candidates.sort(Comparator.comparing(candidate -> candidate.occurrence().start()));

    List<SourceEvent> events = new ArrayList<>();
    int detailsRead = 0;
    for (Candidate candidate : candidates) {
      SourceEvent event = null;
      if (detailsRead < detailBudget && session.requests() < maxRequests) {
        detailsRead++;
        event = enrich(candidate.tile(), window, session);
      }
      if (event == null) {
        event = fromList(candidate.tile(), candidate.occurrence());
      }
      if (overlaps(event, window)) {
        events.add(event);
      }
    }

    log.debug(
        "eventfrog: {} candidates, {} returned, {} requests",
        candidates.size(),
        events.size(),
        session.requests());
    return new SourceFetch(events, session.requests());
  }

  private List<Tile> readLandingPage(
      PageSession session, Map<URI, Tile> allTiles, String city, Set<URI> citySeen)
      throws SourceFetchException {
    String html = session.load(landingUri(city));
    List<Tile> pageTiles = parser.parseTiles(html);
    List<EventfrogParser.Locality> localities = parser.parseTopLocalities(html);
    log.debug(
        "eventfrog: {} page 1 has {} tiles and {} top localities",
        city,
        pageTiles.size(),
        localities.size());
    addTiles(allTiles, citySeen, pageTiles);
    return pageTiles;
  }

  private SourceEvent enrich(Tile tile, FetchWindow window, PageSession session) {
    try {
      String html = session.load(tile.url());
      Optional<Detail> detail = parser.parseDetail(html);
      if (detail.isEmpty()) {
        log.warn("eventfrog: detail page without JSON-LD event: {}", tile.url());
        return null;
      }
      return fromDetail(detail.get(), tile, window);
    } catch (SourceFetchException | RuntimeException e) {
      log.warn("eventfrog: detail page failed for {}: {}", tile.url(), e.toString());
      return null;
    }
  }

  private SourceEvent fromDetail(Detail detail, Tile tile, FetchWindow window) {
    List<EventOccurrence> occurrences = parser.detailOccurrences(detail, window.zone());
    if (occurrences.isEmpty()) {
      return null;
    }
    URI url = detail.url() != null ? detail.url() : tile.url();
    String title = firstNonBlank(detail.name(), tile.title());
    if (title == null) {
      return null;
    }

    Place place = detail.location();
    String venue = place == null ? null : trimToNull(place.name());
    String city = place == null ? null : trimToNull(place.city());
    if (venue == null) {
      venue = parser.venueFromLocation(tile.locationText());
    }
    if (city == null) {
      city = parser.cityFromLocation(tile.locationText());
    }
    String address = place == null ? null : joinAddress(place.street(), place.postalCode());
    BigDecimal latitude = place == null ? null : decimal(place.latitude());
    BigDecimal longitude = place == null ? null : decimal(place.longitude());
    String country = place == null ? null : trimToNull(place.country());

    Set<String> categories = parser.categoriesFromKeywords(detail.keywords());
    if (categories.isEmpty()) {
      categories = parser.categoriesFromPath(url);
    }

    return new SourceEvent(
        SourceId.EVENTFROG,
        eventId(url),
        title,
        plainText(detail.description()),
        venue,
        address,
        city,
        null,
        country,
        latitude,
        longitude,
        url,
        categories,
        priceInfo(detail),
        status(detail.status()),
        occurrences);
  }

  private SourceEvent fromList(Tile tile, EventOccurrence occurrence) {
    return new SourceEvent(
        SourceId.EVENTFROG,
        eventId(tile.url()),
        tile.title(),
        null,
        parser.venueFromLocation(tile.locationText()),
        null,
        parser.cityFromLocation(tile.locationText()),
        null,
        null,
        null,
        null,
        tile.url(),
        parser.categoriesFromPath(tile.url()),
        null,
        EventStatus.UNKNOWN,
        List.of(occurrence));
  }

  private static String priceInfo(Detail detail) {
    List<Offer> offers = detail.offers();
    if (offers.isEmpty()) {
      return detail.accessibleForFree() ? "Free entry" : null;
    }

    String currency = "CHF";
    BigDecimal low = null;
    BigDecimal high = null;
    for (Offer offer : offers) {
      if (trimToNull(offer.currency()) != null) {
        currency = offer.currency().strip();
      }
      BigDecimal offerLow = decimal(offer.lowPrice());
      BigDecimal offerHigh = decimal(offer.highPrice());
      BigDecimal single = decimal(offer.price());
      if (single != null) {
        offerLow = offerLow == null ? single : offerLow;
        offerHigh = offerHigh == null ? single : offerHigh;
      }
      if (offerLow != null && (low == null || offerLow.compareTo(low) < 0)) {
        low = offerLow;
      }
      if (offerHigh != null && (high == null || offerHigh.compareTo(high) > 0)) {
        high = offerHigh;
      }
    }

    BigDecimal from = low != null ? low : high;
    BigDecimal to = high != null ? high : low;
    if (detail.accessibleForFree()
        || (from != null && from.signum() == 0 && (to == null || to.signum() == 0))) {
      return "Free entry";
    }
    if (from == null) {
      return null;
    }
    if (to == null || from.compareTo(to) == 0) {
      return currency + " " + from.toPlainString();
    }
    return currency + " " + from.toPlainString() + "–" + to.toPlainString();
  }

  private static EventStatus status(String wire) {
    if (wire == null) {
      return EventStatus.UNKNOWN;
    }
    String normalized = wire.strip().toLowerCase(Locale.ROOT);
    if (normalized.endsWith("eventscheduled") || normalized.endsWith("eventmovedonline")) {
      return EventStatus.CONFIRMED;
    }
    if (normalized.endsWith("eventcancelled") || normalized.endsWith("eventcanceled")) {
      return EventStatus.CANCELLED;
    }
    if (normalized.endsWith("eventpostponed")) {
      return EventStatus.TENTATIVE;
    }
    return EventStatus.fromWire(wire);
  }

  private static String eventId(URI url) {
    String path = url.getPath();
    String file = path == null ? "" : path.substring(path.lastIndexOf('/') + 1);
    Matcher matcher = EVENT_ID.matcher(file);
    if (matcher.find()) {
      return matcher.group(1);
    }
    return file.isBlank() ? url.toString() : file;
  }

  private static String joinAddress(String street, String postalCode) {
    String cleanStreet = trimToNull(street);
    String cleanPostalCode = trimToNull(postalCode);
    if (cleanStreet == null) {
      return cleanPostalCode;
    }
    return cleanPostalCode == null ? cleanStreet : cleanStreet + ", " + cleanPostalCode;
  }

  private static BigDecimal decimal(Double value) {
    return value == null ? null : BigDecimal.valueOf(value);
  }

  private static BigDecimal decimal(String value) {
    String text = trimToNull(value);
    if (text == null) {
      return null;
    }
    try {
      return new BigDecimal(text);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static String plainText(String html) {
    if (html == null) {
      return null;
    }
    String text = Jsoup.parse(html).text().strip();
    if (text.isEmpty()) {
      return null;
    }
    return text.length() > MAX_DESCRIPTION_CHARS ? text.substring(0, MAX_DESCRIPTION_CHARS) : text;
  }

  private static boolean overlaps(EventOccurrence occurrence, FetchWindow window) {
    if (!occurrence.start().isBefore(window.to())) {
      return false;
    }
    return !occurrence.start().isBefore(window.from())
        || (occurrence.end() != null && !occurrence.end().isBefore(window.from()));
  }

  private static boolean overlaps(SourceEvent event, FetchWindow window) {
    return event.occurrences().stream().anyMatch(occurrence -> overlaps(occurrence, window));
  }

  private static void addTiles(Map<URI, Tile> allTiles, Set<URI> citySeen, List<Tile> pageTiles) {
    for (Tile tile : pageTiles) {
      if (citySeen.add(tile.url())) {
        allTiles.putIfAbsent(tile.url(), tile);
      }
    }
  }

  private URI landingUri(String city) {
    return URI.create(baseUrl + "/de/events/" + city);
  }

  private URI listPageUri(String city, int page) {
    return URI.create(
        baseUrl
            + "/de/event-list/"
            + city
            + ".html?shop_recpage="
            + page
            + "&perPage="
            + EVENTS_PER_PAGE);
  }

  private static String firstNonBlank(String first, String second) {
    String value = trimToNull(first);
    return value != null ? value : trimToNull(second);
  }

  private static String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.strip();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static String stripTrailingSlash(String value) {
    String stripped = value;
    while (stripped.endsWith("/")) {
      stripped = stripped.substring(0, stripped.length() - 1);
    }
    return stripped;
  }

  private record Candidate(Tile tile, EventOccurrence occurrence) {}

  /** One fetch's request counter; enforces the spec's per-source cap. */
  private final class PageSession {

    private int requests;

    String load(URI uri) throws SourceFetchException {
      if (requests >= maxRequests) {
        throw new SourceFetchException(
            "eventfrog: request cap reached (" + maxRequests + ") before the city pages ended");
      }
      requests++;
      return pageLoader.load(uri);
    }

    int requests() {
      return requests;
    }
  }
}
