package ch.fribourgevents.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.fribourgevents.digest.DigestMessage;
import ch.fribourgevents.domain.FetchWindow;
import ch.fribourgevents.notify.DeliveryResult.DeliveryStatus;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeliveryResultTest {

  private static final FetchWindow WINDOW =
      new FetchWindow(
          Instant.parse("2026-11-03T00:00:00Z"),
          Instant.parse("2026-11-17T00:00:00Z"),
          ZoneId.of("Europe/Zurich"));

  @Test
  void describesACompleteDelivery() {
    DeliveryResult result = DeliveryResult.delivered("telegram", List.of("42", "43"));

    assertThat(result.channel()).isEqualTo("telegram");
    assertThat(result.parts()).isEqualTo(2);
    assertThat(result.externalIds()).containsExactly("42", "43");
    assertThat(result.status()).isEqualTo(DeliveryStatus.DELIVERED);
  }

  @Test
  void describesADeliveryThatStoppedAfterSomeParts() {
    DeliveryResult result = DeliveryResult.partial("telegram", 3, List.of("42"));

    assertThat(result.parts()).isEqualTo(3);
    assertThat(result.externalIds()).containsExactly("42");
    assertThat(result.status()).isEqualTo(DeliveryStatus.PARTIAL);
  }

  @Test
  void rejectsBlankChannelsAndOverlongIdLists() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new DeliveryResult(" ", 1, List.of(), DeliveryStatus.DELIVERED));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new DeliveryResult("telegram", -1, List.of(), DeliveryStatus.DELIVERED));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> new DeliveryResult("telegram", 1, List.of("1", "2"), DeliveryStatus.DELIVERED));
  }

  @Test
  void sendersReportSuccessThroughTheResultAndFailureThroughTheCheckedException() throws Exception {
    NotificationSender sender = message -> DeliveryResult.delivered("fake", List.of("1"));
    NotificationSender failing =
        message -> {
          throw new NotificationException("telegram 400: bad chat id");
        };
    DigestMessage message = new DigestMessage(WINDOW, List.of(), 0, 0);

    assertThat(sender.send(message).status()).isEqualTo(DeliveryStatus.DELIVERED);
    assertThatThrownBy(() -> failing.send(message))
        .isInstanceOf(NotificationException.class)
        .hasMessageContaining("telegram 400");
  }
}
