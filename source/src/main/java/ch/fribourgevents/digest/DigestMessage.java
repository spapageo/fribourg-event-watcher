package ch.fribourgevents.digest;

import ch.fribourgevents.domain.FetchWindow;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Channel-agnostic digest: the items selected for a window, already grouped by local day and
 * capped.
 *
 * <p>This is the boundary between selection and delivery. Renderers ({@link DigestTextRenderer})
 * and notification channels consume it; nothing here knows about text, HTML, or Telegram.
 *
 * <p>{@code itemCount} is the number of items present, {@code omittedCount} the number the cap
 * dropped. Both are derived, never edited by callers.
 *
 * <p>{@code notes} are footer paragraphs the pipeline adds after the events — a failed-source
 * warning, later a dedup summary. Renderers print them verbatim (escaping for their transport), so
 * a notes-only message (no days) renders as the notes alone instead of a misleading "no events"
 * line.
 */
public record DigestMessage(
    FetchWindow window, List<Day> days, int itemCount, int omittedCount, List<String> notes) {

  public DigestMessage {
    Objects.requireNonNull(window, "window");
    days = List.copyOf(days);
    notes = List.copyOf(Objects.requireNonNull(notes, "notes"));
    for (String note : notes) {
      if (note == null || note.isBlank()) {
        throw new IllegalArgumentException("notes must not contain blanks");
      }
    }
    if (itemCount < 0) {
      throw new IllegalArgumentException("itemCount must not be negative");
    }
    if (omittedCount < 0) {
      throw new IllegalArgumentException("omittedCount must not be negative");
    }
    if (itemCount != days.stream().mapToInt(day -> day.items().size()).sum()) {
      throw new IllegalArgumentException("itemCount must equal the number of items in days");
    }
  }

  /** A message without footer notes; the common case outside the pipeline. */
  public DigestMessage(FetchWindow window, List<Day> days, int itemCount, int omittedCount) {
    this(window, days, itemCount, omittedCount, List.of());
  }

  /** Same digest with different footer notes. */
  public DigestMessage withNotes(List<String> notes) {
    return new DigestMessage(window, days, itemCount, omittedCount, notes);
  }

  /** One local calendar day of the window; never empty. */
  public record Day(LocalDate date, List<Item> items) {

    public Day {
      Objects.requireNonNull(date, "date");
      items = List.copyOf(items);
      if (items.isEmpty()) {
        throw new IllegalArgumentException("a digest day must contain at least one item");
      }
    }
  }

  /**
   * One occurrence of one canonical event.
   *
   * <p>{@code start} is the real occurrence start, even when the item is grouped under a later day
   * (an event that began before the window is listed on the window's first day). {@code end} is
   * null when upstream did not report one.
   */
  public record Item(
      Long eventId,
      Long occurrenceId,
      Instant start,
      Instant end,
      boolean allDay,
      String title,
      String venueName,
      String city,
      URI url) {

    public Item {
      Objects.requireNonNull(start, "start");
      Objects.requireNonNull(title, "title");
    }

    /** Local calendar date of {@link #start} in {@code zone}. */
    public LocalDate localDate(ZoneId zone) {
      return start.atZone(Objects.requireNonNull(zone, "zone")).toLocalDate();
    }

    /** Local calendar date of {@link #end}, or null when the end is unknown. */
    public LocalDate localEndDate(ZoneId zone) {
      return end == null ? null : end.atZone(Objects.requireNonNull(zone, "zone")).toLocalDate();
    }
  }

  public boolean isEmpty() {
    return itemCount == 0;
  }

  /** True when the item cap dropped something. */
  public boolean truncated() {
    return omittedCount > 0;
  }

  /**
   * Sorts items by start then title, applies the cap, and groups by local day in the window zone.
   *
   * <p>Occurrences that started before the window are grouped under the window's first day so the
   * digest never shows a day header outside the window; their {@link Item#start} is left untouched
   * so renderers can call them out as ongoing.
   */
  static DigestMessage build(FetchWindow window, List<Item> items, int itemCap) {
    Objects.requireNonNull(window, "window");
    Objects.requireNonNull(items, "items");
    if (itemCap < 0) {
      throw new IllegalArgumentException("itemCap must not be negative");
    }

    List<Item> sorted = new ArrayList<>(items);
    sorted.sort(
        Comparator.comparing(Item::start)
            .thenComparing(Item::title, Comparator.nullsLast(Comparator.naturalOrder())));

    int omitted = Math.max(0, sorted.size() - itemCap);
    List<Item> included = omitted == 0 ? sorted : List.copyOf(sorted.subList(0, itemCap));

    ZoneId zone = window.zone();
    LocalDate firstDay = window.fromDate();
    Map<LocalDate, List<Item>> grouped = new LinkedHashMap<>();
    for (Item item : included) {
      LocalDate day = item.localDate(zone);
      if (day.isBefore(firstDay)) {
        day = firstDay;
      }
      grouped.computeIfAbsent(day, ignored -> new ArrayList<>()).add(item);
    }

    List<Day> days =
        grouped.entrySet().stream()
            .map(entry -> new Day(entry.getKey(), entry.getValue()))
            .toList();
    return new DigestMessage(window, days, included.size(), omitted, List.of());
  }
}
