package ch.fribourgevents.source.bka;

import ch.fribourgevents.domain.EventOccurrence;
import ch.fribourgevents.domain.EventStatus;
import ch.fribourgevents.domain.FetchWindow;
import ch.fribourgevents.domain.SourceEvent;
import ch.fribourgevents.domain.SourceId;
import ch.fribourgevents.source.EventSource;
import ch.fribourgevents.source.SourceFetch;
import ch.fribourgevents.source.SourceFetchException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adapter for the bka.ch agenda JSON API (canton Bern).
 *
 * <p>The API returns one row per showing: a recurring production appears once per date, as a {@code
 * subevent} carrying a {@code mainevent_id}. Rows are therefore grouped by {@code mainevent_id}
 * (falling back to their own {@code id}) into one canonical event whose {@code occurrences} list
 * holds every showing. A multi-day exhibition — one row spanning weeks — becomes a single
 * occurrence, exactly like the insitu adapter, so the payload stays stable while it runs.
 *
 * <p>Rendering choices, all deterministic: the German label wins (the agenda is published in
 * German), descriptions are converted from upstream HTML to plain text, a start at exactly midnight
 * is treated as an all-day marker, and prices become either {@code "Free entry"} or a stable {@code
 * "Designation: CHF price"} list. A row that cannot be parsed is skipped and logged; it never fails
 * the whole source.
 */
public final class BkaEventSource implements EventSource {

  /**
   * Canonical public event page; the API's {@code slug} (or {@code mainevent_slug}) completes it.
   */
  private static final String PUBLIC_EVENT_BASE = "https://www.bka.ch/event/";

  /** Bound on stored descriptions; the digest truncates again for display. */
  private static final int MAX_DESCRIPTION_CHARS = 2000;

  private static final Logger log = LoggerFactory.getLogger(BkaEventSource.class);

  private final BkaClient client;

  public BkaEventSource(BkaClient client) {
    this.client = Objects.requireNonNull(client, "client");
  }

  @Override
  public SourceId id() {
    return SourceId.BKA;
  }

  @Override
  public SourceFetch fetch(FetchWindow window) throws SourceFetchException {
    Objects.requireNonNull(window, "window");
    BkaClient.Crawl crawl = client.fetchAll(window);
    Map<Long, List<BkaEvent>> groups = groupRows(crawl.rows());

    List<SourceEvent> events = new ArrayList<>();
    for (Map.Entry<Long, List<BkaEvent>> group : groups.entrySet()) {
      SourceEvent event;
      try {
        event = map(group.getKey(), group.getValue());
      } catch (RuntimeException e) {
        BkaEvent first = group.getValue().getFirst();
        log.warn(
            "bka: skipping malformed event id={} slug={}: {}",
            group.getKey(),
            first == null ? null : first.slug(),
            e.toString());
        continue;
      }
      if (overlapsWindow(event, window)) {
        events.add(event);
      }
    }
    log.debug(
        "bka: {} agenda rows grouped into {} events, {} inside the window",
        crawl.rows().size(),
        groups.size(),
        events.size());
    return new SourceFetch(events, crawl.pages());
  }

  /** Keys rows by {@code mainevent_id} when present, else by the row's own id. */
  private static Map<Long, List<BkaEvent>> groupRows(List<BkaEvent> rows) {
    Map<Long, List<BkaEvent>> groups = new LinkedHashMap<>();
    for (BkaEvent row : rows) {
      if (row == null) {
        continue;
      }
      if (row.id() == null) {
        log.warn("bka: skipping agenda row without id (slug={})", row.slug());
        continue;
      }
      Long key = row.maineventId() != null ? row.maineventId() : row.id();
      groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
    }
    return groups;
  }

  private static SourceEvent map(Long groupId, List<BkaEvent> rows) {
    List<BkaEvent> valid = new ArrayList<>();
    List<EventOccurrence> occurrences = new ArrayList<>();
    Set<EventOccurrence> seenOccurrences = new LinkedHashSet<>();
    for (BkaEvent row : rows) {
      if (row == null) {
        continue;
      }
      try {
        EventOccurrence candidate = occurrence(row);
        if (seenOccurrences.add(candidate)) {
          occurrences.add(candidate);
          valid.add(row);
        }
      } catch (RuntimeException e) {
        log.warn(
            "bka: skipping malformed showing id={} slug={}: {}",
            row.id(),
            row.slug(),
            e.toString());
      }
    }
    if (valid.isEmpty()) {
      throw new IllegalArgumentException("no parseable occurrence");
    }
    occurrences.sort(
        Comparator.comparing(EventOccurrence::start)
            .thenComparing(EventOccurrence::end, Comparator.nullsLast(Comparator.naturalOrder())));

    BkaEvent base = valid.getFirst();
    String title = firstNonBlank(valid, BkaEvent::name);
    if (title == null) {
      throw new IllegalArgumentException("missing name");
    }
    BkaEvent.Place place = firstPlace(valid);

    return new SourceEvent(
        SourceId.BKA,
        groupId.toString(),
        title,
        plainText(firstDescription(valid)),
        place == null ? null : trimToNull(place.name()),
        addressLine(place),
        place == null ? null : trimToNull(place.city()),
        null,
        null,
        place == null ? null : asDecimal(place.latitude()),
        place == null ? null : asDecimal(place.longitude()),
        eventUrl(valid),
        categoryLabels(valid),
        firstPriceInfo(valid),
        EventStatus.fromWire(base.eventStatus()),
        occurrences);
  }

