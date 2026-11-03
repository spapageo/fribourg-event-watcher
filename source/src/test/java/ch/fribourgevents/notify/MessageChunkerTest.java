package ch.fribourgevents.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class MessageChunkerTest {

  private static final Pattern NUMBERING = Pattern.compile("\n\n\\(\\d+/\\d+\\)$");
  private static final Pattern NUMBER = Pattern.compile("\\((\\d+)/(\\d+)\\)$");

  private final MessageChunker chunker = new MessageChunker();

  @Test
  void returnsNoPartsForBlankText() {
    assertThat(chunker.chunk("")).isEmpty();
    assertThat(chunker.chunk("   \n\n \t ")).isEmpty();
  }

  @Test
  void keepsShortTextInOneUnnumberedPart() {
    String text = "Fribourg & Bern · 3 Nov – 17 Nov 2026\n\nTue 3 Nov\n  19:30 · Cinéma Zéro";

    assertThat(chunker.chunk(text)).containsExactly(text);
  }

  @Test
  void keepsTextExactlyAtTheLimitInOnePart() {
    String text = "x".repeat(100);

    assertThat(chunker.chunk(text, 100)).containsExactly(text);
  }

  @Test
  void splitsParagraphsThatDoNotFitTogetherAndNumbersThem() {
    String first = "x".repeat(60);
    String second = "y".repeat(60);

    List<String> parts = chunker.chunk(first + "\n\n" + second, 100);

    assertThat(parts).containsExactly(first + "\n\n(1/2)", second + "\n\n(2/2)");
  }

  @Test
  void numbersEveryPartAndKeepsAllParagraphsInOrder() {
    List<String> paragraphs = new ArrayList<>();
    for (int i = 1; i <= 12; i++) {
      paragraphs.add("Day " + i + "\n  19:30 · Event number " + i + "\n  Venue " + i);
    }
    String text = String.join("\n\n", paragraphs);

    List<String> parts = chunker.chunk(text, 120);

    assertThat(parts).hasSizeGreaterThan(2);
    assertThat(parts).allSatisfy(part -> assertThat(part.length()).isLessThanOrEqualTo(120));
    assertThat(parts)
        .extracting(MessageChunkerTest::partNumber)
        .containsExactlyElementsOf(ordinals(parts.size()));
    assertThat(parts)
        .extracting(MessageChunkerTest::partTotal)
        .allSatisfy(total -> assertThat(total).isEqualTo(String.valueOf(parts.size())));
    assertThat(String.join("\n\n", parts.stream().map(MessageChunkerTest::withoutSuffix).toList()))
        .isEqualTo(text);
  }

  @Test
  void neverBreaksAnItemBlockAcrossParts() {
    List<String> items =
        List.of(
            "Tue 3 Nov\n  19:30 · Cinéma Zéro\n  Nouveau Monde, Fribourg\n  https://example.test/a",
            "  20:00 · Another one\n  Café-Récit, Fribourg\n  https://example.test/b",
            "  21:00 · Third\n  Muntelier\n  https://example.test/c",
            "Wed 4 Nov\n  all day · Expo\n  Chemin des Grottes, Muntelier\n  https://example.test/d",
            "  18:00 · Last\n  Bulle\n  https://example.test/e");
    String text = String.join("\n\n", items);

    List<String> parts = chunker.chunk(text, 130);

    assertThat(parts).hasSizeGreaterThan(1);
    List<String> seen = new ArrayList<>();
    for (String part : parts) {
      for (String paragraph : withoutSuffix(part).split("\n\n")) {
        assertThat(items).contains(paragraph);
        seen.add(paragraph);
      }
    }
    assertThat(seen).containsExactlyElementsOf(items);
  }

  @Test
  void truncatesAnOverLongParagraphLineWithAnEllipsis() {
    String line = "Event " + "y".repeat(600);

    List<String> parts = chunker.chunk(line, 200);

    assertThat(parts).hasSize(1);
    assertThat(parts.getFirst()).hasSize(200).startsWith("Event y").endsWith("y…");
  }

  @Test
  void splitsAMultiLineParagraphOnLineBoundariesNotInsideALine() {
    String first = "a".repeat(60);
    String second = "b".repeat(60);
    String third = "c".repeat(60);

    List<String> parts = chunker.chunk(first + "\n" + second + "\n" + third, 100);

    assertThat(parts).hasSize(3);
    assertThat(parts.stream().map(MessageChunkerTest::withoutSuffix).toList())
        .containsExactly(first, second, third);
  }

  @Test
  void rejectsLimitsBelowTheMinimum() {
    assertThatIllegalArgumentException().isThrownBy(() -> chunker.chunk("text", 0));
    assertThatIllegalArgumentException().isThrownBy(() -> chunker.chunk("text", 19));
  }

  private static String withoutSuffix(String part) {
    return NUMBERING.matcher(part).replaceAll("");
  }

  private static String partNumber(String part) {
    return match(part).group(1);
  }

  private static String partTotal(String part) {
    return match(part).group(2);
  }

  private static Matcher match(String part) {
    Matcher matcher = NUMBER.matcher(part);
    assertThat(matcher.find()).as("part should end with an (n/m) suffix: %s", part).isTrue();
    return matcher;
  }

  private static List<String> ordinals(int count) {
    List<String> ordinals = new ArrayList<>();
    for (int i = 1; i <= count; i++) {
      ordinals.add(String.valueOf(i));
    }
    return ordinals;
  }
}
