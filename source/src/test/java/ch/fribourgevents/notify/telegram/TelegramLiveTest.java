package ch.fribourgevents.notify.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import ch.fribourgevents.digest.DigestMessage;
import ch.fribourgevents.digest.DigestMessage.Day;
import ch.fribourgevents.digest.DigestMessage.Item;
import ch.fribourgevents.domain.FetchWindow;
import ch.fribourgevents.notify.DeliveryResult;
import ch.fribourgevents.notify.DeliveryResult.DeliveryStatus;
import ch.fribourgevents.notify.NotificationException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

/**
 * Manual live smoke against the real Telegram Bot API. Tagged {@code live}, so it is excluded from
 * {@code ./gradlew build}; it sends one message to the configured chat.
 *
 * <p>Run with: {@code TELEGRAM_BOT_TOKEN=... TELEGRAM_CHAT_ID=... ./gradlew test -PincludeTags=live
 * -PliveSource=telegram}. Credentials are read from the environment only and are never logged.
 */
@Tag("live")
class TelegramLiveTest {

  private static final Logger log = LoggerFactory.getLogger(TelegramLiveTest.class);
  private static final ZoneId ZURICH = ZoneId.of("Europe/Zurich");

  @Test
  void deliversALiveMessage() throws NotificationException {
    TelegramProperties properties =
        TelegramProperties.of(required("TELEGRAM_BOT_TOKEN"), required("TELEGRAM_CHAT_ID"));
    TelegramNotificationSender sender =
        new TelegramNotificationSender(new TelegramClient(properties, RestClient.create()));

    Instant now = Instant.now();
    FetchWindow window =
        new FetchWindow(now.minus(Duration.ofHours(1)), now.plus(Duration.ofDays(1)), ZURICH);
    DigestMessage message =
        new DigestMessage(
            window,
            List.of(
                new Day(
                    now.atZone(ZURICH).toLocalDate(),
                    List.of(
                        new Item(
                            0L,
                            0L,
                            now,
                            null,
                            false,
                            "fribourg-events-digest live check",
                            "n/a",
                            "Fribourg",
                            URI.create("https://example.test/live"))))),
            1,
            0);

    DeliveryResult result = sender.send(message);

    log.info(
        "telegram live: channel={} status={} parts={} externalIds={}",
        result.channel(),
        result.status(),
        result.parts(),
        result.externalIds());
    assertThat(result.status()).isEqualTo(DeliveryStatus.DELIVERED);
    assertThat(result.parts()).isEqualTo(1);
    assertThat(result.externalIds()).hasSize(1);
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " must be set to run the live Telegram check");
    }
    return value.strip();
  }
}
