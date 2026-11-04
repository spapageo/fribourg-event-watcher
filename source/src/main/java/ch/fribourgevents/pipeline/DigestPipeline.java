package ch.fribourgevents.pipeline;

import ch.fribourgevents.dedup.DedupResolver;
import ch.fribourgevents.digest.DigestMessage;
import ch.fribourgevents.digest.DigestService;
import ch.fribourgevents.digest.DigestTextRenderer;
import ch.fribourgevents.domain.FetchWindow;
import ch.fribourgevents.domain.SourceEvent;
import ch.fribourgevents.domain.SourceId;
import ch.fribourgevents.notify.DeliveryResult;
import ch.fribourgevents.notify.DeliveryResult.DeliveryStatus;
import ch.fribourgevents.notify.NotificationException;
import ch.fribourgevents.notify.NotificationSender;
import ch.fribourgevents.pipeline.PipelineResult.RunStatus;
import ch.fribourgevents.source.EventSource;
import ch.fribourgevents.source.SourceFetch;
import ch.fribourgevents.source.SourceFetchException;
import ch.fribourgevents.store.EventStore;
import ch.fribourgevents.store.UpsertResult;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One digest run: fetch every enabled source, upsert into the store, select, render, deliver.
 *
 * <p>Isolation is the point: sources run independently, so one failing source yields {@link
 * RunStatus#PARTIAL} and a footer naming it while the rest of the digest is still delivered. Only
 * when every source fails does the run avoid the digest entirely ({@link RunStatus#FAILED}) — an
 * empty "no events" message would be a lie.
 *
 * <p>Execution history is not persisted. The run emits exactly four kinds of structured log lines
 * (contract in {@code docs/SPEC.md} §4) and returns a {@link PipelineResult} for callers and tests:
 *
 * <pre>
 * digest_run_started, source_sync (one per source), notification_delivery, digest_run_finished
 * </pre>
 *
 * <p>Not thread-safe per instance (it holds no mutable state, but a run should not overlap a
 * previous one); the scheduler in the {@code runtime} module owns that guarantee.
 */
public final class DigestPipeline {

  private static final Logger log = LoggerFactory.getLogger(DigestPipeline.class);

  /** Bound on a logged error so one bad response cannot flood the run log. */
  private static final int MAX_ERROR_LENGTH = 200;

  /** Channel name used in the {@code notification_delivery} line when a dry run skips sending. */
  private static final String DRY_RUN_CHANNEL = "dry-run";

  private final List<EventSource> sources;
  private final EventStore store;
  private final DedupResolver dedupResolver;
  private final DigestService digestService;
  private final NotificationSender sender;
  private final DigestTextRenderer renderer;
  private final Clock clock;
  private final PipelineOptions options;

  public DigestPipeline(
      List<EventSource> sources,
      EventStore store,
      DedupResolver dedupResolver,
      DigestService digestService,
      NotificationSender sender,
      DigestTextRenderer renderer,
      Clock clock,
      PipelineOptions options) {
    this.sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
    this.store = Objects.requireNonNull(store, "store");
    this.dedupResolver = Objects.requireNonNull(dedupResolver, "dedupResolver");
    this.digestService = Objects.requireNonNull(digestService, "digestService");
    this.sender = Objects.requireNonNull(sender, "sender");
    this.renderer = Objects.requireNonNull(renderer, "renderer");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.options = Objects.requireNonNull(options, "options");
  }

  /**
   * Runs the pipeline once for {@code window}.
   *
   * <p>Returns normally for every outcome the spec defines, including failures: the caller reads
   * {@link PipelineResult#status()} instead of catching. Only programming errors escape.
   */
  public PipelineResult run(FetchWindow window) {
    Objects.requireNonNull(window, "window");
    Instant startedAt = clock.instant();
    String runId = UUID.randomUUID().toString();
    log.info(
        "digest_run_started run_id={} window_from={} window_to={} sources={} dry_run={}",
        runId,
        window.from(),
        window.to(),
        sourceNames(),
        options.dryRun());

    List<SourceSyncResult> outcomes = new ArrayList<>(sources.size());
    for (EventSource source : sources) {
      outcomes.add(sync(source, window, runId));
    }

    if (sources.isEmpty()) {
      logDelivery(runId, "-", "SKIPPED", 0, List.of(), "no sources are enabled");
      return finish(
          runId, window, RunStatus.FAILED, outcomes, List.of(), Optional.empty(), startedAt);
    }

    List<SourceId> failed = failedSources(outcomes);
    if (failed.size() == sources.size()) {
      return runFailed(runId, window, outcomes, failed, startedAt);
    }

    DigestMessage message = digestService.select(window);
    List<String> notes = footerNotes(outcomes);
    if (!notes.isEmpty()) {
      message = message.withNotes(notes);
    }
    List<Long> occurrenceIds = selectedOccurrenceIds(message);

    if (options.dryRun()) {
      log.info("digest_run_dry_run run_id={} digest=\n{}", runId, renderer.render(message));
      logDelivery(runId, DRY_RUN_CHANNEL, "SKIPPED", 0, List.of(), null);
      RunStatus status = failed.isEmpty() ? RunStatus.SUCCESS : RunStatus.PARTIAL;
      return finish(runId, window, status, outcomes, occurrenceIds, Optional.empty(), startedAt);
    }

    Optional<DeliveryResult> delivery = send(message, runId);
    RunStatus status;
    if (delivery.isEmpty()) {
      status = RunStatus.FAILED;
    } else if (!failed.isEmpty() || delivery.get().status() == DeliveryStatus.PARTIAL) {
      status = RunStatus.PARTIAL;
    } else {
      status = RunStatus.SUCCESS;
    }
    return finish(runId, window, status, outcomes, occurrenceIds, delivery, startedAt);
  }

  /** Fetches and stores one source; never throws, reports the outcome instead. */
  private SourceSyncResult sync(EventSource source, FetchWindow window, String runId) {
    Instant startedAt = clock.instant();
    SourceFetch fetch = null;
    try {
      fetch = source.fetch(window);
      int inserted = 0;
      int merged = 0;
      for (SourceEvent event : fetch.events()) {
        Optional<Long> canonical = dedupResolver.findCanonicalEventId(event);
        UpsertResult result = store.upsert(event, clock.instant(), canonical.orElse(null));
        if (result.eventCreated()) {
          inserted++;
        } else if (result.merged()) {
          merged++;
        }
      }
      SourceSyncResult result =
          SourceSyncResult.ok(
              source.id(),
              startedAt,
              clock.instant(),
              fetch.pagesFetched(),
              fetch.eventCount(),
              inserted,
              merged);
      logSourceSync(runId, result);
      return result;
    } catch (SourceFetchException | RuntimeException e) {
      SourceSyncResult result =
          SourceSyncResult.failed(
              source.id(),
              startedAt,
              clock.instant(),
              fetch == null ? 0 : fetch.pagesFetched(),
              fetch == null ? 0 : fetch.eventCount(),
              describe(e));
      logSourceSync(runId, result);
      return result;
    }
  }

  /** Every source failed: do not build an empty digest; send a notice only when configured. */
  private PipelineResult runFailed(
      String runId,
      FetchWindow window,
      List<SourceSyncResult> outcomes,
      List<SourceId> failed,
      Instant startedAt) {
    DigestMessage notice = failureNotice(window, failed);
    Optional<DeliveryResult> delivery = Optional.empty();
    if (options.dryRun()) {
      log.info("digest_run_dry_run run_id={} digest=\n{}", runId, renderer.render(notice));
      logDelivery(runId, DRY_RUN_CHANNEL, "SKIPPED", 0, List.of(), null);
    } else if (options.sendFailureNotice()) {
      delivery = send(notice, runId);
    } else {
      logDelivery(
          runId, "-", "SKIPPED", 0, List.of(), "all sources failed; failure notice disabled");
    }
    return finish(runId, window, RunStatus.FAILED, outcomes, List.of(), delivery, startedAt);
  }

  private Optional<DeliveryResult> send(DigestMessage message, String runId) {
    try {
      DeliveryResult result = sender.send(message);
      logDelivery(
          runId,
          result.channel(),
          result.status().name(),
          result.parts(),
          result.externalIds(),
          null);
      return Optional.of(result);
    } catch (NotificationException | RuntimeException e) {
      logDelivery(runId, "-", "FAILED", 0, List.of(), describe(e));
      return Optional.empty();
    }
  }

  private PipelineResult finish(
      String runId,
      FetchWindow window,
      RunStatus status,
      List<SourceSyncResult> outcomes,
      List<Long> occurrenceIds,
      Optional<DeliveryResult> delivery,
      Instant startedAt) {
    Instant finishedAt = clock.instant();
    log.info(
        "digest_run_finished run_id={} status={} events_selected={} events_seen={}"
            + " events_inserted={} events_merged={} delivered={}",
        runId,
        status,
        occurrenceIds.size(),
        total(outcomes, SourceSyncResult::eventsSeen),
        total(outcomes, SourceSyncResult::eventsInserted),
        total(outcomes, SourceSyncResult::eventsMerged),
        delivery.isPresent());
    return new PipelineResult(
        runId, window, status, outcomes, occurrenceIds, delivery, startedAt, finishedAt);
  }

  private void logSourceSync(String runId, SourceSyncResult result) {
    log.info(
        "source_sync run_id={} source={} status={} pages_fetched={} events_seen={}"
            + " events_inserted={} events_merged={} duration_ms={} error={}",
        runId,
        result.source().wireName(),
        result.status(),
        result.pagesFetched(),
        result.eventsSeen(),
        result.eventsInserted(),
        result.eventsMerged(),
        result.durationMillis(),
        result.error() == null ? "" : result.error());
  }

  private static void logDelivery(
      String runId,
      String channel,
      String status,
      int messageCount,
      List<String> externalIds,
      String error) {
    log.info(
        "notification_delivery run_id={} channel={} status={} message_count={} external_id={}"
            + " error={}",
        runId,
        channel,
        status,
        messageCount,
        externalIds.isEmpty() ? "" : String.join(",", externalIds),
        error == null ? "" : error);
  }

  private static DigestMessage failureNotice(FetchWindow window, List<SourceId> failed) {
    return new DigestMessage(
        window,
        List.of(),
        0,
        0,
        List.of("Digest failed: no source could be read (" + names(failed) + ")."));
  }

  /**
   * Footer paragraphs for this run: unavailable sources, then the dedup summary when enabled. The
   * failure note is never suppressed; the merged count is a reporting nicety and configurable.
   */
  private List<String> footerNotes(List<SourceSyncResult> outcomes) {
    List<String> notes = new ArrayList<>();
    List<SourceId> failed = failedSources(outcomes);
    if (!failed.isEmpty()) {
      notes.add(unavailableFooter(failed));
    }
    int merged = total(outcomes, SourceSyncResult::eventsMerged);
    if (options.showMergedCount() && merged > 0) {
      notes.add(
          merged
              + (merged == 1 ? " event" : " events")
              + " appeared in more than one source and were merged.");
    }
    return notes;
  }

  private static int total(List<SourceSyncResult> outcomes, ToIntFunction<SourceSyncResult> count) {
    return outcomes.stream().mapToInt(count).sum();
  }

  private static String unavailableFooter(List<SourceId> failed) {
    String noun = failed.size() == 1 ? "Source" : "Sources";
    return noun + " unavailable this run: " + names(failed) + ". Events may be missing.";
  }

  private static List<Long> selectedOccurrenceIds(DigestMessage message) {
    return message.days().stream()
        .flatMap(day -> day.items().stream())
        .map(DigestMessage.Item::occurrenceId)
        .filter(Objects::nonNull)
        .toList();
  }

  private static List<SourceId> failedSources(List<SourceSyncResult> outcomes) {
    return outcomes.stream()
        .filter(SourceSyncResult::failed)
        .map(SourceSyncResult::source)
        .toList();
  }

  private static String names(List<SourceId> ids) {
    return ids.stream().map(SourceId::wireName).collect(Collectors.joining(", "));
  }

  private String sourceNames() {
    return sources.stream()
        .map(source -> source.id().wireName())
        .collect(Collectors.joining(",", "[", "]"));
  }

  /** Log-safe, single-line, bounded description of a failure. */
  private static String describe(Throwable error) {
    String message = error.getMessage();
    String detail =
        message == null || message.isBlank()
            ? error.getClass().getSimpleName()
            : error.getClass().getSimpleName() + ": " + message;
    detail = detail.replaceAll("\\s+", " ").strip();
    return detail.length() > MAX_ERROR_LENGTH
        ? detail.substring(0, MAX_ERROR_LENGTH) + "…"
        : detail;
  }
}
