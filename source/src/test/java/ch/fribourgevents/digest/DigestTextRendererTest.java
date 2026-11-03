package ch.fribourgevents.digest;

import static org.assertj.core.api.Assertions.assertThat;

import ch.fribourgevents.digest.DigestMessage.Item;
import ch.fribourgevents.domain.FetchWindow;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class DigestTextRendererTest {

  private static final ZoneId ZURICH = ZoneId.of("Europe/Zurich");
  private static final FetchWindow WINDOW =
      new FetchWindow(
          Instant.parse("2026-11-03T00:00:00Z"), Instant.parse("2026-11-17T00:00:00Z"), ZURICH);

  private final DigestTextRenderer renderer = new DigestTextRenderer();

  @Test
  void rendersHeaderDayHeadersTimesVenuesAndUrls() {
    DigestMessage message =
        DigestMessage.build(
            WINDOW,
            List.of(
                item(
                    1L,
                    10L,
                    "2026-11-03T18:30:00Z",
                    null,
                    false,
                    "Cinéma Zéro",
                    "Nouveau Monde",
                    "Fribourg",
                    "https://example.test/a"),
                item(
                    2L,
                    20L,
                    "2026-11-04T23:00:00Z",
                    "2026-11-07T16:00:00Z",
                    true,
                    "Expo photo",
                    "Chemin des Grottes",
                    "Muntelier",
                    "https://example.test/b")),
            150);

    assertThat(renderer.render(message))
        .isEqualTo(
            """
            Fribourg & Bern · 3 Nov – 17 Nov 2026

            Tue 3 Nov
              19:30 · Cinéma Zéro
              Nouveau Monde, Fribourg
              https://example.test/a

            Thu 5 Nov
              all day · Expo photo (until 7 Nov)
              Chemin des Grottes, Muntelier
              https://example.test/b\
            """);
  }

  @Test
  void separatesEventsOfTheSameDayWithABlankLineSoTheyCanBeChunkedApart() {
    DigestMessage message =
        DigestMessage.build(
            WINDOW,
            List.of(
                item(1L, 10L, "2026-11-03T18:30:00Z", null, false, "First", "A", "Fribourg", null),
                item(
                    2L, 20L, "2026-11-03T20:00:00Z", null, false, "Second", "B", "Fribourg", null)),
            150);

    assertThat(renderer.render(message))
        .isEqualTo(
            """
            Fribourg & Bern · 3 Nov – 17 Nov 2026

            Tue 3 Nov
              19:30 · First
              A, Fribourg

              21:00 · Second
              B, Fribourg\
            """);
  }

  @Test
  void labelsEventsStartedBeforeTheWindowAsOngoing() {
    DigestMessage message =
        DigestMessage.build(
            WINDOW,
            List.of(
                item(
                    1L,
                    10L,
                    "2026-09-24T12:00:00Z",
                    "2026-11-26T16:30:00Z",
                    false,
                    "Philippe GROSS",
                    "Galerie du Temps",
                    "Fribourg",
                    "https://example.test/expo")),
            150);

    assertThat(renderer.render(message))
        .contains("Tue 3 Nov\n  ongoing · Philippe GROSS (until 26 Nov)")
        .doesNotContain("14:00");
  }

  @Test
  void usesYearSuffixWhenAnEventEndsInAnotherYear() {
    DigestMessage message =
        DigestMessage.build(
            new FetchWindow(
                Instant.parse("2026-12-28T00:00:00Z"),
                Instant.parse("2027-01-11T00:00:00Z"),
                ZURICH),
            List.of(
                item(
                    1L,
                    10L,
                    "2026-12-28T11:00:00Z",
                    "2027-01-03T16:00:00Z",
                    false,
                    "Winter expo",
                    "Musée",
                    "Bulle",
                    "https://example.test/winter")),
            150);

    assertThat(renderer.render(message))
        .startsWith("Fribourg & Bern · 28 Dec 2026 – 11 Jan 2027")
        .contains("(until 3 Jan 2027)");
  }

  @Test
  void omitsTheUntilSuffixForEventsThatEndOnTheirStartDay() {
    DigestMessage message =
        DigestMessage.build(
            WINDOW,
            List.of(
                item(
                    1L,
                    10L,
                    "2026-11-05T17:00:00Z",
                    "2026-11-05T18:30:00Z",
                    false,
                    "Café-Récit",
                    "Café-Récit",
                    "Fribourg",
                    "https://example.test/recit")),
            150);

    assertThat(renderer.render(message)).doesNotContain("until");
  }

  @Test
  void omitsThePlaceLineWhenVenueAndCityAreMissing() {
    DigestMessage message =
        DigestMessage.build(
            WINDOW,
            List.of(
                item(
                    1L, 10L, "2026-11-05T17:00:00Z", null, false, "Secret show", null, null, null)),
            150);

    assertThat(renderer.render(message))
        .isEqualTo(
            """
            Fribourg & Bern · 3 Nov – 17 Nov 2026

            Thu 5 Nov
              18:00 · Secret show\
            """);
  }

  @Test
  void rendersTheMoreEventsFooterWhenTruncated() {
    DigestMessage message =
        DigestMessage.build(
            WINDOW,
            List.of(
                item(1L, 10L, "2026-11-03T18:00:00Z", null, false, "One", null, null, null),
                item(2L, 20L, "2026-11-04T18:00:00Z", null, false, "Two", null, null, null),
                item(3L, 30L, "2026-11-05T18:00:00Z", null, false, "Three", null, null, null)),
            2);

    String rendered = renderer.render(message);

    assertThat(message.omittedCount()).isEqualTo(1);
    assertThat(rendered).contains("One", "Two").doesNotContain("Three");
    assertThat(rendered).endsWith("+1 more events not shown.");
  }

  @Test
  void rendersASingleLineWhenTheWindowHasNoEvents() {
    DigestMessage message = DigestMessage.build(WINDOW, List.of(), 150);

    assertThat(message.isEmpty()).isTrue();
    assertThat(renderer.render(message))
        .isEqualTo("No events found between 3 Nov 2026 and 17 Nov 2026.");
  }

  @Test
  void collapsesWhitespaceInUpstreamTextSoEveryItemStaysOnItsLines() {
    DigestMessage message =
        DigestMessage.build(
            WINDOW,
            List.of(
                item(
                    1L,
                    10L,
                    "2026-11-05T17:00:00Z",
                    null,
                    false,
                    "Line\nbreak\ttitle",
                    "Venue\nwith break",
                    "Fribourg",
                    null)),
            150);

    assertThat(renderer.render(message)).contains("18:00 · Line break title\n  Venue with break");
  }

  @Test
  void renderingIsDeterministic() {
    DigestMessage message =
        DigestMessage.build(
            WINDOW,
            List.of(
                item(1L, 10L, "2026-11-03T18:00:00Z", null, false, "One", "A", "Fribourg", null),
                item(2L, 20L, "2026-11-04T18:00:00Z", null, false, "Two", "B", "Bulle", null)),
            150);

    assertThat(renderer.render(message)).isEqualTo(renderer.render(message));
  }

  private static Item item(
      Long eventId,
      Long occurrenceId,
      String start,
      String end,
      boolean allDay,
      String title,
      String venue,
      String city,
      String url) {
    return new Item(
        eventId,
        occurrenceId,
        Instant.parse(start),
        end == null ? null : Instant.parse(end),
        allDay,
        title,
        venue,
        city,
        url == null ? null : URI.create(url));
  }
}
