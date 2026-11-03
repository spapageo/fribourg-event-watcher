package ch.fribourgevents.notify.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class TelegramPropertiesTest {

  @Test
  void defaultsToThePublicApiAndNormalizesTheBaseUrl() {
    TelegramProperties defaults = TelegramProperties.of("123456:secret", "42");
    assertThat(defaults.baseUrl()).isEqualTo(TelegramProperties.DEFAULT_BASE_URL);
    assertThat(defaults.withBaseUrl(" http://localhost:8089/// ").baseUrl())
        .isEqualTo("http://localhost:8089");
    assertThat(new TelegramProperties("123456:secret", "42", " ").baseUrl())
        .isEqualTo(TelegramProperties.DEFAULT_BASE_URL);
  }

  @Test
  void rejectsMissingCredentials() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> TelegramProperties.of(" ", "42"))
        .withMessageContaining("botToken");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> TelegramProperties.of("123456:secret", ""))
        .withMessageContaining("chatId");
    assertThatNullPointerException().isThrownBy(() -> TelegramProperties.of(null, "42"));
  }

  @Test
  void neverExposesTheTokenOrChatIdWhenPrinted() {
    String printed = TelegramProperties.of("123456:very-secret-token", "987654321").toString();

    assertThat(printed)
        .contains("botToken=***", "chatId=***")
        .doesNotContain("very-secret-token")
        .doesNotContain("987654321");
  }
}
