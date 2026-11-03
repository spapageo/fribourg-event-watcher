package ch.fribourgevents.notify.telegram;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.fribourgevents.digest.DigestMessage;
import ch.fribourgevents.digest.DigestMessage.Day;
import ch.fribourgevents.digest.DigestMessage.Item;
import ch.fribourgevents.domain.FetchWindow;
import ch.fribourgevents.notify.DeliveryResult;
import ch.fribourgevents.notify.DeliveryResult.DeliveryStatus;
import ch.fribourgevents.notify.MessageChunker;
import ch.fribourgevents.notify.NotificationException;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * WireMock tests for the Telegram sender: payload shape, retry policy, redaction, multi-part
 * delivery, and partial delivery. No test waits in real time; the client takes a recording sleeper.
 */
class TelegramNotificationSenderTest {

  private static final String TOKEN = "123456:TEST-TOKEN";
  private static final String CHAT_ID = "42";
  private static final String SEND_PATH = "/bot" + TOKEN + "/sendMessage";
  private static final String ACCEPTED = "{\"ok\":true,\"result\":{\"message_id\":7}}";
  private static final ZoneId ZURICH = ZoneId.of("Europe/Zurich");
  private static final Instant START = Instant.parse("2026-11-03T19:00:00Z");
  private static final LocalDate DAY = LocalDate.parse("2026-11-03");
  private static final FetchWindow WINDOW =
      new FetchWindow(
          Instant.parse("2026-11-03T00:00:00Z"), Instant.parse("2026-11-17T00:00:00Z"), ZURICH);

  private final JsonMapper json = JsonMapper.builder().build();
  private final List<Duration> sleeps = new ArrayList<>();

  private WireMockServer server;
  private TelegramProperties properties;

  @BeforeEach
  void startServer() {
    server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    server.start();
    properties = TelegramProperties.of(TOKEN, CHAT_ID).withBaseUrl(server.baseUrl());
  }

  @AfterEach
  void stopServer() {
    server.stop();
  }

  @Test
  void sendsTheDocumentedPayloadWithEscapedUpstreamText() throws NotificationException {
    server.stubFor(post(urlPathEqualTo(SEND_PATH)).willReturn(okJson(ACCEPTED)));

    DeliveryResult result =
        sender(MessageChunker.DEFAULT_LIMIT).send(message(item("Café-Récit <15> & more")));

    assertThat(result.channel()).isEqualTo(TelegramNotificationSender.CHANNEL);
    assertThat(result.status()).isEqualTo(DeliveryStatus.DELIVERED);
    assertThat(result.parts()).isEqualTo(1);
    assertThat(result.externalIds()).containsExactly("7");
    server.verify(
        1,
        postRequestedFor(urlPathEqualTo(SEND_PATH))
            .withHeader("Content-Type", containing("application/json")));

    JsonNode request = onlyRequest();
    assertThat(request.path("chat_id").asString()).isEqualTo(CHAT_ID);
    assertThat(request.path("parse_mode").asString()).isEqualTo("HTML");
    assertThat(request.path("disable_web_page_preview").asBoolean()).isTrue();
    assertThat(request.path("text").asString())
        .contains("<b>Café-Récit &lt;15&gt; &amp; more</b>")
        .doesNotContain("<15>");
  }

  @Test
  void retriesAfter429AndHonoursRetryAfter() throws NotificationException {
    server.stubFor(
        post(urlPathEqualTo(SEND_PATH))
            .inScenario("throttle")
            .whenScenarioStateIs(STARTED)
            .willReturn(
                aResponse()
                    .withStatus(429)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {"ok":false,"error_code":429,"description":"Too Many Requests",
                         "parameters":{"retry_after":2}}\
                        """))
            .willSetStateTo("retry"));
    server.stubFor(
        post(urlPathEqualTo(SEND_PATH))
            .inScenario("throttle")
            .whenScenarioStateIs("retry")
            .willReturn(okJson(ACCEPTED)));

    DeliveryResult result = sender(MessageChunker.DEFAULT_LIMIT).send(message(item("Retry")));

    assertThat(result.status()).isEqualTo(DeliveryStatus.DELIVERED);
    assertThat(sleeps).containsExactly(Duration.ofSeconds(2));
    server.verify(2, postRequestedFor(urlPathEqualTo(SEND_PATH)));
  }

  @Test
  void boundsAnExcessiveRetryAfterAndGivesUpAfterTwoRetries() {
    server.stubFor(
        post(urlPathEqualTo(SEND_PATH))
            .willReturn(
                aResponse()
                    .withStatus(429)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {"ok":false,"error_code":429,"parameters":{"retry_after":3600}}\
                        """)));

    assertThatThrownBy(() -> sender(MessageChunker.DEFAULT_LIMIT).send(message(item("Throttled"))))
        .isInstanceOf(NotificationException.class)
        .hasMessageContaining("telegram 429")
        .hasMessageContaining("rate limited after 2 retries");

