package ch.fribourgevents.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.fribourgevents.domain.FetchWindow;
import ch.fribourgevents.pipeline.DigestPipeline;
import ch.fribourgevents.pipeline.PipelineResult;
import ch.fribourgevents.pipeline.PipelineResult.RunStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Window computation and the no-overlap guard, with a mocked pipeline and a fixed clock. */
class DigestSchedulerTest {

  private static final ZoneId ZURICH = ZoneId.of("Europe/Zurich");
  private static final Instant NOW = Instant.parse("2026-11-03T05:30:00Z");

  private final DigestPipeline pipeline = mock(DigestPipeline.class);

  @Test
  void runsWithALocalMidnightFourteenDayWindow() {
    stubSuccess();

    Optional<PipelineResult> result = scheduler(Clock.fixed(NOW, ZURICH)).runOrSkip("test");

    assertThat(result).isPresent();
    verify(pipeline)
        .run(
            new FetchWindow(
                Instant.parse("2026-11-02T23:00:00Z"),
                Instant.parse("2026-11-16T23:00:00Z"),
                ZURICH));
  }

  @Test
  void startsOnTheLocalDayEvenWhenUtcIsStillYesterday() {
    stubSuccess();
    // 23:30 UTC on 3 Nov is already 4 Nov in Zurich; the window must start at 4 Nov local midnight.
    Clock lateNight = Clock.fixed(Instant.parse("2026-11-03T23:30:00Z"), ZURICH);

    scheduler(lateNight).runOrSkip("test");

    verify(pipeline)
        .run(
            new FetchWindow(
                Instant.parse("2026-11-03T23:00:00Z"),
                Instant.parse("2026-11-17T23:00:00Z"),
                ZURICH));
  }

  @Test
  void skipsATriggerWhileAnotherRunIsStillActive() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    when(pipeline.run(any()))
        .thenAnswer(invocation -> {
          entered.countDown();
          assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
          return summary(invocation.getArgument(0));
        });
    DigestScheduler scheduler = scheduler(Clock.fixed(NOW, ZURICH));

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<Optional<PipelineResult>> first = executor.submit(() -> scheduler.runOrSkip("first"));
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

      assertThat(scheduler.runOrSkip("second")).isEmpty();

      release.countDown();
      assertThat(first.get(5, TimeUnit.SECONDS)).isPresent();
      verify(pipeline, times(1)).run(any());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void releasesTheGuardAfterACrashedRun() {
    when(pipeline.run(any())).thenThrow(new IllegalStateException("database is locked"));
    DigestScheduler scheduler = scheduler(Clock.fixed(NOW, ZURICH));

    assertThat(scheduler.runOrSkip("crash")).isEmpty();

    reset(pipeline);
    stubSuccess();
    assertThat(scheduler.runOrSkip("next")).isPresent();
    verify(pipeline, times(1)).run(any());
  }

  @Test
  void scheduledRunGoesThroughTheSameGuard() {
    stubSuccess();

    scheduler(Clock.fixed(NOW, ZURICH)).scheduledRun();

    verify(pipeline, times(1)).run(any());
  }

  private void stubSuccess() {
    when(pipeline.run(any())).thenAnswer(invocation -> summary(invocation.getArgument(0)));
  }

  private DigestScheduler scheduler(Clock clock) {
    return new DigestScheduler(pipeline, properties(), clock);
  }

  private static DigestProperties properties() {
    return new DigestProperties(
        "0 0 7 * * MON",
        "Europe/Zurich",
        14,
        false,
        false,
        false,
        null,
        new DigestProperties.Telegram("123456:secret", "42", null, 4000),
        null,
        null,
        null);
  }

  private static PipelineResult summary(FetchWindow window) {
    return new PipelineResult(
        "run-1", window, RunStatus.SUCCESS, List.of(), List.of(), Optional.empty(), NOW, NOW);
  }
}
