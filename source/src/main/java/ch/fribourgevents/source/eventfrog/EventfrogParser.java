package ch.fribourgevents.source.eventfrog;

import ch.fribourgevents.domain.EventOccurrence;
import ch.fribourgevents.domain.FetchWindow;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Month;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
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
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pure parsers for the two eventfrog.ch formats the adapter reads.
 *
 * <p>The city page and its {@code /de/event-list/...} load-more fragments are server-rendered HTML:
 * one {@code a.event-list__events__tile} per event, with a title, a local date/time label, and a
 * "venue, city (CH)" line. The page also embeds a {@code top-localities} JSON script, but that is
 * navigation data (the list of city pages), not event data — a research assumption corrected by the
 * live site.
 *
 * <p>Each detail page carries a schema.org {@code Event} JSON-LD block with the exact ISO instants,
 * the address/coordinates, the description, categories and offers. Group pages carry the whole run
 * in a {@code subEvent} array, so one request yields every showing.
 *
 * <p>Nothing here touches the network or a browser: a malformed tile is skipped, and a malformed
 * detail block is reported as an {@link IllegalArgumentException} so the adapter can fall back to
 * the list data for that one event.
 */
final class EventfrogParser {

  /** Public origin; detail links are absolute, list links are root-relative. */
  static final String BASE_URL = "https://eventfrog.ch";

  private static final String EVENT_PATH = "/de/p/";

  private static final Pattern TIME =
      Pattern.compile("(\\d{1,2})\\.\\s+([\\p{L}]+)\\.?,?\\s+(\\d{1,2}):(\\d{2})");
  private static final Pattern DAY = Pattern.compile("\\d{1,2}");

  private static final Map<String, Month> MONTHS = months();

  private final JsonMapper json = JsonMapper.builder().build();

  /** All unique event tiles on one list page, in upstream order. */
  List<Tile> parseTiles(String html) {
    Document document = Jsoup.parse(html == null ? "" : html);
    List<Tile> tiles = new ArrayList<>();
    Set<URI> seen = new LinkedHashSet<>();
    for (Element anchor : document.select("a.event-list__events__tile")) {
      String href = trimToNull(anchor.attr("href"));
      if (href == null || !href.startsWith(EVENT_PATH)) {
        continue;
      }
      String title = text(anchor, ".event-list__events__tile__content__infos__title");
      if (title == null) {
        continue;
      }
      URI url = absoluteUri(href);
      if (url == null || !seen.add(url)) {
        continue;
      }
      String timeText = text(anchor, ".event-list__events__tile__content__infos__time");
      String locationText = text(anchor, ".event-list__events__tile__content__infos__location");
      String monthText = text(anchor, ".event-list__events__tile__content__date__month");
      String dayText = text(anchor, ".event-list__events__tile__content__date__day");
      tiles.add(new Tile(url, title, timeText, locationText, monthText, dayText));
    }
    return tiles;
  }

  /** The city navigation embedded as {@code script#top-localities}, parsed but not event data. */
  List<Locality> parseTopLocalities(String html) {
    Document document = Jsoup.parse(html == null ? "" : html);
    Element script = document.selectFirst("script#top-localities");
    if (script == null) {
      return List.of();
    }
    try {
      JsonNode root = json.readTree(script.data());
      if (root == null || !root.isArray()) {
        return List.of();
      }
      List<Locality> localities = new ArrayList<>();
      for (JsonNode node : root.values()) {
        String title = text(node, "title");
        URI href = absoluteUri(text(node, "href"));
        if (title != null && href != null) {
          localities.add(new Locality(title, href));
        }
      }
      return List.copyOf(localities);
    } catch (JacksonException e) {
      return List.of();
    }
  }

  /**
   * The first schema.org {@code Event} JSON-LD block on a detail page.
   *
   * @throws IllegalArgumentException when a JSON-LD block exists but cannot be read; the adapter
   *     logs it and keeps the list-derived event instead of failing the source
   */
  Optional<Detail> parseDetail(String html) {
    Document document = Jsoup.parse(html == null ? "" : html);
    boolean malformed = false;
    for (Element script : document.select("script[type=application/ld+json]")) {
      String raw = script.data();
      if (raw == null || raw.isBlank()) {
        continue;
      }
      JsonNode root;
      try {
        root = json.readTree(raw);
      } catch (JacksonException e) {
        malformed = true;
        continue;
      }
      JsonNode event = findEvent(root);
      if (event != null) {
        return Optional.of(detail(event));
      }
    }
    if (malformed) {
      throw new IllegalArgumentException("eventfrog: malformed JSON-LD detail");
    }
    return Optional.empty();
  }

