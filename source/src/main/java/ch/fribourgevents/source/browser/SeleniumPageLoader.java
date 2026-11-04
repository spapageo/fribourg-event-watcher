package ch.fribourgevents.source.browser;

import ch.fribourgevents.source.PageLoader;
import ch.fribourgevents.source.SourceFetchException;
import java.net.URI;
import java.util.Objects;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link PageLoader} backed by headless Chromium. One browser session per page, always quit in a
 * {@code finally} block, and every request paced through the shared {@link RateLimiter}.
 *
 * <p>A page counts as loaded only when navigation returned <em>and</em> {@code document.readyState
 * == "complete"}; several agenda sites render their list after the initial HTML, so returning
 * {@code driver.getPageSource()} without that wait would hand parsers a skeleton page.
 *
 * <p>Failures are reported as {@link SourceFetchException} — the adapter SPI's only checked failure
 * — with the host and path but never the full URI, so logs stay free of query strings.
 */
public final class SeleniumPageLoader implements PageLoader {

  private static final Logger log = LoggerFactory.getLogger(SeleniumPageLoader.class);
  private static final String READY_STATE_SCRIPT = "return document.readyState";

  private final BrowserProperties properties;
  private final RateLimiter rateLimiter;
  private final DriverFactory driverFactory;

  /** Production loader: real Chrome sessions and the injected per-host pacing. */
  public SeleniumPageLoader(BrowserProperties properties, RateLimiter rateLimiter) {
    this(properties, rateLimiter, DriverFactory.chrome());
  }

  SeleniumPageLoader(
      BrowserProperties properties, RateLimiter rateLimiter, DriverFactory driverFactory) {
    this.properties = Objects.requireNonNull(properties, "properties");
    this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter");
    this.driverFactory = Objects.requireNonNull(driverFactory, "driverFactory");
  }

  @Override
  public String load(URI uri) throws SourceFetchException {
    Objects.requireNonNull(uri, "uri");
    awaitTurn(uri);

    WebDriver driver = null;
    try {
      driver = driverFactory.create(properties);
      configure(driver);
      driver.get(uri.toString());
      awaitReadyState(driver, uri);
      String source = driver.getPageSource();
      if (source == null || source.isBlank()) {
        throw new SourceFetchException("browser: empty page source for " + target(uri));
      }
      return source;
    } catch (WebDriverException e) {
      throw new SourceFetchException("browser: could not load " + target(uri), e);
    } finally {
      quit(driver);
    }
  }

  private void awaitTurn(URI uri) throws SourceFetchException {
    try {
      rateLimiter.acquire(uri);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SourceFetchException("browser: interrupted while pacing " + target(uri), e);
    }
  }

  private void configure(WebDriver driver) {
    driver.manage().timeouts().pageLoadTimeout(properties.pageLoadTimeout());
    driver.manage().window().setSize(new Dimension(properties.width(), properties.height()));
  }

  private void awaitReadyState(WebDriver driver, URI uri) throws SourceFetchException {
    WebDriverWait wait = new WebDriverWait(driver, properties.readyStateTimeout());
    try {
      wait.until(session ->
          "complete".equals(((JavascriptExecutor) session).executeScript(READY_STATE_SCRIPT)));
    } catch (TimeoutException e) {
      throw new SourceFetchException(
          "browser: page did not reach document.readyState=complete for " + target(uri), e);
    }
  }

  private static void quit(WebDriver driver) {
    if (driver == null) {
      return;
    }
    try {
      driver.quit();
    } catch (RuntimeException e) {
      // A failing quit must never mask the real load result or failure.
      log.warn("browser: failed to quit the driver cleanly: {}", e.getMessage());
    }
  }

  /** Host and path only; query strings and fragments never reach logs or exceptions. */
  private static String target(URI uri) {
    String path = uri.getPath();
    return uri.getHost() + (path == null || path.isEmpty() ? "" : path);
  }

  static ChromeOptions chromeOptions(BrowserProperties properties) {
    ChromeOptions options = new ChromeOptions();
    if (properties.headless()) {
      options.addArguments("--headless=new");
    }
    // The container runs Chromium as a non-root user without user namespaces, so the sandbox
    // cannot start; --disable-dev-shm-usage keeps a small /dev/shm from crashing the renderer.
    options.addArguments(
        "--no-sandbox",
        "--disable-dev-shm-usage",
        "--disable-gpu",
        "--window-size=" + properties.width() + "," + properties.height(),
        "--user-agent=" + properties.userAgent());
    return options;
  }
}
