package ch.fribourgevents.notify.telegram;

import java.util.Objects;

/**
 * Telegram Bot API settings.
 *
 * <p>A plain immutable record on purpose: runtime binding (environment variables →
 * {@code @ConfigurationProperties}) belongs to the {@code runtime} module, so this type stays
 * usable from tests and from the pipeline without a Spring context.
 *
 * <p>{@link #toString()} redacts the token and the chat id so the settings can be logged safely.
 *
 * @param botToken Bot API token ({@code <bot-id>:<secret>}); required
 * @param chatId target chat; required
 * @param baseUrl Bot API origin, {@value #DEFAULT_BASE_URL} when not given
 */
public record TelegramProperties(String botToken, String chatId, String baseUrl) {

  /** Public Bot API origin; injectable so tests can point at WireMock. */
  public static final String DEFAULT_BASE_URL = "https://api.telegram.org";

  public TelegramProperties {
    botToken = requireNonBlank(botToken, "botToken");
    chatId = requireNonBlank(chatId, "chatId");
    baseUrl =
        baseUrl == null || baseUrl.isBlank()
            ? DEFAULT_BASE_URL
            : stripTrailingSlash(baseUrl.strip());
  }

  /** Settings for the public Bot API. */
  public static TelegramProperties of(String botToken, String chatId) {
    return new TelegramProperties(botToken, chatId, DEFAULT_BASE_URL);
  }

  /** Same settings against another origin, for tests or a proxy. */
  public TelegramProperties withBaseUrl(String baseUrl) {
    return new TelegramProperties(botToken, chatId, baseUrl);
  }

  @Override
  public String toString() {
    return "TelegramProperties[botToken=***, chatId=***, baseUrl=" + baseUrl + "]";
  }

  private static String requireNonBlank(String value, String name) {
    Objects.requireNonNull(value, name);
    String stripped = value.strip();
    if (stripped.isEmpty()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return stripped;
  }

  private static String stripTrailingSlash(String value) {
    String stripped = value;
    while (stripped.endsWith("/")) {
      stripped = stripped.substring(0, stripped.length() - 1);
    }
    return stripped;
  }
}
