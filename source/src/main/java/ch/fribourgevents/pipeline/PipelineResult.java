package ch.fribourgevents.pipeline;

import ch.fribourgevents.domain.FetchWindow;
import ch.fribourgevents.domain.SourceId;
import ch.fribourgevents.notify.DeliveryResult;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * In-memory summary of one digest run: what every source did, which occurrences were selected, and
 * whether a delivery happened.
 *
 * <p>This is the replacement for the removed run-history tables (see {@code docs/SPEC.md} §4):
 * callers log it or inspect it in tests; nothing persists it.
 *
 * @param runId identifier shared by all four structured log lines of this run
 * @param window the fetch/selection window
 * @param status overall outcome
 * @param sources one outcome per enabled source, in execution order
 * @param selectedOccurrenceIds occurrence ids included in the digest (after the item cap)
 * @param delivery transport outcome, empty when nothing was sent (dry run, skipped, or failed)
 * @param startedAt when the run began
 * @param finishedAt when the run returned
 */
public record PipelineResult(
    String runId,
    FetchWindow window,
    RunStatus status,
    List<SourceSyncResult> sources,
    List<Long> selectedOccurrenceIds,
    Optional<DeliveryResult> delivery,
    Instant startedAt,
    Instant finishedAt) {

  public enum RunStatus {
    /** Every enabled source was read and the digest was delivered (or rendered, for a dry run). */
    SUCCESS,
    /**
     * Something was missing — a failed source or a partial delivery — but the run produced value.
     */
    PARTIAL,
    /** Nothing useful happened: no source could be read or the delivery failed. */
    FAILED
  }

  public PipelineResult {
    Objects.requireNonNull(runId, "runId");
    if (runId.isBlank()) {
      throw new IllegalArgumentException("runId must not be blank");
    }
    Objects.requireNonNull(window, "window");
    Objects.requireNonNull(status, "status");
    sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
    selectedOccurrenceIds =
        List.copyOf(Objects.requireNonNull(selectedOccurrenceIds, "selectedOccurrenceIds"));
    delivery = Objects.requireNonNull(delivery, "delivery");
    Objects.requireNonNull(startedAt, "startedAt");
    Objects.requireNonNull(finishedAt, "finishedAt");
    if (finishedAt.isBefore(startedAt)) {
      throw new IllegalArgumentException("finishedAt must not precede startedAt");
    }
  }

  /** Sources that failed this run, in execution order. */
  public List<SourceId> failedSources() {
    return sources.stream().filter(SourceSyncResult::failed).map(SourceSyncResult::source).toList();
  }

  /** Total events every source returned during this run. */
  public int eventsSeen() {
    return sources.stream().mapToInt(SourceSyncResult::eventsSeen).sum();
  }

  /** Canonical events created during this run. */
  public int eventsInserted() {
    return sources.stream().mapToInt(SourceSyncResult::eventsInserted).sum();
  }

  /** Source records attached to an existing canonical event during this run. */
  public int eventsMerged() {
    return sources.stream().mapToInt(SourceSyncResult::eventsMerged).sum();
  }

  /** True when a notification was handed to a transport (dry runs report {@code false}). */
  public boolean delivered() {
    return delivery.isPresent();
  }
}
