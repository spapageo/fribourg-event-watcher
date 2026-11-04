package ch.fribourgevents.source.browser;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Paces requests so one host never receives more than the configured rate (default 1 per second).
 *
 * <p>The spec's global rule is ≤1 request/second per host. The limiter is the single place that
 * enforces it, so adapters cannot forget it. State is per host and per instance; a run creates one
 * limiter (see {@code RuntimeConfiguration}) and every browser request passes through it.
 *
 * <p>Time comes from an injected monotonic {@link LongSupplier} (nanos) and waiting from an
 * injected {@link Sleeper}, so tests are exact and never sleep. Not thread-safe with respect to
 * concurrent callers for the same instance; the pipeline is sequential by design.
 */
public final class RateLimiter {

  private static final long NANOS_PER_SECOND = Duration.ofSeconds(1).toNanos();

  private final long intervalNanos;
  private final LongSupplier nanoClock;
  private final Sleeper sleeper;
  private final Map<String, Long> nextAllowed = new ConcurrentHashMap<>();

  /** Production limiter: monotonic system time and a real thread sleep. */
  public RateLimiter(int perSecond) {
    this(perSecond, System::nanoTime, Sleeper.threadSleep());
  }

  /**
   * @param perSecond requests allowed per host per second; must be positive
   * @param nanoClock monotonic clock in nanoseconds
   * @param sleeper blocking wait used to fill the gap between two requests
   */
  public RateLimiter(int perSecond, LongSupplier nanoClock, Sleeper sleeper) {
    if (perSecond <= 0) {
      throw new IllegalArgumentException(
          "rate limit per-second must be positive, was " + perSecond);
    }
    this.intervalNanos = NANOS_PER_SECOND / perSecond;
    this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
  }

  /**
   * Blocks until a request to {@code uri}'s host is allowed, then records it.
   *
   * @throws IllegalArgumentException when the URI has no host
   * @throws InterruptedException when the wait is interrupted; the caller must decide whether to
   *     abort the run
   */
  public void acquire(URI uri) throws InterruptedException {
    Objects.requireNonNull(uri, "uri");
    String host = uri.getHost();
    if (host == null || host.isBlank()) {
      throw new IllegalArgumentException("rate limiting needs a URI with a host, got " + uri);
    }
    host = host.toLowerCase(Locale.ROOT);

    long now = nanoClock.getAsLong();
    long next = nextAllowed.getOrDefault(host, now);
    if (now < next) {
      sleeper.sleep(Duration.ofNanos(next - now));
      // Re-read the clock: a real sleep can overshoot, and a short one must not tighten the gap.
      now = Math.max(next, nanoClock.getAsLong());
    }
    nextAllowed.put(host, now + intervalNanos);
  }

  /** Waiting seam; throws on interruption so callers can restore the interrupt flag. */
  public interface Sleeper {

    void sleep(Duration duration) throws InterruptedException;

    /** The production sleeper: blocks the calling thread. */
    static Sleeper threadSleep() {
      return duration -> {
        if (!duration.isZero() && !duration.isNegative()) {
          Thread.sleep(duration);
        }
      };
    }
  }
}