  /**
   * The occurrence implied by a list tile. Exact instants come from detail pages; this estimate is
   * what lets the adapter filter by window and stays in place when a detail request fails.
   */
  Optional<EventOccurrence> listOccurrence(Tile tile, FetchWindow window) {
    Objects.requireNonNull(tile, "tile");
    Objects.requireNonNull(window, "window");
    ZoneId zone = window.zone();
    LocalDate reference = window.fromDate();

    List<TimeMatch> times = parseTimes(tile.timeText());
    if (!times.isEmpty()) {
      LocalDate startDate =
          resolveDate(times.getFirst().month(), times.getFirst().day(), reference);
      if (startDate == null) {
        return Optional.empty();
      }
      LocalTime startTime = times.getFirst().time();
      Instant start = startDate.atTime(startTime).atZone(zone).toInstant();
      Instant end = null;
      if (times.size() > 1) {
        LocalDate endDate = resolveDate(times.get(1).month(), times.get(1).day(), reference);
        if (endDate == null) {
          return Optional.empty();
        }
        if (endDate.isBefore(startDate)) {
          endDate = endDate.plusYears(1);
        }
        end = endDate.atTime(times.get(1).time()).atZone(zone).toInstant();
      }
      return Optional.of(new EventOccurrence(start, end, startTime.equals(LocalTime.MIDNIGHT)));
    }

    DateSpan span = parseSpan(tile.monthText(), tile.dayText(), reference);
    if (span == null) {
      return Optional.empty();
    }
    Instant start = span.start().atStartOfDay(zone).toInstant();
    Instant end =
        span.end() == null
            ? null
            : span.end().atTime(LocalTime.of(23, 59, 59)).atZone(zone).toInstant();
    return Optional.of(new EventOccurrence(start, end, true));
  }

  /** Every showing in a detail block, start-sorted and de-duplicated by start instant. */
  List<EventOccurrence> detailOccurrences(Detail detail, ZoneId zone) {
    Objects.requireNonNull(detail, "detail");
    Objects.requireNonNull(zone, "zone");
    List<EventOccurrence> occurrences = new ArrayList<>();
    Set<Instant> seenStarts = new LinkedHashSet<>();
    if (!detail.subEvents().isEmpty()) {
      for (Showing showing : detail.subEvents()) {
        addOccurrence(occurrences, seenStarts, showing.startDate(), showing.endDate(), zone);
      }
    } else {
      addOccurrence(occurrences, seenStarts, detail.startDate(), detail.endDate(), zone);
    }
    occurrences.sort(
        Comparator.comparing(EventOccurrence::start)
            .thenComparing(EventOccurrence::end, Comparator.nullsLast(Comparator.naturalOrder())));
    return List.copyOf(occurrences);
  }

  /** Categories from the {@code /de/p/<rubric>/<subrubric>/...} path, humanised. */
  Set<String> categoriesFromPath(URI url) {
    if (url == null || url.getPath() == null) {
      return Set.of();
    }
    String path = url.getPath();
    int marker = path.indexOf(EVENT_PATH);
    if (marker < 0) {
      return Set.of();
    }
    String rest = path.substring(marker + EVENT_PATH.length());
    String[] segments = rest.split("/");
    Set<String> labels = new LinkedHashSet<>();
    for (int i = 0; i < segments.length - 1; i++) {
      String label = humanize(segments[i]);
      if (label != null) {
        labels.add(label);
      }
    }
    return labels;
  }

  /**
   * Categories from the JSON-LD {@code keywords} prefix, e.g. {@code Comedy / Comedy & Kabarett}.
   */
  Set<String> categoriesFromKeywords(String keywords) {
    String value = trimToNull(keywords);
    if (value == null) {
      return Set.of();
    }
    int comma = value.indexOf(',');
    String prefix = comma < 0 ? value : value.substring(0, comma);
    Set<String> labels = new LinkedHashSet<>();
    for (String part : prefix.split("/")) {
      String label = trimToNull(part);
      if (label != null) {
        labels.add(label);
      }
    }
    return labels;
  }

