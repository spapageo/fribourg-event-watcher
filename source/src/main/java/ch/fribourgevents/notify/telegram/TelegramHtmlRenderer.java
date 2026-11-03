package ch.fribourgevents.notify.telegram;

import ch.fribourgevents.digest.DigestMessage;
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
 * Renders a {@link DigestMessage} as Telegram-flavoured HTML.
 *
 * <p>The {@code notify} module depends only on the digest <em>message</em> type (see {@code
 * docs/SPEC.md}), so this renderer repeats the presentation rules of the plain-text renderer
 * instead of sharing them: same day/time/venue layout, same {@code ongoing} and {@code until}
 * labels, same paragraph structure the chunker expects (one paragraph per event, day header glued
 * to its first event).
 *
 * <p>Safety rule: every value that came from upstream is escaped, and the only tags in the output
 * are the ones written here — {@code <b>} and {@code <a href>}. Even the message title is escaped
 * rather than concatenated.
 */
final class TelegramHtmlRenderer {

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

  /** Renders {@code message}; an empty window yields a single escaped line. */
  String render(DigestMessage message) {
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
    blocks.add("<b>" + escape(TITLE) + "</b> · " + windowRange(window));
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
    return "<b>" + DAY_HEADER.format(day.date()) + "</b>\n" + items;
  }

  private static String itemBlock(Item item, LocalDate day, ZoneId zone) {
    StringJoiner lines = new StringJoiner("\n");
    StringBuilder headline =
        new StringBuilder("<b>")
            .append(timeLabel(item, day, zone))
            .append("</b> · <b>")
            .append(escape(text(item.title())))
            .append("</b>");
    untilSuffix(item, zone).ifPresent(suffix -> headline.append(' ').append(suffix));
    lines.add(headline.toString());
    place(item).ifPresent(place -> lines.add(escape(place)));
    if (item.url() != null) {
      String url = item.url().toString();
      lines.add("<a href=\"" + escapeAttribute(url) + "\">" + escape(url) + "</a>");
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

  /** Escapes text content; Telegram only needs the three markup-sensitive characters. */
  static String escape(String value) {
    return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  /** Escapes an attribute value, which additionally must not end the quoted href early. */
  static String escapeAttribute(String value) {
    return escape(value).replace("\"", "&quot;").replace("'", "&#39;");
  }

  private static DateTimeFormatter formatter(String pattern) {
    return DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH);
  }
}
