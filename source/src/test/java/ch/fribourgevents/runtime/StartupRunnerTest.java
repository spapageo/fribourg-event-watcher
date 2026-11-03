package ch.fribourgevents.runtime;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;

/** The startup trigger fires exactly one pipeline invocation, and only when enabled. */
class StartupRunnerTest {

  private final DigestScheduler scheduler = mock(DigestScheduler.class);
  private final ApplicationArguments arguments = mock(ApplicationArguments.class);

  @Test
  void runsThePipelineOnceWhenEnabled() {
    new StartupRunner(scheduler, properties(true)).run(arguments);

    verify(scheduler, times(1)).runOrSkip("startup");
  }

  @Test
  void doesNothingWhenDisabled() {
    new StartupRunner(scheduler, properties(false)).run(arguments);

    verifyNoInteractions(scheduler);
  }

  private static DigestProperties properties(boolean runOnStartup) {
    return new DigestProperties(
        "0 0 7 * * MON",
        "Europe/Zurich",
        14,
        runOnStartup,
        false,
        false,
        null,
        new DigestProperties.Telegram("123456:secret", "42", null, 4000),
        null,
        null,
        null);
  }
}