  /** Venue part of a list tile's {@code "Venue, City (CH)"} line. */
  String venueFromLocation(String location) {
    String value = withoutCountry(location);
    if (value == null) {
      return null;
    }
    int lastComma = value.lastIndexOf(',');
    return lastComma < 0 ? null : trimToNull(value.substring(0, lastComma));
  }

  /** City part of a list tile's {@code "Venue, City (CH)"} line. */
  String cityFromLocation(String location) {
    String value = withoutCountry(location);
    if (value == null) {
      return null;
    }
    int lastComma = value.lastIndexOf(',');
    return lastComma < 0 ? value : trimToNull(value.substring(lastComma + 1));
  }

  private static void addOccurrence(
      List<EventOccurrence> occurrences,
      Set<Instant> seenStarts,
      String startText,
      String endText,
      ZoneId zone) {
    Instant start = instant(startText, zone);
    if (start == null || !seenStarts.add(start)) {
      return;
    }
    Instant end = instant(endText, zone);
    if (end != null && end.isBefore(start)) {
      end = null;
    }
    boolean allDay = start.atZone(zone).toLocalTime().equals(LocalTime.MIDNIGHT);
    occurrences.add(new EventOccurrence(start, end, allDay));
  }

  private static Instant instant(String value, ZoneId zone) {
    String text = trimToNull(value);
    if (text == null) {
      return null;
    }
    try {
      return OffsetDateTime.parse(text).toInstant();
    } catch (DateTimeParseException ignored) {
      // Some publishers emit a date without a time; treat it as local midnight.
    }
    try {
      return LocalDate.parse(text).atStartOfDay(zone).toInstant();
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  private static List<TimeMatch> parseTimes(String text) {
    if (text == null || text.isBlank()) {
      return List.of();
    }
    List<TimeMatch> matches = new ArrayList<>(2);
    Matcher matcher = TIME.matcher(text);
    while (matcher.find() && matches.size() < 2) {
      Month month = month(matcher.group(2));
      if (month == null) {
        continue;
      }
      try {
        matches.add(
            new TimeMatch(
                month,
                Integer.parseInt(matcher.group(1)),
                LocalTime.of(
                    Integer.parseInt(matcher.group(3)), Integer.parseInt(matcher.group(4)))));
      } catch (DateTimeException ignored) {
        // Invalid day/time in this label; the date spans are the fallback.
      }
    }
    return matches;
  }

  private static DateSpan parseSpan(String monthText, String dayText, LocalDate reference) {
    String months = trimToNull(monthText);
    String days = trimToNull(dayText);
    if (months == null || days == null) {
      return null;
    }
    List<Integer> dayNumbers = dayNumbers(days);
    if (dayNumbers.isEmpty()) {
      return null;
    }

    String lower = months.toLowerCase(Locale.ROOT);
    if (lower.startsWith("bis ")) {
      Month endMonth = month(months.substring(4));
      if (endMonth == null) {
        return null;
      }
      int day = dayNumbers.getFirst();
      LocalDate start = nextDayOfMonth(day, reference);
      if (start == null) {
        return null;
      }
      LocalDate end = dateInYear(start.getYear(), endMonth, day);
      if (end == null) {
        return null;
      }
      if (end.isBefore(start)) {
        end = dateInYear(start.getYear() + 1, endMonth, day);
      }
      return end == null ? null : new DateSpan(start, end);
    }

    List<String> monthParts = monthParts(months);
    if (monthParts.isEmpty()) {
      return null;
    }
    Month startMonth = month(monthParts.getFirst());
    if (startMonth == null) {
      return null;
    }
    LocalDate start = resolveDate(startMonth, dayNumbers.getFirst(), reference);
    if (start == null) {
      return null;
    }
    if (dayNumbers.size() < 2) {
      return new DateSpan(start, null);
    }
    Month endMonth = monthParts.size() > 1 ? month(monthParts.get(1)) : startMonth;
    if (endMonth == null) {
      return null;
    }
    LocalDate end = resolveDate(endMonth, dayNumbers.get(1), reference);
    if (end == null) {
      return null;
    }
    if (end.isBefore(start)) {
      end = end.plusYears(1);
    }
    return new DateSpan(start, end);
  }

  private static List<String> monthParts(String value) {
    String[] parts = value.split("\\s*[-–]\\s*");
    List<String> result = new ArrayList<>(parts.length);
    for (String part : parts) {
      String trimmed = trimToNull(part);
      if (trimmed != null) {
        result.add(trimmed);
      }
    }
    return result;
  }

  private static List<Integer> dayNumbers(String value) {
    List<Integer> result = new ArrayList<>(2);
    Matcher matcher = DAY.matcher(value);
    while (matcher.find() && result.size() < 2) {
      result.add(Integer.parseInt(matcher.group()));
    }
    return result;
  }

  private static LocalDate resolveDate(Month month, int day, LocalDate reference) {
    LocalDate candidate = dateInYear(reference.getYear(), month, day);
    if (candidate == null) {
      return null;
    }
    return candidate.isBefore(reference)
        ? dateInYear(reference.getYear() + 1, month, day)
        : candidate;
  }

  private static LocalDate dateInYear(int year, Month month, int day) {
    try {
      return LocalDate.of(year, month, day);
    } catch (DateTimeException e) {
      return null;
    }
  }

  private static LocalDate nextDayOfMonth(int day, LocalDate reference) {
    YearMonth start = YearMonth.from(reference);
    for (int i = 0; i < 12; i++) {
      YearMonth candidateMonth = start.plusMonths(i);
      if (day > candidateMonth.lengthOfMonth()) {
        continue;
      }
      LocalDate candidate = candidateMonth.atDay(day);
      if (!candidate.isBefore(reference)) {
        return candidate;
      }
    }
    return null;
  }

  private static Month month(String value) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      return null;
    }
    normalized = normalized.toLowerCase(Locale.ROOT).replace(".", "");
    if (normalized.startsWith("bis ")) {
      normalized = normalized.substring(4).strip();
    }
    int space = normalized.indexOf(' ');
    if (space > 0) {
      normalized = normalized.substring(0, space);
    }
    return MONTHS.get(normalized);
  }

