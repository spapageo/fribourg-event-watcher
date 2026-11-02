package ch.fribourgevents.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class FlywayRecreateTest {

  private static final Path DB_FILE = SqliteTestDatabase.create("recreate");

  @AfterAll
  static void deleteDatabase() {
    SqliteTestDatabase.delete(DB_FILE);
  }

  @Test
  void migratesEmptyDatabaseAndRecreatesAfterDeletion() throws Exception {
    assertThat(migrate()).isEqualTo(1);
    assertThat(tableNames()).contains("event", "event_occurrence", "flyway_schema_history");

    Files.delete(DB_FILE);

    assertThat(migrate()).isEqualTo(1);
    assertThat(tableNames()).contains("event", "event_occurrence", "flyway_schema_history");
  }

  private static int migrate() {
    return Flyway.configure()
        .dataSource(dataSource())
        .locations("classpath:db/migration")
        .load()
        .migrate()
        .migrationsExecuted;
  }

  private static List<String> tableNames() throws SQLException {
    List<String> names = new ArrayList<>();
    try (Connection connection = dataSource().getConnection();
        ResultSet rows =
            connection
                .createStatement()
                .executeQuery("select name from sqlite_master where type = 'table'")) {
      while (rows.next()) {
        names.add(rows.getString(1));
      }
    }
    return names;
  }

  private static DataSource dataSource() {
    return new DriverManagerDataSource(SqliteTestDatabase.url(DB_FILE));
  }
}
