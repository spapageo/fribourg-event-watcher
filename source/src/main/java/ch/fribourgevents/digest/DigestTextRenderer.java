package ch.fribourgevents.digest;

import ch.fribourgevents.digest.DigestMessage.Day;
import ch.fribourgevents.digest.DigestMessage.Item;
import ch.fribourgevents.domain.FetchWindow;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * Renders a {@link DigestMessage} as plain text.
 *
 * <p>Output is deterministic: no clock, no locale from the environment, and no HTML. Upstream text
 * is whitespace-collapsed so an item always occupies the same lines, which keeps the message stable
 * for any transport. Escaping for a specific channel (for example Telegram HTML) belongs to that
 * channel's sender, not here.
 *
 * <p>Each event is its own paragraph (blank line separated) and the day header is glued to its
 * first event, so {@link MessageChunker} can split between events without cutting one in half.
 */
public final class DigestTextRenderer {

  private static final String TITLE = "Fribourg & Bern";
  private static final String DAY_HEADER_PATTERN = "EEE d MMM";
  private static final String DAY_MONTH_PATTERN = "d MMM";
  private static final String DAY_MONTH_YEAR_PATTERN = "d MMM yyyy";
  private static final String TIME_PATTERN = "HH:mm";
  private static final String ALL_DAY = "all day";
  private static final String ONGOING = "ongoing";

  private static final DateTimeFormatter DAY_HEADER = formatter(DAY_HEADER_PATTERN);
  private static final DateTimeFormatter DAY_MONTH = formatter(DAY_MONTH_PATTERN);
  private static final DateTimeFormatter DAY_MONTH_YEAR = formatter(DAY_MONTH_YEAR_PATTERN);
  private static final DateTimeFormatter TIME = formatter(TIME_PATTERN);

  /** Renders {@code message}; an empty window yields a single line. */
  public String render(DigestMessage message) {
    Objects.requireNonNull(message, "message");
    FetchWindow window = message.window();
    ZoneId zone = window.zone();

    if (message.isEmpty()) {
      return "No events found between "
          + DAY_MONTH_YEAR.format(window.fromDate())
          + " and "
          + DAY_MONTH_YEAR.format(window.to().atZone(zone).toLocalDate())
          + ".";
    }

    List<String> blocks = new ArrayList<>();
    blocks.add(TITLE + " · " + windowRange(window));
    for (Day day : message.days()) {
      blocks.add(dayBlock(day, zone));
    }
    if (message.truncated()) {
      blocks.add("+" + message.omittedCount() + " more events not shown.");
    }
    return String.join("\n\n", blocks);
  }

  private static String dayBlock(Day day, ZoneId zone) {
    StringJoiner items = new StringJoiner("\n\n");
    for (Item item : day.items()) {
      items.add(itemBlock(item, day.date(), zone));
    }
    // The day header rides along with its first item so a chunk never starts with a bare header.
    return DAY_HEADER.format(day.date()) + "\n" + items;
  }

  private static String itemBlock(Item item, LocalDate day, ZoneId zone) {
    StringJoiner lines = new StringJoiner("\n");
    StringBuilder headline =
        new StringBuilder("  ")
            .append(timeLabel(item, day, zone))
            .append(" · ")
            .append(text(item.title()));
    untilSuffix(item, zone).ifPresent(suffix -> headline.append(' ').append(suffix));
    lines.add(headline.toString());
    place(item).ifPresent(place -> lines.add("  " + place));
    if (item.url() != null) {
      lines.add("  " + item.url());
    }
    return lines.toString();
  }

  /** {@code ongoing} for items that began before the day they are listed under. */
  private static String timeLabel(Item item, LocalDate day, ZoneId zone) {
    if (item.localDate(zone).isBefore(day)) {
      return ONGOING;
    }
    return item.allDay() ? ALL_DAY : TIME.format(item.start().atZone(zone));
  }

  /** {@code (until 17 Nov)} for runs that end on a later local day than they start. */
  private static Optional<String> untilSuffix(Item item, ZoneId zone) {
    LocalDate startDate = item.localDate(zone);
    LocalDate endDate = item.localEndDate(zone);
    if (endDate == null || !endDate.isAfter(startDate)) {
      return Optional.empty();
    }
    DateTimeFormatter format =
        endDate.getYear() == startDate.getYear() ? DAY_MONTH : DAY_MONTH_YEAR;
    return Optional.of("(until " + format.format(endDate) + ")");
  }

  private static Optional<String> place(Item item) {
    StringJoiner joiner = new StringJoiner(", ");
    String venue = text(item.venueName());
    String city = text(item.city());
    if (!venue.isEmpty()) {
      joiner.add(venue);
    }
    if (!city.isEmpty()) {
      joiner.add(city);
    }
    String joined = joiner.toString();
    return joined.isEmpty() ? Optional.empty() : Optional.of(joined);
  }

  private static String windowRange(FetchWindow window) {
    LocalDate from = window.fromDate();
    LocalDate to = window.to().atZone(window.zone()).toLocalDate();
    if (from.getYear() == to.getYear()) {
      return DAY_MONTH.format(from) + " – " + DAY_MONTH_YEAR.format(to);
    }
    return DAY_MONTH_YEAR.format(from) + " – " + DAY_MONTH_YEAR.format(to);
  }

  /** Collapses every whitespace run so upstream text cannot break the item layout. */
  private static String text(String value) {
    return value == null ? "" : value.strip().replaceAll("\\s+", " ");
  }

  private static DateTimeFormatter formatter(String pattern) {
    return DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH);
  }
}
