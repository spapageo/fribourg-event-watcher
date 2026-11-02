package ch.fribourgevents.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class NormalizerTest {

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          Cinéma – Zéro!               | cinema zero
          L'ÉTÉ  à Fribourg            | l ete a fribourg
          Straße                      | strasse
            Café  de la  Gare         | cafe de la gare
          """)
  void normalizesText(String raw, String expected) {
    assertThat(Normalizer.title(raw)).isEqualTo(expected);
  }

  @Test
  void treatsNullAsEmpty() {
    assertThat(Normalizer.title(null)).isEmpty();
    assertThat(Normalizer.venue(null)).isEmpty();
    assertThat(Normalizer.city(null)).isEmpty();
    assertThat(Normalizer.title("")).isEmpty();
  }

  @Test
  void isIdempotent() {
    String normalized = Normalizer.title("L'ÉTÉ à Fribourg – 2026");
    assertThat(Normalizer.title(normalized)).isEqualTo(normalized);
  }

  @Test
  void canonicalizesKnownCityAliases() {
    assertThat(Normalizer.city("Freiburg")).isEqualTo("fribourg");
    assertThat(Normalizer.city("Berne")).isEqualTo("bern");
    assertThat(Normalizer.city("Morat")).isEqualTo("murten");
    assertThat(Normalizer.city("Fribourg")).isEqualTo("fribourg");
  }
}
