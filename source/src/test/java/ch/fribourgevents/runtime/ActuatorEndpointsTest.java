package ch.fribourgevents.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import ch.fribourgevents.store.SqliteTestDatabase;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Boots the full web application and checks the Actuator surface: health and info are exposed,
 * everything else is not, and there is no inbound digest trigger.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ActuatorEndpointsTest {

  private static final Path DB_FILE = SqliteTestDatabase.create("actuator");
  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("DB_PATH", () -> DB_FILE.toAbsolutePath().toString());
    registry.add("digest.dry-run", () -> "true");
  }

  @LocalServerPort private int port;

  @AfterAll
  static void deleteDatabase() {
    SqliteTestDatabase.delete(DB_FILE);
  }

  @Test
  void exposesHealthAndInfo() throws Exception {
    HttpResponse<String> health = get("/actuator/health");
    assertThat(health.statusCode()).isEqualTo(200);
    assertThat(health.body()).contains("\"status\":\"UP\"");

    assertThat(get("/actuator/info").statusCode()).isEqualTo(200);
  }

  @Test
  void doesNotExposeOtherActuatorEndpoints() throws Exception {
    assertThat(get("/actuator/env").statusCode()).isEqualTo(404);
    assertThat(get("/actuator/beans").statusCode()).isEqualTo(404);
    assertThat(get("/actuator/metrics").statusCode()).isEqualTo(404);
  }

  @Test
  void hasNoInboundDigestTrigger() throws Exception {
    assertThat(get("/digest/run").statusCode()).isEqualTo(404);
    assertThat(post("/digest/run").statusCode()).isEqualTo(404);
  }

  private HttpResponse<String> get(String path) throws IOException, InterruptedException {
    return send(path, "GET");
  }

  private HttpResponse<String> post(String path) throws IOException, InterruptedException {
    return send(path, "POST");
  }

  private HttpResponse<String> send(String path, String method)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .timeout(TIMEOUT)
            .method(method, HttpRequest.BodyPublishers.noBody())
            .build();
    return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
  }
}
