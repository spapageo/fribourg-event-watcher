package ch.fribourgevents.runtime;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ch.fribourgevents.store.SqliteTestDatabase;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Full-context check that {@code digest.run-on-startup=true} triggers the pipeline exactly once,
 * through the real {@link StartupRunner} bean, without touching the network (the scheduler is a
 * mock, so the guarded pipeline never runs).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"digest.dry-run=true", "digest.run-on-startup=true"})
class StartupRunIntegrationTest {

  private static final Path DB_FILE = SqliteTestDatabase.create("startup-run");

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("DB_PATH", () -> DB_FILE.toAbsolutePath().toString());
  }

  @MockitoBean private DigestScheduler scheduler;

  @AfterAll
  static void deleteDatabase() {
    SqliteTestDatabase.delete(DB_FILE);
  }

  @Test
  void triggersTheStartupRunOnce() {
    verify(scheduler, times(1)).runOrSkip("startup");
  }
}
