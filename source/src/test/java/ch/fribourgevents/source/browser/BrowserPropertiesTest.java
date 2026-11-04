package ch.fribourgevents.source.browser;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** Binding and validation tests for the headless-browser settings. */
class BrowserPropertiesTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class);

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(BrowserProperties.class)
  static class PropertiesConfiguration {}

  @Test
  void appliesSaneDefaults() {
    runner.run(context -> {
      assertThat(context).hasNotFailed();
      BrowserProperties properties = context.getBean(BrowserProperties.class);

      assertThat(properties.headless()).isTrue();
      assertThat(properties.width()).isEqualTo(1920);
      assertThat(properties.height()).isEqualTo(1080);
      assertThat(properties.userAgent()).isEqualTo("fribourg-events-digest/1.0");
      assertThat(properties.pageLoadTimeout()).isEqualTo(Duration.ofSeconds(30));
      assertThat(properties.readyStateTimeout()).isEqualTo(Duration.ofSeconds(30));
    });
  }

  @Test
  void bindsEveryOperatorSetting() {
    runner
        .withPropertyValues(
            "digest.browser.headless=false",
            "digest.browser.width=1280",
            "digest.browser.height=800",
            "digest.browser.user-agent=digest-test/2.0",
            "digest.browser.page-load-timeout=15s",
            "digest.browser.ready-state-timeout=5s")
        .run(context -> {
          assertThat(context).hasNotFailed();
          BrowserProperties properties = context.getBean(BrowserProperties.class);

          assertThat(properties.headless()).isFalse();
          assertThat(properties.width()).isEqualTo(1280);
          assertThat(properties.height()).isEqualTo(800);
          assertThat(properties.userAgent()).isEqualTo("digest-test/2.0");
          assertThat(properties.pageLoadTimeout()).isEqualTo(Duration.ofSeconds(15));
          assertThat(properties.readyStateTimeout()).isEqualTo(Duration.ofSeconds(5));
        });
  }

  @Test
  void rejectsNonPositiveWindowSize() {
    runner
        .withPropertyValues("digest.browser.width=0")
        .run(context ->
            assertThat(context.getStartupFailure())
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .rootCause()
                .hasMessageContaining("digest.browser.width"));
  }

  @Test
  void rejectsNonPositiveTimeouts() {
    runner
        .withPropertyValues("digest.browser.page-load-timeout=0s")
        .run(context ->
            assertThat(context.getStartupFailure())
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .rootCause()
                .hasMessageContaining("digest.browser.page-load-timeout"));
  }

  @Test
  void fallsBackToTheDefaultWhenTheUserAgentIsBlank() {
    runner
        .withPropertyValues("digest.browser.user-agent=  ")
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.getBean(BrowserProperties.class).userAgent())
              .isEqualTo(BrowserProperties.DEFAULT_USER_AGENT);
        });
  }
}
