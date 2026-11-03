package ch.fribourgevents.notify.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import ch.fribourgevents.digest.DigestMessage;
import ch.fribourgevents.digest.DigestMessage.Day;
import ch.fribourgevents.digest.DigestMessage.Item;
import ch.fribourgevents.domain.FetchWindow;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for the Telegram HTML rendering rules: escaping, tags, and layout. */
class TelegramHtmlRendererTest {

  private static final ZoneId ZURICH = ZoneId.of("Europe/Zurich");
  private static final FetchWindow WINDOW =
      new FetchWindow(
          Instant.parse("2026-11-03T00:00:00Z"), Instant.parse("2026-11-17T00:00:00Z"), ZURICH);

  private final TelegramHtmlRenderer renderer = new TelegramHtmlRenderer();

  @Test
  void emitsOnlyAdapterTagsAndEscapesEveryUpstreamValue() {
    Item item =
        new Item(
            1L,
            10L,
            Instant.parse("2026-11-03T18:30:00Z"),
            null,
            false,
            "Tom & Jerry <script>alert(\"x\")</script>",
            "Venue <b>",
            "Fribourg & Co",
            URI.create("https://example.test/e?a=1&b=2"));

    assertThat(renderer.render(message(item)))
        .isEqualTo(
            """
            <b>Fribourg &amp; Bern</b> · 3 Nov – 17 Nov 2026

            <b>Tue 3 Nov</b>
            <b>19:30</b> · <b>Tom &amp; Jerry &lt;script&gt;alert("x")&lt;/script&gt;</b>
            Venue &lt;b&gt;, Fribourg &amp; Co
            <a href="https://example.test/e?a=1&amp;b=2">https://example.test/e?a=1&amp;b=2</a>\
            """);
  }

  @Test
  void labelsOngoingAllDayAndMultiDayItems() {
    DigestMessage message =
        new DigestMessage(
            WINDOW,
            List.of(
                new Day(LocalDate.parse("2026-11-03"), List.of(ongoingItem())),
                new Day(LocalDate.parse("2026-11-05"), List.of(allDayItem()))),
            2,
            0);

    assertThat(renderer.render(message))
        .isEqualTo(
            """
            <b>Fribourg &amp; Bern</b> · 3 Nov – 17 Nov 2026

            <b>Tue 3 Nov</b>
            <b>ongoing</b> · <b>Running expo</b>
            Nouveau Monde, Fribourg

            <b>Thu 5 Nov</b>
            <b>all day</b> · <b>Expo photo</b> (until 7 Nov)
            Chemin des Grottes, Muntelier\
            """);
  }

  @Test
  void rendersAnEmptyWindowAsOneLine() {
    assertThat(renderer.render(new DigestMessage(WINDOW, List.of(), 0, 0)))
        .isEqualTo("No events found between 3 Nov 2026 and 17 Nov 2026.");
  }

  @Test
  void rendersTheTruncationFooter() {
    DigestMessage message =
        new DigestMessage(
            WINDOW, List.of(new Day(LocalDate.parse("2026-11-03"), List.of(ongoingItem()))), 1, 42);

    assertThat(renderer.render(message)).endsWith("+42 more events not shown.");
  }

  @Test
  void escapesQuotesInAttributes() {
    assertThat(TelegramHtmlRenderer.escapeAttribute("\"a'b\"")).isEqualTo("&quot;a&#39;b&quot;");
    assertThat(TelegramHtmlRenderer.escapeAttribute("a&b<c>d")).isEqualTo("a&amp;b&lt;c&gt;d");
  }

  private static DigestMessage message(Item... items) {
    return new DigestMessage(
        WINDOW, List.of(new Day(LocalDate.parse("2026-11-03"), List.of(items))), items.length, 0);
  }

  private static Item ongoingItem() {
    return new Item(
        1L,
        10L,
        Instant.parse("2026-11-02T10:00:00Z"),
        null,
        false,
        "Running expo",
        "Nouveau Monde",
        "Fribourg",
        null);
  }

  private static Item allDayItem() {
    return new Item(
        2L,
        20L,
        Instant.parse("2026-11-04T23:00:00Z"),
        Instant.parse("2026-11-07T16:00:00Z"),
        true,
        "Expo photo",
        "Chemin des Grottes",
        "Muntelier",
        null);
  }
}
