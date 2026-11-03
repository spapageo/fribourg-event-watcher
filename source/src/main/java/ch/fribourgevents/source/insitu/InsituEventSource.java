package ch.fribourgevents.source.insitu;

import ch.fribourgevents.domain.EventOccurrence;
import ch.fribourgevents.domain.EventStatus;
import ch.fribourgevents.domain.FetchWindow;
import ch.fribourgevents.domain.SourceEvent;
import ch.fribourgevents.domain.SourceId;
import ch.fribourgevents.source.EventSource;
import ch.fribourgevents.source.SourceFetchException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adapter for the open insitu.live agenda API (canton Fribourg).
 *
 * <p>One upstream event is listed once per open day, always carrying the same run range ({@code
 * date_from}..{@code date_to}). This adapter keeps the first row per event id and maps the run
 * range to a single {@link EventOccurrence}, so a multi-day exhibition is one canonical event with
 * one long-running occurrence instead of one row per day. That keeps the payload — and therefore
 * {@code event_source_ref.payload_hash} — stable while the event runs.
 *
 * <p>Rendering choices, all deterministic: descriptions are converted from upstream HTML to plain
 * text, French labels win over German, and a start at exactly midnight is treated as an all-day
 * marker (upstream exposes no all-day flag).
 */
public final class InsituEventSource implements EventSource {

  /**
   * Public event page; upstream redirects {@code /fr/evenement/<slug>} to the {@code -<id>} form.
   */
  private static final String PUBLIC_EVENT_BASE = "https://insitu.live/fr/evenement/";

  /** Bound on stored descriptions; the digest truncates again for display. */
  private static final int MAX_DESCRIPTION_CHARS = 2000;

  private static final Logger log = LoggerFactory.getLogger(InsituEventSource.class);

  private final InsituClient client;

  public InsituEventSource(InsituClient client) {
    this.client = Objects.requireNonNull(client, "client");
  }

  @Override
  public SourceId id() {
    return SourceId.INSITU;
  }

  @Override
  public List<SourceEvent> fetch(FetchWindow window) throws SourceFetchException {
    Objects.requireNonNull(window, "window");
    List<InsituEvent> rows = client.fetchAllRows();
    Map<Long, InsituEvent> firstRowById = firstRowPerEvent(rows);

    List<SourceEvent> events = new ArrayList<>();
    for (InsituEvent row : firstRowById.values()) {
      SourceEvent event;
      try {
        event = map(row);
      } catch (RuntimeException e) {
        log.warn(
            "insitu: skipping malformed event row id={} slug={}: {}",
            row.id(),
            row.slug(),
            e.toString());
        continue;
      }
      if (overlapsWindow(event, window)) {
        events.add(event);
      }
    }
    log.debug(
        "insitu: {} agenda rows collapsed to {} events, {} inside the window",
        rows.size(),
        firstRowById.size(),
        events.size());
    return List.copyOf(events);
  }

  /** Keeps the first row per event id; ids are stable, rows only differ in {@code next_date}. */
  private static Map<Long, InsituEvent> firstRowPerEvent(List<InsituEvent> rows) {
    Map<Long, InsituEvent> byId = new LinkedHashMap<>();
    for (InsituEvent row : rows) {
      if (row == null) {
        continue;
      }
      if (row.id() == null) {
        log.warn("insitu: skipping agenda row without id (slug={})", row.slug());
        continue;
      }
      byId.putIfAbsent(row.id(), row);
    }
    return byId;
  }

  private static SourceEvent map(InsituEvent row) {
    String sourceEventId = row.id().toString();
    if (isBlank(row.name())) {
      throw new IllegalArgumentException("missing name");
    }
    OffsetDateTime start = parseDate(row.dateFrom(), "date_from");
    if (start == null) {
      throw new IllegalArgumentException("missing date_from");
    }
    OffsetDateTime end = parseDate(row.dateTo(), "date_to");

    InsituEvent.Place place = row.place();
    return new SourceEvent(
        SourceId.INSITU,
        sourceEventId,
        row.name().strip(),
        plainText(description(row.labels())),
        place == null ? null : trimToNull(place.name()),
        addressLine(place),
        place == null ? null : trimToNull(place.city()),
        null,
        null,
        place == null ? null : asDecimal(place.latitude()),
        place == null ? null : asDecimal(place.longitude()),
        eventUrl(row.slug(), sourceEventId),
        categoryLabels(row.categories()),
        null,
        EventStatus.fromWire(row.eventStatus()),
        List.of(occurrence(start, end)));
  }

  private static EventOccurrence occurrence(OffsetDateTime start, OffsetDateTime end) {
    boolean allDay = start.toLocalTime().equals(LocalTime.MIDNIGHT);
    return new EventOccurrence(start.toInstant(), end == null ? null : end.toInstant(), allDay);
  }

  private static OffsetDateTime parseDate(String value, String field) {
    if (isBlank(value)) {
      return null;
    }
    try {
      return OffsetDateTime.parse(value.strip());
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException(field + " is not an ISO-8601 instant: " + value, e);
    }
  }

  private static URI eventUrl(String slug, String sourceEventId) {
    if (isBlank(slug)) {
      return null;
    }
    return URI.create(PUBLIC_EVENT_BASE + slug.strip() + "-" + sourceEventId);
  }

  private static String addressLine(InsituEvent.Place place) {
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

  private static Set<String> categoryLabels(List<InsituEvent.Category> categories) {
    Set<String> labels = new LinkedHashSet<>();
    if (categories == null) {
      return labels;
    }
    for (InsituEvent.Category category : categories) {
      String label = category == null ? null : label(category.label());
      if (label != null) {
        labels.add(label);
      }
    }
    return labels;
  }

  private static String description(InsituEvent.Labels labels) {
    return labels == null ? null : label(labels.description());
  }

  /** French first (canton Fribourg default), German as fallback. */
  private static String label(InsituEvent.Label label) {
    if (label == null) {
      return null;
    }
    String french = trimToNull(label.fr());
    return french != null ? french : trimToNull(label.de());
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

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private static String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.strip();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
