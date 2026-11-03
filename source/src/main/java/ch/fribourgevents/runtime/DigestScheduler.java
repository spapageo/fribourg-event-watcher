package ch.fribourgevents.runtime;

import ch.fribourgevents.domain.FetchWindow;
import ch.fribourgevents.pipeline.DigestPipeline;
import ch.fribourgevents.pipeline.PipelineResult;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Turns the pipeline into a scheduled service.
 *
 * <p>Both triggers (cron and startup) go through {@link #runOrSkip(String)}, which holds a lock for
 * the whole run: a scheduled run that fires while the startup run is still fetching is skipped and
 * logged instead of overlapping. The trade-off is at-most-once for the skipped trigger, which the
 * spec accepts for a weekly personal digest.
 *
 * <p>The window is computed from the injected {@link Clock} in the configured zone: local midnight
 * today through local midnight {@code window-days} later, so "the next 14 days" is stable
 * regardless of the time of day the run happens.
 */
@Component
public class DigestScheduler {

  private static final Logger log = LoggerFactory.getLogger(DigestScheduler.class);

  private final DigestPipeline pipeline;
  private final DigestProperties properties;
  private final Clock clock;
  private final ReentrantLock running = new ReentrantLock();

  public DigestScheduler(DigestPipeline pipeline, DigestProperties properties, Clock clock) {
    this.pipeline = pipeline;
    this.properties = properties;
    this.clock = clock;
  }

  /** Cron trigger; the schedule and zone come from {@code digest.cron}/{@code digest.zone}. */
  @Scheduled(cron = "${digest.cron}", zone = "${digest.zone}")
  public void scheduledRun() {
    runOrSkip("scheduled");
  }

  /**
   * Runs the pipeline unless another run is still active.
   *
   * @return the summary, or empty when this trigger was skipped or the run crashed before producing
   *     one
   */
  public Optional<PipelineResult> runOrSkip(String trigger) {
    if (!running.tryLock()) {
      log.warn("digest_run_skipped trigger={} reason=previous run still active", trigger);
      return Optional.empty();
    }
    try {
      FetchWindow window = window();
      log.info(
          "digest_run_triggered trigger={} window_from={} window_to={}",
          trigger,
          window.from(),
          window.to());
      return Optional.of(pipeline.run(window));
    } catch (RuntimeException e) {
      // Keep the scheduler thread alive; the pipeline already isolates per-source failures.
      log.error("digest_run_crashed trigger={} error={}", trigger, e.toString(), e);
      return Optional.empty();
    } finally {
      running.unlock();
    }
  }

  /** Local-midnight window of {@code digest.window-days} days starting today. */
  FetchWindow window() {
    ZoneId zone = properties.zoneId();
    LocalDate today = LocalDate.now(clock.withZone(zone));
    Instant from = today.atStartOfDay(zone).toInstant();
    Instant to = today.plusDays(properties.windowDays()).atStartOfDay(zone).toInstant();
    return new FetchWindow(from, to, zone);
  }
}
