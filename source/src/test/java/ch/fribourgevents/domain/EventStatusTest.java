package ch.fribourgevents.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EventStatusTest {

  @Test
  void mapsEnglishAndBilingualStatuses() {
    assertThat(EventStatus.fromWire("confirmed")).isEqualTo(EventStatus.CONFIRMED);
    assertThat(EventStatus.fromWire("CONFIRMED")).isEqualTo(EventStatus.CONFIRMED);
    assertThat(EventStatus.fromWire("bestätigt")).isEqualTo(EventStatus.CONFIRMED);
    assertThat(EventStatus.fromWire("tentative")).isEqualTo(EventStatus.TENTATIVE);
    assertThat(EventStatus.fromWire("cancelled")).isEqualTo(EventStatus.CANCELLED);
    assertThat(EventStatus.fromWire("canceled")).isEqualTo(EventStatus.CANCELLED);
    assertThat(EventStatus.fromWire("abgesagt")).isEqualTo(EventStatus.CANCELLED);
  }

  @Test
  void degradesUnknownAndMissingValues() {
    assertThat(EventStatus.fromWire(null)).isEqualTo(EventStatus.UNKNOWN);
    assertThat(EventStatus.fromWire(" ")).isEqualTo(EventStatus.UNKNOWN);
    assertThat(EventStatus.fromWire("postponed")).isEqualTo(EventStatus.UNKNOWN);
  }
}