  private static EventOccurrence occurrence(BkaEvent row) {
    OffsetDateTime start = parseDate(row.dateFrom(), "date_from");
    if (start == null) {
      throw new IllegalArgumentException("missing date_from");
    }
    OffsetDateTime end = parseDate(row.dateTo(), "date_to");
    if (end != null && end.isBefore(start)) {
      throw new IllegalArgumentException("date_to is before date_from");
    }
    boolean allDay = start.toLocalTime().equals(LocalTime.MIDNIGHT);
    return new EventOccurrence(start.toInstant(), end == null ? null : end.toInstant(), allDay);
  }

  private static OffsetDateTime parseDate(String value, String field) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return OffsetDateTime.parse(value.strip());
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException(field + " is not an ISO-8601 instant: " + value, e);
    }
  }

  private static URI eventUrl(List<BkaEvent> rows) {
    String slug = firstNonBlank(rows, BkaEvent::maineventSlug);
    if (slug == null) {
      slug = firstNonBlank(rows, BkaEvent::slug);
    }
    return slug == null ? null : URI.create(PUBLIC_EVENT_BASE + slug);
  }

  private static BkaEvent.Place firstPlace(List<BkaEvent> rows) {
    for (BkaEvent row : rows) {
      if (row.place() != null) {
        return row.place();
      }
    }
    for (BkaEvent row : rows) {
      if (row.placeProposed() != null) {
        return row.placeProposed();
      }
    }
    return null;
  }

  private static String addressLine(BkaEvent.Place place) {
    if (place == null) {
      return null;
    }
    String address = trimToNull(place.address());
    String zip = trimToNull(place.zip());
    if (address == null) {
      return zip;
    }
    return zip == null ? address : address + ", " + zip;
  }

  private static BigDecimal asDecimal(Double value) {
    return value == null ? null : BigDecimal.valueOf(value);
  }

  private static Set<String> categoryLabels(List<BkaEvent> rows) {
    Set<String> labels = new LinkedHashSet<>();
    for (BkaEvent row : rows) {
      if (row.categories() == null) {
        continue;
      }
      for (BkaEvent.Category category : row.categories()) {
        String label = category == null ? null : label(category.label());
        if (label != null) {
          labels.add(label);
        }
      }
    }
    return labels;
  }

  private static String firstDescription(List<BkaEvent> rows) {
    for (BkaEvent row : rows) {
      String html = row.labels() == null ? null : label(row.labels().description());
      if (html != null) {
        return html;
      }
    }
    return null;
  }

  private static String firstPriceInfo(List<BkaEvent> rows) {
    for (BkaEvent row : rows) {
      String price = priceInfo(row);
      if (price != null) {
        return price;
      }
    }
    return null;
  }

  private static String priceInfo(BkaEvent row) {
    String special = trimToNull(row.specialRate());
    if (special != null) {
      String normalized = special.toLowerCase(Locale.ROOT);
      return switch (normalized) {
        case "free_entry", "free_price" -> "Free entry";
        default -> null;
      };
    }
    if (row.rates() == null || row.rates().isEmpty()) {
      return null;
    }
    List<String> prices = new ArrayList<>();
    for (BkaEvent.Rate rate : row.rates()) {
      if (rate == null) {
        continue;
      }
      String price = trimToNull(rate.price());
      String designation =
          rate.labels() == null ? null : trimToNull(label(rate.labels().designation()));
      if (price == null && designation == null) {
        continue;
      }
      if (price == null) {
        prices.add(designation);
      } else if (designation == null) {
        prices.add("CHF " + price);
      } else {
        prices.add(designation + ": CHF " + price);
      }
    }
    return prices.isEmpty() ? null : String.join(", ", prices);
  }

  /** German first (the agenda's publication language), French as fallback. */
  private static String label(BkaEvent.Label label) {
    if (label == null) {
      return null;
    }
    String german = trimToNull(label.de());
    return german != null ? german : trimToNull(label.fr());
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

  /**
   * Mirrors the store's window query: an occurrence qualifies when it starts before {@code to} and
   * either starts at/after {@code from} or still runs at {@code from}.
   */
  private static boolean overlapsWindow(SourceEvent event, FetchWindow window) {
    Instant from = window.from();
    Instant to = window.to();
    return event.occurrences().stream()
        .anyMatch(occurrence -> {
          if (!occurrence.start().isBefore(to)) {
            return false;
          }
          return !occurrence.start().isBefore(from)
              || (occurrence.end() != null && !occurrence.end().isBefore(from));
        });
  }

  private static String firstNonBlank(List<BkaEvent> rows, Function<BkaEvent, String> field) {
    for (BkaEvent row : rows) {
      String value = trimToNull(field.apply(row));
      if (value != null) {
        return value;
      }
    }
    return null;
  }

  private static String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.strip();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
