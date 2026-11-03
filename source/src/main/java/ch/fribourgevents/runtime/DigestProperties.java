package ch.fribourgevents.runtime;

import ch.fribourgevents.domain.SourceId;
import ch.fribourgevents.notify.MessageChunker;
import ch.fribourgevents.notify.telegram.TelegramProperties;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.support.CronExpression;

/**
 * The whole operator-facing configuration, bound from {@code digest.*}.
 *
 * <p>Every value is validated in the record's compact constructor, so a bad env var fails startup
 * with a message naming the key instead of surfacing later during a run. Defaults live here (not
 * only in {@code application.yml}) so a missing property and an unparsable one behave predictably.
 *
 * <p>Telegram credentials are required only when {@code digest.dry-run=false}: a dry run renders
 * and logs the digest and must work on a machine without secrets.
 *
 * @param cron Spring cron expression for the scheduled run
 * @param zone IANA zone used for the digest window and day grouping
 * @param windowDays length of the fetch/selection window in local days
 * @param runOnStartup run the pipeline once when the application starts
 * @param dryRun render and log the digest instead of sending it
 * @param sendFailureNotice send a short notice when every source fails
 * @param dbPath SQLite file path; also feeds {@code spring.datasource.url}
 * @param telegram Bot API settings
 * @param sources per-adapter toggles
 * @param rateLimit request budget for scraped sources
 * @param http outbound HTTP timeout
 */
@ConfigurationProperties(prefix = "digest")
public record DigestProperties(
    String cron,
    String zone,
    Integer windowDays,
    boolean runOnStartup,
    boolean dryRun,
    boolean sendFailureNotice,
    String dbPath,
    Telegram telegram,
    Sources sources,
    RateLimit rateLimit,
    Http http) {

  /** Default schedule: Mondays at 07:00 local time. */
  public static final String DEFAULT_CRON = "0 0 7 * * MON";

  public static final String DEFAULT_ZONE = "Europe/Zurich";
  public static final int DEFAULT_WINDOW_DAYS = 14;
  public static final String DEFAULT_DB_PATH = "../data/digest.db";

  public DigestProperties {
    cron = cron == null || cron.isBlank() ? DEFAULT_CRON : cron.strip();
    if (!CronExpression.isValidExpression(cron)) {
      throw new IllegalArgumentException(
          "digest.cron is not a valid cron expression: '" + cron + "'");
    }

    zone = zone == null || zone.isBlank() ? DEFAULT_ZONE : zone.strip();
    try {
      ZoneId.of(zone);
    } catch (DateTimeException e) {
      throw new IllegalArgumentException("digest.zone is not a valid zone id: '" + zone + "'");
    }

    windowDays = windowDays == null ? DEFAULT_WINDOW_DAYS : windowDays;
    if (windowDays <= 0) {
      throw new IllegalArgumentException("digest.window-days must be positive, was " + windowDays);
    }

    dbPath = dbPath == null || dbPath.isBlank() ? DEFAULT_DB_PATH : dbPath.strip();
    telegram = telegram == null ? new Telegram(null, null, null, 0) : telegram;
    if (!dryRun) {
      telegram.requireCredentials();
    }
    sources = sources == null ? Sources.allEnabled() : sources;
    rateLimit = rateLimit == null ? new RateLimit(1, 60) : rateLimit;
    http = http == null ? new Http(Duration.ofSeconds(10)) : http;
  }

  public ZoneId zoneId() {
    return ZoneId.of(zone);
  }

  /** Whether the adapter with this id should run in this deployment. */
  public boolean enabled(SourceId source) {
    return switch (source) {
      case INSITU -> Boolean.TRUE.equals(sources.insitu().enabled());
      case BKA -> Boolean.TRUE.equals(sources.bka().enabled());
      case EVENTFROG -> Boolean.TRUE.equals(sources.eventfrog().enabled());
    };
  }

  /** Telegram Bot API settings. */
  public record Telegram(String botToken, String chatId, String baseUrl, Integer partLimit) {

    public Telegram {
      botToken = botToken == null ? "" : botToken.strip();
      chatId = chatId == null ? "" : chatId.strip();
      baseUrl =
          baseUrl == null || baseUrl.isBlank()
              ? TelegramProperties.DEFAULT_BASE_URL
              : stripTrailingSlash(baseUrl.strip());
      partLimit = partLimit == null || partLimit == 0 ? MessageChunker.DEFAULT_LIMIT : partLimit;
      if (partLimit < MessageChunker.MINIMUM_LIMIT) {
        throw new IllegalArgumentException(
            "digest.telegram.part-limit must be at least "
                + MessageChunker.MINIMUM_LIMIT
                + ", was "
                + partLimit);
      }
    }

    /** Fails with a clear message when the credentials a real delivery needs are missing. */
    void requireCredentials() {
      if (botToken.isEmpty()) {
        throw new IllegalArgumentException(
            "digest.telegram.bot-token (TELEGRAM_BOT_TOKEN) is required unless"
                + " digest.dry-run=true");
      }
      if (chatId.isEmpty()) {
        throw new IllegalArgumentException(
            "digest.telegram.chat-id (TELEGRAM_CHAT_ID) is required unless digest.dry-run=true");
      }
    }

    /** The SPI-facing settings; only call when {@link #requireCredentials()} passed. */
    public TelegramProperties toSettings() {
      return new TelegramProperties(botToken, chatId, baseUrl);
    }
  }

  /** Per-adapter toggles. */
  public record Sources(Source insitu, Source bka, Source eventfrog) {

    public Sources {
      insitu = insitu == null ? new Source(true) : insitu;
      bka = bka == null ? new Source(true) : bka;
      eventfrog = eventfrog == null ? new Source(true) : eventfrog;
    }

    public static Sources allEnabled() {
      return new Sources(new Source(true), new Source(true), new Source(true));
    }

    /** One adapter toggle; enabled unless explicitly disabled. */
    public record Source(Boolean enabled) {

      public Source {
        enabled = enabled == null ? Boolean.TRUE : enabled;
      }
    }
  }

  /** Request budget for scraped sources: the spec allows 1 req/s and 60 requests per run. */
  public record RateLimit(Integer perSecond, Integer maxPerRun) {

    public RateLimit {
      perSecond = perSecond == null ? 1 : perSecond;
      maxPerRun = maxPerRun == null ? 60 : maxPerRun;
      if (perSecond <= 0) {
        throw new IllegalArgumentException(
            "digest.rate-limit.per-second must be positive, was " + perSecond);
      }
      if (maxPerRun <= 0) {
        throw new IllegalArgumentException(
            "digest.rate-limit.max-per-run must be positive, was " + maxPerRun);
      }
    }
  }

  /** Outbound HTTP settings shared by the API clients. */
  public record Http(Duration requestTimeout) {

    public Http {
      requestTimeout = requestTimeout == null ? Duration.ofSeconds(10) : requestTimeout;
      if (requestTimeout.isZero() || requestTimeout.isNegative()) {
        throw new IllegalArgumentException(
            "digest.http.request-timeout must be positive, was " + requestTimeout);
      }
    }
  }

  private static String stripTrailingSlash(String value) {
    String stripped = value;
    while (stripped.endsWith("/")) {
      stripped = stripped.substring(0, stripped.length() - 1);
    }
    return stripped;
  }
}
