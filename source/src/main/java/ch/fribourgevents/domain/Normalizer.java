package ch.fribourgevents.domain;

import java.text.Normalizer.Form;
import java.util.Locale;
import java.util.Map;

/**
 * Deterministic text normalization used for exact-match dedup keys and fuzzy candidate blocking.
 * All methods accept {@code null} and return an empty string for it.
 */
public final class Normalizer {

  private static final Map<String, String> CITY_ALIASES =
      Map.of(
          "freiburg", "fribourg",
          "berne", "bern",
          "morat", "murten");

  private static final Map<Character, String> LIGATURES =
      Map.ofEntries(
          Map.entry('ß', "ss"),
          Map.entry('ẞ', "ss"),
          Map.entry('œ', "oe"),
          Map.entry('Œ', "oe"),
          Map.entry('æ', "ae"),
          Map.entry('Æ', "ae"),
          Map.entry('ø', "o"),
          Map.entry('Ø', "o"),
          Map.entry('ł', "l"),
          Map.entry('Ł', "l"),
          Map.entry('đ', "d"),
          Map.entry('Đ', "d"),
          Map.entry('ð', "d"),
          Map.entry('Ð', "d"),
          Map.entry('þ', "th"),
          Map.entry('Þ', "th"));

  private Normalizer() {}

  public static String title(String raw) {
    return text(raw);
  }

  public static String venue(String raw) {
    return text(raw);
  }

  public static String city(String raw) {
    String normalized = text(raw);
    return CITY_ALIASES.getOrDefault(normalized, normalized);
  }

  static String text(String raw) {
    if (raw == null) {
      return "";
    }
    StringBuilder expanded = new StringBuilder(raw.length());
    raw.codePoints()
        .forEach(codePoint -> {
          String ligature = LIGATURES.get((char) codePoint);
          if (ligature == null) {
            expanded.appendCodePoint(codePoint);
          } else {
            expanded.append(ligature);
          }
        });
    String decomposed = java.text.Normalizer.normalize(expanded, Form.NFD);
    return decomposed
        .replaceAll("\\p{M}+", "")
        .toLowerCase(Locale.ROOT)
        .replaceAll("[^a-z0-9]+", " ")
        .strip();
  }
}
