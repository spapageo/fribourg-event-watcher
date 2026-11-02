package ch.fribourgevents.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class FetchWindowTest {

  private static final ZoneId ZURICH = ZoneId.of("Europe/Zurich");

  @Test
  void rejectsNullComponents() {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    assertThatThrownBy(() -> new FetchWindow(null, now, ZURICH))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new FetchWindow(now, null, ZURICH))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new FetchWindow(now, now, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void rejectsEmptyOrReversedWindow() {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    assertThatThrownBy(() -> new FetchWindow(now, now, ZURICH))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("from must be before to");
    assertThatThrownBy(() -> new FetchWindow(now.plusSeconds(1), now, ZURICH))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void derivesLocalDatesInWindowZone() {
    FetchWindow window =
        new FetchWindow(
            Instant.parse("2026-01-01T00:30:00Z"), Instant.parse("2026-01-15T23:30:00Z"), ZURICH);

    assertThat(window.fromDate()).isEqualTo(LocalDate.of(2026, 1, 1));
    assertThat(window.toDate()).isEqualTo(LocalDate.of(2026, 1, 16));
  }
}
