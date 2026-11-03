package ch.fribourgevents.source.insitu;

import ch.fribourgevents.source.SourceFetchException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Paged reader for the open insitu.live agenda API: {@code GET /api/events?limit=100&page=N}.
 *
 * <p>Paging follows {@code view.next} until upstream stops advertising another page, with a hard
 * cap of {@value #MAX_REQUESTS} requests per crawl. The cap is a safety net, not the normal exit.
 *
 * <p>Quirks that shape this client (see {@code docs/sources.md}): upstream ignores {@code limit}
 * and always returns 30 rows per page, and the {@code date_from}/{@code date_to} query parameters
 * make the endpoint answer HTTP 500, so the window is filtered client-side.
 *
 * <p>Not thread-safe; create one per run.
 */
public final class InsituClient {

  /** Hard cap on requests per crawl; the spec allows at most 60 per source and run. */
  static final int MAX_REQUESTS = 60;

  private static final String EVENTS_PATH = "/api/events";
  private static final String USER_AGENT = "fribourg-events-digest/1.0";
  private static final int REQUESTED_LIMIT = 100;

  private final String baseUrl;
  private final RestClient http;
  private final JsonMapper json;

  /**
   * @param baseUrl origin of the agenda host, e.g. {@code https://insitu.live} (injectable so tests
   *     can point at WireMock)
   * @param http client used for every request
   */
  public InsituClient(String baseUrl, RestClient http) {
    this.baseUrl = stripTrailingSlash(Objects.requireNonNull(baseUrl, "baseUrl"));
    this.http = Objects.requireNonNull(http, "http");
    this.json =
        JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
  }

  /**
   * Fetches every agenda page in order and flattens the month groups into rows.
   *
   * @return the rows plus the number of page requests it took, for the run's source-sync stats
   * @throws SourceFetchException when a request fails, the payload cannot be read, or the request
   *     cap is reached before the last page
   */
  public Crawl fetchAllRows() throws SourceFetchException {
    List<InsituEvent> rows = new ArrayList<>();
    for (int page = 1; page <= MAX_REQUESTS; page++) {
      InsituPage parsed = fetchPage(page);
      rows.addAll(parsed.events());
      if (!parsed.hasNext()) {
        return new Crawl(rows, page);
      }
    }
    throw new SourceFetchException(
        "insitu: request cap reached (" + MAX_REQUESTS + " pages) before the last agenda page");
  }

  /** Rows together with the number of page requests that produced them. */
  public record Crawl(List<InsituEvent> rows, int pages) {

    public Crawl {
      rows = List.copyOf(rows);
    }
  }

  private InsituPage fetchPage(int page) throws SourceFetchException {
    String body;
    try {
      body =
          http.get()
              .uri(
                  URI.create(baseUrl + EVENTS_PATH + "?limit=" + REQUESTED_LIMIT + "&page=" + page))
              .header("User-Agent", USER_AGENT)
              .retrieve()
              .body(String.class);
    } catch (RestClientException e) {
      throw new SourceFetchException("insitu: request failed for agenda page " + page, e);
    }
    if (body == null || body.isBlank()) {
      throw new SourceFetchException("insitu: empty body for agenda page " + page);
    }
    InsituPage parsed;
    try {
      parsed = json.readValue(body, InsituPage.class);
    } catch (JacksonException e) {
      throw new SourceFetchException("insitu: malformed payload on agenda page " + page, e);
    }
    if (parsed.member() == null) {
      throw new SourceFetchException(
          "insitu: malformed top level on agenda page " + page + " (no member list)");
    }
    return parsed;
  }

  private static String stripTrailingSlash(String value) {
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }
}
