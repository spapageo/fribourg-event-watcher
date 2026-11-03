package ch.fribourgevents.notify.telegram;

import ch.fribourgevents.notify.NotificationException;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * One-message writer for the Telegram Bot API ({@code POST /bot<token>/sendMessage}).
 *
 * <p>Retry policy, kept deliberately small because the pipeline delivers at most once per run:
 *
 * <ul>
 *   <li>{@code 429}: wait for the {@code retry_after} the API asks for, bounded by {@link
 *       #MAX_RETRY_AFTER}, and retry at most {@link #MAX_RETRIES} times;
 *   <li>{@code 5xx} and connection failures: retry at most {@link #MAX_RETRIES} times with
 *       exponential backoff starting at {@link #INITIAL_BACKOFF};
 *   <li>other {@code 4xx}: fail immediately — a bad token or chat id will not fix itself.
 * </ul>
 *
 * <p>The token lives in the request URI, so it is never put into an exception message, and the
 * API's error description is redacted before it leaves this class. Callers log {@link
 * NotificationException#getMessage()}, which is therefore safe.
 */
public final class TelegramClient {

  /** How many extra attempts a single send may make for transient failures. */
  static final int MAX_RETRIES = 2;

  /** Backoff for the first retry; doubles per retry. */
  static final Duration INITIAL_BACKOFF = Duration.ofMillis(500);

  /** Upper bound on a {@code 429} wait so a hostile value cannot stall the run. */
  static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(30);

  private static final int MAX_DESCRIPTION_LENGTH = 200;

  private final TelegramProperties properties;
  private final RestClient http;
  private final Sleeper sleeper;
  private final JsonMapper json = JsonMapper.builder().build();

  public TelegramClient(TelegramProperties properties, RestClient http) {
    this(properties, http, Sleeper.threadSleep());
  }

  TelegramClient(TelegramProperties properties, RestClient http, Sleeper sleeper) {
    this.properties = Objects.requireNonNull(properties, "properties");
    this.http = Objects.requireNonNull(http, "http");
    this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
  }

  /**
   * Sends one message part.
   *
   * @return the {@code message_id} assigned by Telegram
   * @throws NotificationException when the part was not accepted after the retry policy was spent
   */
  public long sendMessage(String text) throws NotificationException {
    Objects.requireNonNull(text, "text");
    try {
      return deliver(text);
    } catch (PermanentException e) {
      throw new NotificationException(e.getMessage(), e.getCause());
    }
  }

  private long deliver(String text) {
    int throttleRetries = 0;
    int serverRetries = 0;
    while (true) {
      try {
        return post(text);
      } catch (ThrottledException e) {
        if (throttleRetries >= MAX_RETRIES) {
          throw new PermanentException(
              "telegram 429: still rate limited after " + MAX_RETRIES + " retries");
        }
        throttleRetries++;
        waitFor(bound(e.retryAfter()));
      } catch (TransientException e) {
        if (serverRetries >= MAX_RETRIES) {
          throw new PermanentException(e.getMessage() + " after " + MAX_RETRIES + " retries");
        }
        serverRetries++;
        waitFor(INITIAL_BACKOFF.multipliedBy(serverRetries));
      }
    }
  }

  private long post(String text) {
    try {
      String response =
          http.post()
              .uri(sendMessageUri())
              .contentType(MediaType.APPLICATION_JSON)
              .accept(MediaType.APPLICATION_JSON)
              .body(requestBody(text))
              .retrieve()
              .body(String.class);
      return acceptedMessageId(parse(response));
    } catch (RestClientResponseException e) {
      throw failure(e.getStatusCode().value(), parse(e.getResponseBodyAsString()));
    } catch (ResourceAccessException e) {
      throw new TransientException("telegram: connection failed");
    } catch (RestClientException e) {
      throw new PermanentException("telegram: request failed");
    }
  }

  private URI sendMessageUri() {
    return URI.create(properties.baseUrl() + "/bot" + properties.botToken() + "/sendMessage");
  }

  private String requestBody(String text) {
    ObjectNode body = json.createObjectNode();
    body.put("chat_id", properties.chatId());
    body.put("text", text);
    body.put("parse_mode", "HTML");
    body.put("disable_web_page_preview", true);
    return json.writeValueAsString(body);
  }

  private long acceptedMessageId(JsonNode body) {
    if (body == null) {
      throw new PermanentException("telegram: unreadable response");
    }
    if (!body.path("ok").asBoolean(false)) {
      throw failure(body.path("error_code").asInt(0), body);
    }
    long messageId = body.path("result").path("message_id").asLong(-1);
    if (messageId < 0) {
      throw new PermanentException("telegram: response without a message id");
    }
    return messageId;
  }

  /**
   * Maps an unsuccessful response to the retry policy: throttling, a retryable server-side problem,
   * or a permanent rejection. {@code status} is the HTTP status when there was one, otherwise the
   * API's {@code error_code}.
   */
  private RuntimeException failure(int status, JsonNode body) {
    int code = body == null ? status : body.path("error_code").asInt(status);
    int effective = code >= 400 ? code : status;
    if (effective == 429) {
      return new ThrottledException(retryAfter(body));
    }
    if (effective >= 500) {
      return new TransientException("telegram " + effective + ": server error");
    }
    if (effective == 0) {
      return new PermanentException("telegram: request rejected");
    }
    String description = redact(body == null ? "" : body.path("description").asString(""));
    return new PermanentException(
        "telegram "
            + effective
            + ": "
            + (description.isEmpty() ? "request rejected" : description));
  }

  private static Duration retryAfter(JsonNode body) {
    if (body == null) {
      return Duration.ZERO;
    }
    int seconds = body.path("parameters").path("retry_after").asInt(0);
    return seconds > 0 ? Duration.ofSeconds(seconds) : Duration.ZERO;
  }

  private static Duration bound(Duration retryAfter) {
    return retryAfter.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : retryAfter;
  }

  private void waitFor(Duration duration) {
    if (duration.isZero() || duration.isNegative()) {
      return;
    }
    try {
      sleeper.sleep(duration);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new PermanentException("telegram: interrupted while waiting to retry");
    }
  }

  private JsonNode parse(String payload) {
    if (payload == null || payload.isBlank()) {
      return null;
    }
    try {
      return json.readTree(payload);
    } catch (JacksonException e) {
      return null;
    }
  }

  /** Keeps API error text log-safe: no token, no chat id, no newlines, bounded length. */
  private String redact(String description) {
    if (description == null) {
      return "";
    }
    String redacted =
        description.replace(properties.botToken(), "***").replace(properties.chatId(), "***");
    redacted = redacted.replaceAll("\\s+", " ").strip();
    if (redacted.length() > MAX_DESCRIPTION_LENGTH) {
      return redacted.substring(0, MAX_DESCRIPTION_LENGTH) + "…";
    }
    return redacted;
  }

  /** Lets tests supply a recording sleeper instead of waiting in real time. */
  @FunctionalInterface
  public interface Sleeper {

    void sleep(Duration duration) throws InterruptedException;

    /** The production sleeper: blocks the calling thread. */
    static Sleeper threadSleep() {
      return duration -> {
        if (!duration.isZero()) {
          Thread.sleep(duration);
        }
      };
    }
  }

  /** Waiting is over and the request will never succeed. */
  private static final class PermanentException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    PermanentException(String message) {
      super(message);
    }

    PermanentException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** The API asked for a pause of {@code retryAfter}. */
  private static final class ThrottledException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Duration retryAfter;

    ThrottledException(Duration retryAfter) {
      super("telegram 429: rate limited");
      this.retryAfter = retryAfter == null ? Duration.ZERO : retryAfter;
    }

    Duration retryAfter() {
      return retryAfter;
    }
  }

  /** Worth one more attempt: 5xx or a connection failure. */
  private static final class TransientException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    TransientException(String message) {
      super(message);
    }
  }
}
