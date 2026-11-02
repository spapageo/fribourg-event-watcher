package ch.fribourgevents.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Creates a real temporary SQLite database file for store integration tests. */
final class SqliteTestDatabase {

  private SqliteTestDatabase() {}

  static Path create(String prefix) {
    try {
      return Files.createTempFile("fribourg-events-" + prefix + "-", ".db");
    } catch (IOException e) {
      throw new UncheckedIOException("Could not create test SQLite database", e);
    }
  }

  static String url(Path file) {
    return "jdbc:sqlite:" + file.toAbsolutePath() + "?foreign_keys=on&busy_timeout=5000";
  }

  static void delete(Path file) {
    try {
      Files.deleteIfExists(file);
    } catch (IOException ignored) {
      // The OS temp cleaner is an acceptable fallback for a test artifact.
    }
  }
}
