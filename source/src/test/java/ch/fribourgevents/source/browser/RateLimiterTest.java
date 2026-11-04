package ch.fribourgevents.source.browser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;

/**
 * Deterministic tests for the per-host pacing rule: the limiter must never let two requests to the
 * same host leave less than one second apart, while another host is unaffected.
 *
 * <p>Time is a fake {@link LongSupplier} that only moves when {@link FakeTime#sleep(Duration)} is
 * called, so the assertions are exact and the suite never sleeps.
 */
class RateLimiterTest {

  private static final URI BKA_AGENDA = URI.create("https://bka.ch/agenda");
  private static final URI BKA_EVENT = URI.create("https://bka.ch/event/foo");
  private static final URI EVENTFROG = URI.create("https://eventfrog.ch/de/events/bern");

  private final FakeTime time = new FakeTime();

  @Test
  void letsTheFirstRequestThroughWithoutWaiting() throws InterruptedException {
    RateLimiter limiter = new RateLimiter(1, time, time);

    limiter.acquire(BKA_AGENDA);

    assertThat(time.sleeps).isEmpty();
  }

  @Test
  void spacesTwoRequestsToOneHostByOneSecond() throws InterruptedException {
    RateLimiter limiter = new RateLimiter(1, time, time);

    limiter.acquire(BKA_AGENDA);
    limiter.acquire(BKA_EVENT);

    assertThat(time.sleeps).containsExactly(Duration.ofSeconds(1));
    assertThat(time.getAsLong()).isEqualTo(Duration.ofSeconds(1).toNanos());
  }

  @Test
  void keepsPacingAcrossManyRequestsToOneHost() throws InterruptedException {
    RateLimiter limiter = new RateLimiter(1, time, time);

    limiter.acquire(BKA_AGENDA);
    limiter.acquire(BKA_EVENT);
    limiter.acquire(BKA_AGENDA);
    limiter.acquire(BKA_EVENT);

    assertThat(time.sleeps)
        .containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1));
  }

  @Test
  void doesNotBlockADifferentHost() throws InterruptedException {
    RateLimiter limiter = new RateLimiter(1, time, time);

    limiter.acquire(BKA_AGENDA);
    limiter.acquire(EVENTFROG);

    assertThat(time.sleeps).isEmpty();
  }

  @Test
  void treatsTheHostCaseInsensitively() throws InterruptedException {
    RateLimiter limiter = new RateLimiter(1, time, time);

    limiter.acquire(URI.create("https://BKA.CH/agenda"));
    limiter.acquire(URI.create("https://bka.ch/event/foo"));

    assertThat(time.sleeps).containsExactly(Duration.ofSeconds(1));
  }

  @Test
  void honoursAConfiguredRateBelowOneSecond() throws InterruptedException {
    RateLimiter limiter = new RateLimiter(2, time, time);

    limiter.acquire(BKA_AGENDA);
    limiter.acquire(BKA_EVENT);

    assertThat(time.sleeps).containsExactly(Duration.ofMillis(500));
  }

  @Test
  void rejectsANonPositiveRate() {
    assertThatThrownBy(() -> new RateLimiter(0, time, time))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("per-second");
  }

  @Test
  void rejectsAUriWithoutHost() {
    RateLimiter limiter = new RateLimiter(1, time, time);

    assertThatThrownBy(() -> limiter.acquire(URI.create("mailto:someone@bka.ch")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("host");
  }

  @Test
  void propagatesInterruption() {
    RateLimiter.Sleeper interrupted =
        duration -> {
          throw new InterruptedException("shutting down");
        };
    RateLimiter limiter = new RateLimiter(1, time, interrupted);
    acquireUnchecked(limiter, BKA_AGENDA);

    assertThatThrownBy(() -> limiter.acquire(BKA_EVENT)).isInstanceOf(InterruptedException.class);
  }

  private void acquireUnchecked(RateLimiter limiter, URI uri) {
    try {
      limiter.acquire(uri);
    } catch (InterruptedException e) {
      throw new AssertionError("unexpected interruption", e);
    }
  }

  /** A fake monotonic clock whose only source of progress is the limiter's own sleep calls. */
  private static final class FakeTime implements LongSupplier, RateLimiter.Sleeper {

    private final List<Duration> sleeps = new ArrayList<>();
    private long nanos;

    @Override
    public long getAsLong() {
      return nanos;
    }

    @Override
    public void sleep(Duration duration) {
      sleeps.add(duration);
      nanos += duration.toNanos();
    }
  }
}
