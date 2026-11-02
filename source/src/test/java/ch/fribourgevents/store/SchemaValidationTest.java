package ch.fribourgevents.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@DataJpaTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "spring.flyway.enabled=true",
      "spring.datasource.hikari.maximum-pool-size=1",
      "spring.datasource.hikari.minimum-idle=1"
    })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SchemaValidationTest {

  private static final Path DB_FILE = SqliteTestDatabase.create("schema");

  @DynamicPropertySource
  static void sqlite(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> SqliteTestDatabase.url(DB_FILE));
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add(
        "spring.jpa.database-platform", () -> "org.hibernate.community.dialect.SQLiteDialect");
  }

  @Autowired private JdbcTemplate jdbc;

  @AfterAll
  static void deleteDatabase() {
    SqliteTestDatabase.delete(DB_FILE);
  }

  @Test
  void flywayCreatesOnlyCanonicalTables() {
    List<String> tables =
        jdbc.queryForList("select name from sqlite_master where type = 'table'", String.class);

    assertThat(DB_FILE).exists();
    assertThat(tables)
        .contains("event", "event_source_ref", "event_occurrence", "flyway_schema_history")
        .doesNotContain("digest_run", "digest_run_item", "notification_delivery", "source_sync");
  }

  @Test
  void createsQueryIndexes() {
    List<String> indexes =
        jdbc.queryForList("select name from sqlite_master where type = 'index'", String.class);

    assertThat(indexes)
        .contains("idx_event_city", "idx_event_occurrence_start", "idx_event_occurrence_local_date")
        .doesNotContain("idx_digest_run_started");
  }

  @Test
  void recordsMigrationVersion() {
    Integer applied =
        jdbc.queryForObject(
            "select count(*) from flyway_schema_history where success = 1", Integer.class);

    assertThat(applied).isEqualTo(1);
  }

  @Test
  void usesForeignKeyEnforcement() {
    Integer enabled = jdbc.queryForObject("pragma foreign_keys", Integer.class);
    assertThat(enabled).isEqualTo(1);
  }
}
