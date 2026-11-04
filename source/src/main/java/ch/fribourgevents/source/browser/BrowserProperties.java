package ch.fribourgevents.source.browser;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Headless browser settings for the HTML sources, bound from {@code digest.browser.*}.
 *
 * <p>Defaults match the spec: headless Chromium, a desktop viewport, the descriptive user agent
 * {@code fribourg-events-digest/1.0}, and a 30 second budget for the page load and for the {@code
 * document.readyState == complete} wait. Every value is validated here, so a bad env var fails
 * startup with a message naming the key.
 *
 * @param headless run Chromium without a window; only a headed run needs a display
 * @param width browser window width in pixels
 * @param height browser window height in pixels
 * @param userAgent {@code User-Agent} header; no personal contact details, per the spec
 * @param pageLoadTimeout how long a navigation may take before it is a source failure
 * @param readyStateTimeout how long the page may stay non-{@code complete} after navigation
 */
@ConfigurationProperties(prefix = "digest.browser")
public record BrowserProperties(
    Boolean headless,
    Integer width,
    Integer height,
    String userAgent,
    Duration pageLoadTimeout,
    Duration readyStateTimeout) {

  /** The globally agreed user agent; the container appends nothing. */
  public static final String DEFAULT_USER_AGENT = "fribourg-events-digest/1.0";

  public static final int DEFAULT_WIDTH = 1920;
  public static final int DEFAULT_HEIGHT = 1080;
  public static final Duration DEFAULT_PAGE_LOAD_TIMEOUT = Duration.ofSeconds(30);
  public static final Duration DEFAULT_READY_STATE_TIMEOUT = Duration.ofSeconds(30);

  public BrowserProperties {
    headless = headless == null ? Boolean.TRUE : headless;
    width = width == null ? DEFAULT_WIDTH : width;
    height = height == null ? DEFAULT_HEIGHT : height;
    userAgent = userAgent == null || userAgent.isBlank() ? DEFAULT_USER_AGENT : userAgent.strip();
    pageLoadTimeout = pageLoadTimeout == null ? DEFAULT_PAGE_LOAD_TIMEOUT : pageLoadTimeout;
    readyStateTimeout = readyStateTimeout == null ? DEFAULT_READY_STATE_TIMEOUT : readyStateTimeout;

    if (width <= 0) {
      throw new IllegalArgumentException("digest.browser.width must be positive, was " + width);
    }
    if (height <= 0) {
      throw new IllegalArgumentException("digest.browser.height must be positive, was " + height);
    }
    if (pageLoadTimeout.isZero() || pageLoadTimeout.isNegative()) {
      throw new IllegalArgumentException(
          "digest.browser.page-load-timeout must be positive, was " + pageLoadTimeout);
    }
    if (readyStateTimeout.isZero() || readyStateTimeout.isNegative()) {
      throw new IllegalArgumentException(
          "digest.browser.ready-state-timeout must be positive, was " + readyStateTimeout);
    }
  }
}