  private static Map<String, Month> months() {
    Map<String, Month> months = new LinkedHashMap<>();
    months.put("jan", Month.JANUARY);
    months.put("januar", Month.JANUARY);
    months.put("feb", Month.FEBRUARY);
    months.put("februar", Month.FEBRUARY);
    months.put("mär", Month.MARCH);
    months.put("märz", Month.MARCH);
    months.put("mar", Month.MARCH);
    months.put("apr", Month.APRIL);
    months.put("april", Month.APRIL);
    months.put("mai", Month.MAY);
    months.put("jun", Month.JUNE);
    months.put("juni", Month.JUNE);
    months.put("jul", Month.JULY);
    months.put("juli", Month.JULY);
    months.put("aug", Month.AUGUST);
    months.put("august", Month.AUGUST);
    months.put("sep", Month.SEPTEMBER);
    months.put("sept", Month.SEPTEMBER);
    months.put("september", Month.SEPTEMBER);
    months.put("okt", Month.OCTOBER);
    months.put("oktober", Month.OCTOBER);
    months.put("nov", Month.NOVEMBER);
    months.put("november", Month.NOVEMBER);
    months.put("dez", Month.DECEMBER);
    months.put("dezember", Month.DECEMBER);
    return Map.copyOf(months);
  }

  private static String withoutCountry(String location) {
    String value = trimToNull(location);
    if (value == null) {
      return null;
    }
    return trimToNull(value.replaceFirst("\\s*\\([A-Za-z]{2}\\)\\s*$", ""));
  }

