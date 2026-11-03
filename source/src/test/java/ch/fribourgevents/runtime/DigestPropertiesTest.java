package ch.fribourgevents.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import ch.fribourgevents.domain.SourceId;
import java.time.Duration;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Binding and validation tests for {@link DigestProperties}: defaults, a full valid configuration,
 * and every rejection path that must abort startup with a clear message.
 */
class DigestPropertiesTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class);

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(DigestProperties.class)
  static class PropertiesConfiguration {}

  @Test
  void appliesTheSpecDefaultsWhenDryRunning() {
    runner
        .withPropertyValues("digest.dry-run=true")
        .run(context -> {
          assertThat(context).hasNotFailed();
          DigestProperties properties = context.getBean(DigestProperties.class);

          assertThat(properties.cron()).isEqualTo("0 0 7 * * MON");
          assertThat(properties.zone()).isEqualTo("Europe/Zurich");
          assertThat(properties.zoneId()).isEqualTo(ZoneId.of("Europe/Zurich"));
          assertThat(properties.windowDays()).isEqualTo(14);
          assertThat(properties.runOnStartup()).isFalse();
          assertThat(properties.dryRun()).isTrue();
          assertThat(properties.sendFailureNotice()).isFalse();
          assertThat(properties.dbPath()).isEqualTo("../data/digest.db");
          assertThat(properties.telegram().baseUrl()).isEqualTo("https://api.telegram.org");
          assertThat(properties.telegram().partLimit()).isEqualTo(4000);
          assertThat(properties.enabled(SourceId.INSITU)).isTrue();
          assertThat(properties.enabled(SourceId.BKA)).isTrue();
          assertThat(properties.enabled(SourceId.EVENTFROG)).isTrue();
          assertThat(properties.rateLimit().perSecond()).isEqualTo(1);
          assertThat(properties.rateLimit().maxPerRun()).isEqualTo(60);
          assertThat(properties.http().requestTimeout()).isEqualTo(Duration.ofSeconds(10));
        });
  }

  @Test
  void bindsEveryOperatorSetting() {
    runner
        .withPropertyValues(
            "digest.cron=0 30 6 * * TUE",
            "digest.zone=Europe/Paris",
            "digest.window-days=7",
            "digest.run-on-startup=true",
            "digest.send-failure-notice=true",
            "digest.db-path=/data/digest.db",
            "digest.telegram.bot-token=123456:secret",
            "digest.telegram.chat-id=42",
            "digest.telegram.base-url=http://telegram.test/",
            "digest.telegram.part-limit=3500",
            "digest.http.request-timeout=20s",
            "digest.rate-limit.per-second=2",
            "digest.rate-limit.max-per-run=30",
            "digest.sources.insitu.enabled=false",
            "digest.sources.bka.enabled=true",
            "digest.sources.eventfrog.enabled=false")
        .run(context -> {
          assertThat(context).hasNotFailed();
          DigestProperties properties = context.getBean(DigestProperties.class);

          assertThat(properties.cron()).isEqualTo("0 30 6 * * TUE");
          assertThat(properties.zoneId()).isEqualTo(ZoneId.of("Europe/Paris"));
          assertThat(properties.windowDays()).isEqualTo(7);
          assertThat(properties.runOnStartup()).isTrue();
          assertThat(properties.sendFailureNotice()).isTrue();
          assertThat(properties.dbPath()).isEqualTo("/data/digest.db");
          assertThat(properties.telegram().toSettings().botToken()).isEqualTo("123456:secret");
          assertThat(properties.telegram().toSettings().chatId()).isEqualTo("42");
          assertThat(properties.telegram().baseUrl()).isEqualTo("http://telegram.test");
          assertThat(properties.telegram().partLimit()).isEqualTo(3500);
          assertThat(properties.http().requestTimeout()).isEqualTo(Duration.ofSeconds(20));
          assertThat(properties.rateLimit().perSecond()).isEqualTo(2);
          assertThat(properties.rateLimit().maxPerRun()).isEqualTo(30);
          assertThat(properties.enabled(SourceId.INSITU)).isFalse();
          assertThat(properties.enabled(SourceId.BKA)).isTrue();
          assertThat(properties.enabled(SourceId.EVENTFROG)).isFalse();
        });
  }

  @Test
  void requiresTelegramCredentialsForANonDryRun() {
    assertBindingFails("digest.telegram.bot-token");
    assertBindingFails("digest.telegram.chat-id", "digest.telegram.bot-token=123456:secret");
  }

  @Test
  void rejectsAnInvalidCron() {
    assertBindingFails("digest.cron", "digest.dry-run=true", "digest.cron=not a cron");
  }

  @Test
  void rejectsAnInvalidZone() {
    assertBindingFails("digest.zone", "digest.dry-run=true", "digest.zone=Mars/Olympus");
  }

  @Test
  void rejectsANonPositiveWindow() {
    assertBindingFails("digest.window-days", "digest.dry-run=true", "digest.window-days=0");
  }

  @Test
  void rejectsAnInvalidRateLimit() {
    assertBindingFails(
        "digest.rate-limit.per-second", "digest.dry-run=true", "digest.rate-limit.per-second=0");
    assertBindingFails(
        "digest.rate-limit.max-per-run", "digest.dry-run=true", "digest.rate-limit.max-per-run=-1");
  }

  @Test
  void rejectsATooSmallTelegramPartLimit() {
    assertBindingFails(
        "digest.telegram.part-limit", "digest.dry-run=true", "digest.telegram.part-limit=10");
  }

  @Test
  void rejectsANonPositiveHttpTimeout() {
    assertBindingFails(
        "digest.http.request-timeout", "digest.dry-run=true", "digest.http.request-timeout=0s");
  }

  private void assertBindingFails(String expectedMessage, String... properties) {
    runner
        .withPropertyValues(properties)
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure())
              .getRootCause()
              .hasMessageContaining(expectedMessage);
        });
  }
}
