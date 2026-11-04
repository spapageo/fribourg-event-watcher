package ch.fribourgevents.pipeline;

import ch.fribourgevents.domain.SourceId;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Outcome of reading one source during a run, kept in memory and written to the {@code source_sync}
 * log line only — never to SQLite.
 *
 * @param source the adapter this outcome belongs to
 * @param status whether the fetch and its upserts completed
 * @param startedAt when the pipeline started reading this source
 * @param finishedAt when the last upsert returned or the failure was caught
 * @param pagesFetched upstream pages/requests the adapter read ({@code 0} on failure before paging)
 * @param eventsSeen events the adapter returned ({@code 0} when the fetch itself failed)
 * @param eventsInserted canonical events this source created during the run
 * @param eventsMerged events this source attached to an existing canonical event (cross-source dup)
 * @param error log-safe failure description, {@code null} for a successful sync
 */
public record SourceSyncResult(
    SourceId source,
    Status status,
    Instant startedAt,
    Instant finishedAt,
    int pagesFetched,
    int eventsSeen,
    int eventsInserted,
    int eventsMerged,
    String error) {

  public enum Status {
    OK,
    FAILED
  }

  public SourceSyncResult {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(startedAt, "startedAt");
    Objects.requireNonNull(finishedAt, "finishedAt");
    if (finishedAt.isBefore(startedAt)) {
      throw new IllegalArgumentException("finishedAt must not precede startedAt");
    }
    if (pagesFetched < 0) {
      throw new IllegalArgumentException("pagesFetched must not be negative");
    }
    if (eventsSeen < 0) {
      throw new IllegalArgumentException("eventsSeen must not be negative");
    }
    if (eventsInserted < 0 || eventsMerged < 0) {
      throw new IllegalArgumentException("inserted and merged counts must not be negative");
    }
    if (eventsInserted + eventsMerged > eventsSeen) {
      throw new IllegalArgumentException("inserted + merged must not exceed eventsSeen");
    }
    if (status == Status.OK && error != null) {
      throw new IllegalArgumentException("a successful sync must not carry an error");
    }
    if (status == Status.FAILED && (error == null || error.isBlank())) {
      throw new IllegalArgumentException("a failed sync must carry an error");
    }
  }

  public static SourceSyncResult ok(
      SourceId source,
      Instant startedAt,
      Instant finishedAt,
      int pagesFetched,
      int eventsSeen,
      int eventsInserted,
      int eventsMerged) {
    return new SourceSyncResult(
        source,
        Status.OK,
        startedAt,
        finishedAt,
        pagesFetched,
        eventsSeen,
        eventsInserted,
        eventsMerged,
        null);
  }

  public static SourceSyncResult failed(
      SourceId source,
      Instant startedAt,
      Instant finishedAt,
      int pagesFetched,
      int eventsSeen,
      String error) {
    return new SourceSyncResult(
        source, Status.FAILED, startedAt, finishedAt, pagesFetched, eventsSeen, 0, 0, error);
  }

  public boolean failed() {
    return status == Status.FAILED;
  }

  public long durationMillis() {
    return Duration.between(startedAt, finishedAt).toMillis();
  }
}