  private static String humanize(String segment) {
    String decoded;
    try {
      decoded = URLDecoder.decode(segment, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      decoded = segment;
    }
    String value = trimToNull(decoded.replace('-', ' '));
    if (value == null) {
      return null;
    }
    StringBuilder label = new StringBuilder(value.length());
    boolean capitalize = true;
    for (char c : value.toCharArray()) {
      if (Character.isWhitespace(c)) {
        capitalize = true;
        label.append(c);
      } else {
        label.append(capitalize ? Character.toUpperCase(c) : c);
        capitalize = false;
      }
    }
    return label.toString();
  }

  private static JsonNode findEvent(JsonNode node) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      return null;
    }
    if (node.isArray()) {
      for (JsonNode child : node.values()) {
        JsonNode found = findEvent(child);
        if (found != null) {
          return found;
        }
      }
      return null;
    }
    if (!node.isObject()) {
      return null;
    }
    if (hasType(node, "Event")) {
      return node;
    }
    JsonNode graph = node.path("@graph");
    return graph.isObject() || graph.isArray() ? findEvent(graph) : null;
  }

  private static boolean hasType(JsonNode node, String expected) {
    JsonNode type = node.path("@type");
    if (type.isArray()) {
      for (JsonNode value : type.values()) {
        if (expected.equals(value.asString(""))) {
          return true;
        }
      }
      return false;
    }
    return expected.equals(type.asString(""));
  }

  private static Detail detail(JsonNode event) {
    JsonNode location = event.path("location");
    Place place = null;
    if (location.isObject()) {
      JsonNode address = location.path("address");
      JsonNode geo = location.path("geo");
      place =
          new Place(
              text(location, "name"),
              text(address, "streetAddress"),
              text(address, "addressLocality"),
              text(address, "addressRegion"),
              text(address, "addressCountry"),
              text(address, "postalCode"),
              number(geo, "latitude"),
              number(geo, "longitude"));
    }

    List<Offer> offers = new ArrayList<>();
    JsonNode offerNode = event.path("offers");
    if (offerNode.isArray()) {
      for (JsonNode node : offerNode.values()) {
        addOffer(offers, node);
      }
    } else if (offerNode.isObject()) {
      addOffer(offers, offerNode);
    }

    List<Showing> showings = new ArrayList<>();
    JsonNode subEvents = event.path("subEvent");
    if (subEvents.isArray()) {
      for (JsonNode node : subEvents.values()) {
        if (node.isObject()) {
          showings.add(new Showing(text(node, "startDate"), text(node, "endDate")));
        }
      }
    }

    return new Detail(
        text(event, "name"),
        uri(text(event, "url")),
        text(event, "startDate"),
        text(event, "endDate"),
        text(event, "eventStatus"),
        text(event, "description"),
        place,
        List.copyOf(offers),
        event.path("isAccessibleForFree").asBoolean(false),
        text(event, "keywords"),
        List.copyOf(showings));
  }

  private static void addOffer(List<Offer> offers, JsonNode node) {
    if (node == null || !node.isObject()) {
      return;
    }
    offers.add(
        new Offer(
            text(node, "priceCurrency"),
            text(node, "lowPrice"),
            text(node, "highPrice"),
            text(node, "price")));
  }

  private static URI absoluteUri(String href) {
    String value = trimToNull(href);
    if (value == null) {
      return null;
    }
    try {
      URI uri = URI.create(value.startsWith("http") ? value : BASE_URL + value);
      String path = uri.getPath();
      if (path == null || path.isBlank() || uri.getAuthority() == null) {
        return null;
      }
      return URI.create(uri.getScheme() + "://" + uri.getAuthority() + path);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static URI uri(String value) {
    return absoluteUri(value);
  }

  private static String text(Element root, String selector) {
    Element element = root.selectFirst(selector);
    return element == null ? null : trimToNull(element.text());
  }

  private static String text(JsonNode node, String field) {
    return node == null ? null : text(node.path(field));
  }

  private static String text(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return null;
    }
    String value = node.isNumber() ? node.decimalValue().toPlainString() : node.asString("");
    return trimToNull(value);
  }

  private static Double number(JsonNode node, String field) {
    if (node == null) {
      return null;
    }
    JsonNode value = node.path(field);
    if (value.isMissingNode() || value.isNull()) {
      return null;
    }
    if (value.isNumber()) {
      return value.asDouble();
    }
    String text = trimToNull(value.asString(""));
    if (text == null) {
      return null;
    }
    try {
      return Double.valueOf(text);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.strip();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private record TimeMatch(Month month, int day, LocalTime time) {}

  private record DateSpan(LocalDate start, LocalDate end) {}

  /** One list tile before its date is resolved against a window. */
  record Tile(
      URI url,
      String title,
      String timeText,
      String locationText,
      String monthText,
      String dayText) {}

  /** One entry of the page's {@code top-localities} navigation JSON. */
  record Locality(String title, URI href) {}

  /** The schema.org Event fields the adapter needs from a detail page. */
  record Detail(
      String name,
      URI url,
      String startDate,
      String endDate,
      String status,
      String description,
      Place location,
      List<Offer> offers,
      boolean accessibleForFree,
      String keywords,
      List<Showing> subEvents) {

    Detail {
      offers = offers == null ? List.of() : List.copyOf(offers);
      subEvents = subEvents == null ? List.of() : List.copyOf(subEvents);
    }
  }

  record Place(
      String name,
      String street,
      String city,
      String region,
      String country,
      String postalCode,
      Double latitude,
      Double longitude) {}

  record Offer(String currency, String lowPrice, String highPrice, String price) {}

  record Showing(String startDate, String endDate) {}
}
