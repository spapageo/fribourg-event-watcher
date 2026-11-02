package ch.fribourgevents.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import ch.fribourgevents.domain.EventOccurrence;
import ch.fribourgevents.domain.EventStatus;
import ch.fribourgevents.domain.SourceEvent;
import ch.fribourgevents.domain.SourceId;
import ch.fribourgevents.store.EventStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ApplicationStartupTest {

  private static final Path DB_FILE = createDatabaseFile();

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("DB_PATH", () -> DB_FILE.toAbsolutePath().toString());
  }

  @Autowired private EventStore store;
  @Autowired private JdbcTemplate jdbc;

  @AfterAll
  static void deleteDatabase() {
    try {
      Files.deleteIfExists(DB_FILE);
    } catch (IOException ignored) {
      // Best effort; the temp directory is disposable.
    }
  }

  @Test
  void startsWithConfiguredDatabaseAndWritesThroughTheStore() {
    store.upsert(
        new SourceEvent(
            SourceId.INSITU,
            "startup-1",
            "Startup concert",
            null,
            "Nouveau Monde",
            null,
            "Fribourg",
            "FR",
            "CH",
            null,
            null,
            URI.create("https://example.test/startup-1"),
            Set.of("concert"),
            null,
            EventStatus.CONFIRMED,
            List.of(new EventOccurrence(Instant.parse("2026-03-01T19:00:00Z"), null, false))));

    assertThat(jdbc.queryForObject("select count(*) from event", Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from event_occurrence", Integer.class))
        .isEqualTo(1);
  }

  private static Path createDatabaseFile() {
    try {
      return Files.createTempFile("fribourg-events-startup-", ".db");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
