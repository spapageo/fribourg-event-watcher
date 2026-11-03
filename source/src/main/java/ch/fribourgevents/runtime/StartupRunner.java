package ch.fribourgevents.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Runs the pipeline once after the context is ready when {@code digest.run-on-startup=true}.
 *
 * <p>An {@link ApplicationRunner} is invoked exactly once per boot by Spring, which is the "once"
 * half of the semantics; the scheduler's lock supplies the other half (a startup run cannot overlap
 * a scheduled one). Disabled by default so a local boot or a test never touches the network.
 */
@Component
public class StartupRunner implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(StartupRunner.class);

  private final DigestScheduler scheduler;
  private final DigestProperties properties;

  public StartupRunner(DigestScheduler scheduler, DigestProperties properties) {
    this.scheduler = scheduler;
    this.properties = properties;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!properties.runOnStartup()) {
      log.info("startup_run_skipped reason=digest.run-on-startup is false");
      return;
    }
    log.info("startup_run_started reason=digest.run-on-startup is true");
    scheduler.runOrSkip("startup");
  }
}
