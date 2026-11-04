package ch.fribourgevents.source.browser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import ch.fribourgevents.source.SourceFetchException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.chrome.ChromeOptions;

/**
 * Unit tests for the Selenium wrapper with a mocked {@link WebDriver}, so no browser is launched.
 *
 * <p>These cover the parts that break silently in production: the driver is always quit, the page
 * load timeout and window size are applied, requests are paced, and a navigation failure becomes
 * the adapter SPI's {@link SourceFetchException} instead of a Selenium exception.
 */
class SeleniumPageLoaderTest {

  private static final URI AGENDA = URI.create("https://bka.ch/agenda");
  private static final URI EVENT = URI.create("https://bka.ch/event/foo");

  private final BrowserProperties properties =
      new BrowserProperties(
          true,
          1280,
          800,
          "fribourg-events-digest/1.0",
          Duration.ofSeconds(5),
          Duration.ofMillis(200));
  private final FakeTime time = new FakeTime();

  private WebDriver driver;
  private WebDriver.Options options;
  private WebDriver.Timeouts timeouts;
  private WebDriver.Window window;
  private JavascriptExecutor javascript;

  @BeforeEach
  void setUp() {
    driver = mock(WebDriver.class, withSettings().extraInterfaces(JavascriptExecutor.class));
    javascript = (JavascriptExecutor) driver;
    options = mock(WebDriver.Options.class);
    timeouts = mock(WebDriver.Timeouts.class);
    window = mock(WebDriver.Window.class);
    when(driver.manage()).thenReturn(options);
    when(options.timeouts()).thenReturn(timeouts);
    when(options.window()).thenReturn(window);
    when(javascript.executeScript("return document.readyState")).thenReturn("complete");
    when(driver.getPageSource()).thenReturn("<html><body>agenda</body></html>");
  }

  @Test
  void returnsThePageSourceAfterWaitingForReadiness() throws SourceFetchException {
    SeleniumPageLoader loader = loader(time);

    String page = loader.load(AGENDA);

    assertThat(page).isEqualTo("<html><body>agenda</body></html>");
    verify(driver).get(AGENDA.toString());
    verify(timeouts).pageLoadTimeout(Duration.ofSeconds(5));
    verify(window).setSize(new Dimension(1280, 800));
    verify(javascript).executeScript("return document.readyState");
    verify(driver).quit();
  }

  @Test
  void quitsTheDriverWhenNavigationFails() {
    doThrow(new WebDriverException("boom")).when(driver).get(anyString());

    assertThatThrownBy(() -> loader(time).load(AGENDA))
        .isInstanceOf(SourceFetchException.class)
        .hasMessageContaining("bka.ch/agenda")
        .hasCauseInstanceOf(WebDriverException.class);
    verify(driver).quit();
  }

  @Test
  void failsAndQuitsWhenThePageNeverBecomesReady() {
    when(javascript.executeScript("return document.readyState")).thenReturn("loading");

    assertThatThrownBy(() -> loader(time).load(AGENDA))
        .isInstanceOf(SourceFetchException.class)
        .hasMessageContaining("ready");
    verify(driver).quit();
  }

  @Test
  void pacesRequestsThroughTheRateLimiter() throws SourceFetchException {
    SeleniumPageLoader loader = loader(time);

    loader.load(AGENDA);
    loader.load(EVENT);

    assertThat(time.sleeps).containsExactly(Duration.ofSeconds(1));
    verify(driver, times(2)).quit();
  }

  @Test
  void reportsAnInterruptedWaitAsASourceFailure() throws SourceFetchException {
    RateLimiter.Sleeper interrupted =
        duration -> {
          throw new InterruptedException("shutting down");
        };
    SeleniumPageLoader loader =
        new SeleniumPageLoader(properties, new RateLimiter(1, time, interrupted), p -> driver);

    loader.load(AGENDA);

    assertThatThrownBy(() -> loader.load(EVENT))
        .isInstanceOf(SourceFetchException.class)
        .hasMessageContaining("interrupted");
    verify(driver, times(1)).quit();
  }

  @Test
  void appliesHeadlessWindowAndUserAgentFlags() {
    ChromeOptions options = SeleniumPageLoader.chromeOptions(properties);

    assertThat(chromeArguments(options))
        .contains(
            "--headless=new",
            "--window-size=1280,800",
            "--user-agent=fribourg-events-digest/1.0",
            "--disable-dev-shm-usage");
  }

  @Test
  void omitsTheHeadlessFlagWhenRunningHeaded() {
    ChromeOptions options =
        SeleniumPageLoader.chromeOptions(
            new BrowserProperties(
                false, 1280, 800, "agent", Duration.ofSeconds(5), Duration.ofSeconds(5)));

    assertThat(chromeArguments(options)).doesNotContain("--headless=new");
  }

  @SuppressWarnings("unchecked")
  private static List<String> chromeArguments(ChromeOptions options) {
    Map<String, Object> chrome =
        (Map<String, Object>) options.asMap().get(ChromeOptions.CAPABILITY);
    return (List<String>) chrome.get("args");
  }

  private SeleniumPageLoader loader(FakeTime clock) {
    return new SeleniumPageLoader(properties, new RateLimiter(1, clock, clock), p -> driver);
  }

  /** Fake monotonic clock that advances with the limiter's sleeps. */
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
