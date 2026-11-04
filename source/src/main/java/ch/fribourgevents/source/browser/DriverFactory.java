package ch.fribourgevents.source.browser;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;

/**
 * Creates the {@link WebDriver} a {@link SeleniumPageLoader} uses for one page.
 *
 * <p>Tests inject a factory returning a mocked driver, so the loader's quit/timeout logic is
 * covered without launching Chromium. Production uses {@link #chrome()}, which hands driver
 * management to Selenium Manager (it finds the Debian {@code chromium} and {@code chromedriver}
 * installed in the container image).
 */
@FunctionalInterface
public interface DriverFactory {

  WebDriver create(BrowserProperties properties);

  /** Production factory: a new headless Chrome session per call. */
  static DriverFactory chrome() {
    return properties -> new ChromeDriver(SeleniumPageLoader.chromeOptions(properties));
  }
}
