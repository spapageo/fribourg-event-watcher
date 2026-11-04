package ch.fribourgevents.source.bka;

import ch.fribourgevents.domain.FetchWindow;
import ch.fribourgevents.source.SourceFetchException;
import ch.fribourgevents.source.browser.RateLimiter;
import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Paged reader for the bka.ch agenda JSON API: {@code GET /api/events?limit=100&page=N&date_from=
 * YYYY-MM-DD&date_to=YYYY-MM-DD}.
 *
 * <p>The site's React agenda calls this endpoint, so it is the structured form of the same data the
 * rendered HTML shows — including times, addresses, descriptions, categories, and prices that the
 * agenda cards omit. Paging stops on the first empty page; a hard cap of {@value #MAX_REQUESTS}
 * requests is the safety net. The window is also sent as {@code date_from}/{@code date_to} so a
 * normal run reads 2–4 pages instead of the whole agenda, and the adapter re-checks overlap
 * client-side because the date parameters are calendar days, not instants.
 *
 * <p>Every request is paced through the shared {@link RateLimiter}. Not thread-safe; create one per
 * run.
 */
public final class BkaClient {

  /** Hard cap on requests per crawl; the spec allows at most 60 per source and run. */
  static final int MAX_REQUESTS = 60;

  static final int PAGE_SIZE = 100;

  private static final String EVENTS_PATH = "/api/events";
  private static final String USER_AGENT = "fribourg-events-digest/1.0";

  private final String baseUrl;
  private final RestClient http;
  private final RateLimiter rateLimiter;
  private final int maxRequests;
  private final JsonMapper json;

  /**
   * @param baseUrl origin of the agenda host, e.g. {@code https://www.bka.ch} (injectable so tests
   *     can point at WireMock)
   * @param http client used for every request
   * @param rateLimiter per-host pacing shared with the other sources
   */
  public BkaClient(String baseUrl, RestClient http, RateLimiter rateLimiter) {
    this(baseUrl, http, rateLimiter, MAX_REQUESTS);
  }

  BkaClient(String baseUrl, RestClient http, RateLimiter rateLimiter, int maxRequests) {
    this.baseUrl = stripTrailingSlash(Objects.requireNonNull(baseUrl, "baseUrl"));
    this.http = Objects.requireNonNull(http, "http");
    this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter");
    if (maxRequests <= 0) {
      throw new IllegalArgumentException("maxRequests must be positive, was " + maxRequests);
    }
    this.maxRequests = maxRequests;
    this.json =
        JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
  }

  /**
   * Fetches every agenda page whose rows may overlap {@code window}, flattening the month groups.
   *
   * @return the rows plus the number of page requests it took, for the run's source-sync stats
   * @throws SourceFetchException when a request fails, the payload cannot be read, or the request
   *     cap is reached before the first empty page
   */
  public Crawl fetchAll(FetchWindow window) throws SourceFetchException {
    Objects.requireNonNull(window, "window");
    List<BkaEvent> rows = new ArrayList<>();
    for (int page = 1; page <= maxRequests; page++) {
      List<BkaEvent> pageRows = fetchPage(window, page).events();
      if (pageRows.isEmpty()) {
        return new Crawl(rows, page);
      }
      rows.addAll(pageRows);
    }
    throw new SourceFetchException(
        "bka: request cap reached (" + maxRequests + " pages) before the empty agenda page");
  }

  /** Rows together with the number of page requests that produced them. */
  public record Crawl(List<BkaEvent> rows, int pages) {

    public Crawl {
      rows = List.copyOf(rows);
    }
  }

  private BkaPage fetchPage(FetchWindow window, int page) throws SourceFetchException {
    URI uri = pageUri(window, page);
    try {
      rateLimiter.acquire(uri);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SourceFetchException("bka: interrupted while pacing agenda page " + page, e);
    }

    String body;
    try {
      body = http.get().uri(uri).header("User-Agent", USER_AGENT).retrieve().body(String.class);
    } catch (RestClientException e) {
      throw new SourceFetchException("bka: request failed for agenda page " + page, e);
    }
    if (body == null || body.isBlank()) {
      throw new SourceFetchException("bka: empty body for agenda page " + page);
    }

    BkaPage parsed;
    try {
      parsed = json.readValue(body, BkaPage.class);
    } catch (JacksonException e) {
      throw new SourceFetchException("bka: malformed payload on agenda page " + page, e);
    }
    if (parsed.data() == null) {
      throw new SourceFetchException(
          "bka: malformed top level on agenda page " + page + " (no data list)");
    }
    return parsed;
  }

  private URI pageUri(FetchWindow window, int page) {
    LocalDate from = window.fromDate();
    LocalDate to = window.toDate();
    return URI.create(
        baseUrl
            + EVENTS_PATH
            + "?limit="
            + PAGE_SIZE
            + "&page="
            + page
            + "&date_from="
            + from
            + "&date_to="
            + to);
  }

  private static String stripTrailingSlash(String value) {
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }
}
