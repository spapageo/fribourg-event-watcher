package ch.fribourgevents.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import ch.fribourgevents.domain.FetchWindow;
import ch.fribourgevents.domain.SourceId;
import ch.fribourgevents.pipeline.PipelineResult.RunStatus;
import ch.fribourgevents.pipeline.SourceSyncResult.Status;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PipelineResultTest {

  private static final Instant STARTED = Instant.parse("2026-11-03T06:00:00Z");
  private static final Instant FINISHED = Instant.parse("2026-11-03T06:00:04Z");
  private static final FetchWindow WINDOW =
      new FetchWindow(
          Instant.parse("2026-11-03T00:00:00Z"),
          Instant.parse("2026-11-17T00:00:00Z"),
          ZoneId.of("Europe/Zurich"));

  @Test
  void reportsFailedSourcesAndDeliveryPresence() {
    SourceSyncResult ok = SourceSyncResult.ok(SourceId.EVENTFROG, STARTED, FINISHED, 3, 12, 10, 2);
    SourceSyncResult failed =
        SourceSyncResult.failed(SourceId.BKA, STARTED, FINISHED, 1, 0, "bka: selector broke");

    PipelineResult result =
        new PipelineResult(
            "run-1",
            WINDOW,
            RunStatus.PARTIAL,
            List.of(failed, ok),
            List.of(1L, 2L),
            Optional.empty(),
            STARTED,
            FINISHED);

    assertThat(result.failedSources()).containsExactly(SourceId.BKA);
    assertThat(result.eventsSeen()).isEqualTo(12);
    assertThat(result.eventsInserted()).isEqualTo(10);
    assertThat(result.eventsMerged()).isEqualTo(2);
    assertThat(result.delivered()).isFalse();
    assertThat(ok.durationMillis()).isEqualTo(4000);
    assertThat(ok.failed()).isFalse();
    assertThat(failed.failed()).isTrue();
  }

  @Test
  void rejectsInconsistentSummaries() {
    assertThatIllegalArgumentException()
        .isThrownBy(() ->
            new PipelineResult(
                " ",
                WINDOW,
                RunStatus.SUCCESS,
                List.of(),
                List.of(),
                Optional.empty(),
                STARTED,
                FINISHED));
    assertThatIllegalArgumentException()
        .isThrownBy(() ->
            new PipelineResult(
                "run-1",
                WINDOW,
                RunStatus.SUCCESS,
                List.of(),
                List.of(),
                Optional.empty(),
                FINISHED,
                STARTED));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> SourceSyncResult.ok(SourceId.INSITU, FINISHED, STARTED, 1, 1, 1, 0));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> SourceSyncResult.ok(SourceId.INSITU, STARTED, FINISHED, -1, 1, 1, 0));
    assertThatIllegalArgumentException()
        .isThrownBy(() ->
            new SourceSyncResult(
                SourceId.INSITU, Status.FAILED, STARTED, FINISHED, 0, 0, 0, 0, ""));
    assertThatIllegalArgumentException()
        .isThrownBy(() ->
            new SourceSyncResult(
                SourceId.INSITU, Status.OK, STARTED, FINISHED, 0, 0, 0, 0, "unexpected"));
  }
}
