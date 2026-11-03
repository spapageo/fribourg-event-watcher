package ch.fribourgevents.notify;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Splits rendered digest text into transport-safe parts.
 *
 * <p>Input is the plain text produced by the digest renderer, where every event is its own
 * paragraph (lines separated by a single newline) and paragraphs are separated by one blank line.
 * The chunker keeps that structure:
 *
 * <ol>
 *   <li>split on blank lines, so a break always falls between two events or between two days;
 *   <li>pack whole paragraphs into parts, greedily, up to the limit;
 *   <li>only if a single paragraph cannot fit, break it on line boundaries — never inside a line;
 *   <li>truncate any remaining over-long line with an ellipsis so it still fits;
 *   <li>number the parts {@code (n/m)} when there is more than one.
 * </ol>
 *
 * <p>The limit applies to the final part including its {@code (n/m)} suffix, so a caller can pass
 * the transport's hard character limit directly.
 */
public final class MessageChunker {

  /** Default part size: comfortably under Telegram's 4096-character message limit. */
  public static final int DEFAULT_LIMIT = 4000;

  /** Smallest limit the chunker accepts; below this the numbering suffix eats the content. */
  public static final int MINIMUM_LIMIT = 20;

  private static final String PARAGRAPH_SEPARATOR = "\n\n";
  private static final String LINE_SEPARATOR = "\n";
  private static final String ELLIPSIS = "…";
  private static final int MAX_PACKING_ATTEMPTS = 5;

  public List<String> chunk(String text) {
    return chunk(text, DEFAULT_LIMIT);
  }

  /**
   * Splits {@code text} into parts of at most {@code limit} characters.
   *
   * @return zero parts for blank text, one un-numbered part when everything fits, otherwise parts
   *     numbered {@code (1/m)}..{@code (m/m)}
   */
  public List<String> chunk(String text, int limit) {
    Objects.requireNonNull(text, "text");
    if (limit < MINIMUM_LIMIT) {
      throw new IllegalArgumentException(
          "limit must be at least " + MINIMUM_LIMIT + ", was " + limit);
    }
    String normalized = text.strip();
    if (normalized.isEmpty()) {
      return List.of();
    }

    List<String> paragraphs = splitParagraphs(normalized);
    int reserve = 0;
    List<String> parts = List.of();
    for (int attempt = 0; attempt < MAX_PACKING_ATTEMPTS; attempt++) {
      int width = Math.max(MINIMUM_LIMIT, limit - reserve);
      parts = pack(paragraphs, width);
      if (parts.size() <= 1) {
        return parts;
      }
      int needed = PARAGRAPH_SEPARATOR.length() + suffix(1, parts.size()).length();
      if (needed <= reserve) {
        return numbered(parts);
      }
      // Part count may grow digits once suffixed, so reserve for the wider suffix and repack.
      reserve = needed;
    }
    return numbered(parts);
  }

  private static List<String> splitParagraphs(String text) {
    List<String> paragraphs = new ArrayList<>();
    for (String paragraph : text.split("\n[ \t]*\n")) {
      if (!paragraph.isBlank()) {
        paragraphs.add(paragraph);
      }
    }
    return paragraphs;
  }

  private static List<String> pack(List<String> paragraphs, int width) {
    List<String> parts = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    for (String paragraph : paragraphs) {
      for (String unit : units(paragraph, width)) {
        if (current.isEmpty()) {
          current.append(unit);
        } else if (current.length() + PARAGRAPH_SEPARATOR.length() + unit.length() <= width) {
          current.append(PARAGRAPH_SEPARATOR).append(unit);
        } else {
          parts.add(current.toString());
          current.setLength(0);
          current.append(unit);
        }
      }
    }
    if (!current.isEmpty()) {
      parts.add(current.toString());
    }
    return parts;
  }

  /**
   * Returns {@code paragraph} when it fits, otherwise consecutive runs of its lines that do fit,
   * truncating any single line that is longer than {@code width}.
   */
  private static List<String> units(String paragraph, int width) {
    if (paragraph.length() <= width) {
      return List.of(paragraph);
    }
    List<String> units = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    for (String line : paragraph.split(LINE_SEPARATOR, -1)) {
      String truncated = truncate(line, width);
      if (current.isEmpty()) {
        current.append(truncated);
      } else if (current.length() + LINE_SEPARATOR.length() + truncated.length() <= width) {
        current.append(LINE_SEPARATOR).append(truncated);
      } else {
        units.add(current.toString());
        current.setLength(0);
        current.append(truncated);
      }
    }
    if (!current.isEmpty()) {
      units.add(current.toString());
    }
    return units;
  }

  private static String truncate(String line, int width) {
    if (line.length() <= width) {
      return line;
    }
    return line.substring(0, Math.max(0, width - ELLIPSIS.length())).stripTrailing() + ELLIPSIS;
  }

  private static List<String> numbered(List<String> parts) {
    if (parts.size() <= 1) {
      return parts;
    }
    List<String> numbered = new ArrayList<>(parts.size());
    for (int index = 0; index < parts.size(); index++) {
      numbered.add(parts.get(index) + PARAGRAPH_SEPARATOR + suffix(index + 1, parts.size()));
    }
    return List.copyOf(numbered);
  }

  private static String suffix(int number, int total) {
    return "(" + number + "/" + total + ")";
  }
}