    assertThat(sleeps).containsExactly(Duration.ofSeconds(30), Duration.ofSeconds(30));
    server.verify(3, postRequestedFor(urlPathEqualTo(SEND_PATH)));
  }

  @Test
  void retriesServerErrorsTwiceWithBackoffThenSucceeds() throws NotificationException {
    server.stubFor(
        post(urlPathEqualTo(SEND_PATH))
            .inScenario("flaky")
            .whenScenarioStateIs(STARTED)
            .willReturn(aResponse().withStatus(500))
            .willSetStateTo("second"));
    server.stubFor(
        post(urlPathEqualTo(SEND_PATH))
            .inScenario("flaky")
            .whenScenarioStateIs("second")
            .willReturn(aResponse().withStatus(502))
            .willSetStateTo("third"));
    server.stubFor(
        post(urlPathEqualTo(SEND_PATH))
            .inScenario("flaky")
            .whenScenarioStateIs("third")
            .willReturn(okJson(ACCEPTED)));

    DeliveryResult result = sender(MessageChunker.DEFAULT_LIMIT).send(message(item("Flaky")));

    assertThat(result.status()).isEqualTo(DeliveryStatus.DELIVERED);
    assertThat(sleeps).containsExactly(Duration.ofMillis(500), Duration.ofMillis(1000));
    server.verify(3, postRequestedFor(urlPathEqualTo(SEND_PATH)));
  }

  @Test
  void failsFastOnAClientErrorWithoutLeakingSecrets() {
    server.stubFor(
        post(urlPathEqualTo(SEND_PATH))
            .willReturn(
                aResponse()
                    .withStatus(400)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: token "
                            + TOKEN
                            + " invalid for chat "
                            + CHAT_ID
                            + "\"}")));

    assertThatThrownBy(() -> sender(MessageChunker.DEFAULT_LIMIT).send(message(item("Rejected"))))
        .isInstanceOf(NotificationException.class)
        .hasMessage("telegram 400: Bad Request: token *** invalid for chat ***");

    // Fail fast: exactly one attempt, no backoff.
    assertThat(sleeps).isEmpty();
    server.verify(1, postRequestedFor(urlPathEqualTo(SEND_PATH)));
  }

  @Test
  void givesUpAfterTwoRetriesWhenTheServerKeepsFailing() {
    server.stubFor(post(urlPathEqualTo(SEND_PATH)).willReturn(aResponse().withStatus(503)));

    assertThatThrownBy(() -> sender(MessageChunker.DEFAULT_LIMIT).send(message(item("Down"))))
        .isInstanceOf(NotificationException.class)
        .hasMessageContaining("telegram 503")
        .hasMessageContaining("server error after 2 retries");

    server.verify(3, postRequestedFor(urlPathEqualTo(SEND_PATH)));
  }

  @Test
  void splitsALongDigestIntoNumberedPartsThatEachFitTheLimit() throws NotificationException {
    server.stubFor(post(urlPathEqualTo(SEND_PATH)).willReturn(okJson(ACCEPTED)));
    DigestMessage message =
        message(item("Event one"), item("Event two"), item("Event three"), item("Event four"));

    DeliveryResult result = sender(120).send(message);

    List<String> texts = sentTexts();
    assertThat(result.parts()).isEqualTo(texts.size());
    assertThat(texts).hasSizeGreaterThan(1);
    assertThat(result.externalIds()).hasSize(texts.size());
    assertThat(texts).allSatisfy(text -> assertThat(text.length()).isLessThanOrEqualTo(120));
  }

  @Test
  void numbersEveryPartAndNeverSplitsAnOpenTag() throws NotificationException {
    server.stubFor(post(urlPathEqualTo(SEND_PATH)).willReturn(okJson(ACCEPTED)));

    DeliveryResult result =
        sender(120)
            .send(
                message(
                    item("First"), item("Second"), item("Third"), item("Fourth"), item("Fifth")));

    List<String> texts = sentTexts();
    assertThat(result.parts()).isEqualTo(texts.size());
    assertThat(texts).hasSizeGreaterThan(1);
    for (int index = 0; index < texts.size(); index++) {
      String text = texts.get(index);
      assertThat(text).contains("(" + (index + 1) + "/" + texts.size() + ")");
      assertThat(count(text, "<b>")).isEqualTo(count(text, "</b>"));
      assertThat(count(text, "<a ")).isEqualTo(count(text, "</a>"));
    }
  }

  @Test
  void reportsAPartialDeliveryWhenALaterPartIsRejected() throws NotificationException {
    server.stubFor(
        post(urlPathEqualTo(SEND_PATH))
            .inScenario("partial")
            .whenScenarioStateIs(STARTED)
            .willReturn(okJson(ACCEPTED))
            .willSetStateTo("reject"));
    server.stubFor(
        post(urlPathEqualTo(SEND_PATH))
            .inScenario("partial")
            .whenScenarioStateIs("reject")
            .willReturn(aResponse().withStatus(400)));

    DeliveryResult result = sender(120).send(message(item("First"), item("Second"), item("Third")));

    assertThat(result.status()).isEqualTo(DeliveryStatus.PARTIAL);
    assertThat(result.parts()).isEqualTo(2);
    assertThat(result.externalIds()).containsExactly("7");
    server.verify(2, postRequestedFor(urlPathEqualTo(SEND_PATH)));
  }

  private TelegramNotificationSender sender(int partLimit) {
    TelegramClient client = new TelegramClient(properties, RestClient.create(), sleeps::add);
    return new TelegramNotificationSender(client, partLimit);
  }

  private DigestMessage message(Item... items) {
    return new DigestMessage(WINDOW, List.of(new Day(DAY, List.of(items))), items.length, 0);
  }

  private static Item item(String title) {
    return new Item(
        1L,
        10L,
        START,
        null,
        false,
        title,
        "Nouveau Monde",
        "Fribourg",
        URI.create("https://example.test/event?a=1&b=2"));
  }

  private JsonNode onlyRequest() {
    return json.readTree(sentRequests().getFirst().getBodyAsString());
  }

  private List<String> sentTexts() {
    return sentRequests().stream()
        .map(request -> json.readTree(request.getBodyAsString()).path("text").asString())
        .toList();
  }

  private List<LoggedRequest> sentRequests() {
    return server.findAll(postRequestedFor(urlPathEqualTo(SEND_PATH)));
  }

  private static int count(String text, String token) {
    int count = 0;
    for (int index = text.indexOf(token); index >= 0; index = text.indexOf(token, index + 1)) {
      count++;
    }
    return count;
  }
}
