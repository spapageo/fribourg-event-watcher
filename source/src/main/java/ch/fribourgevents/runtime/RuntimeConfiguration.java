package ch.fribourgevents.runtime;

import ch.fribourgevents.digest.DigestService;
import ch.fribourgevents.digest.DigestTextRenderer;
import ch.fribourgevents.notify.NotificationException;
import ch.fribourgevents.notify.NotificationSender;
import ch.fribourgevents.notify.telegram.TelegramClient;
import ch.fribourgevents.notify.telegram.TelegramNotificationSender;
import ch.fribourgevents.pipeline.DigestPipeline;
import ch.fribourgevents.pipeline.PipelineOptions;
import ch.fribourgevents.source.EventSource;
import ch.fribourgevents.source.insitu.InsituClient;
import ch.fribourgevents.source.insitu.InsituEventSource;
import ch.fribourgevents.store.EventStore;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Wires the modules into one runnable service: HTTP client, notification sender, enabled sources,
 * and the pipeline.
 *
 * <p>Sources are injected as a list, so adding the bka or eventfrog adapter later only means adding
 * its bean; {@link DigestProperties#enabled} decides which ones actually run. In a dry run the
 * sender is a stub that refuses to send, so the application can boot and render without Telegram
 * credentials.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DigestProperties.class)
public class RuntimeConfiguration {

  /** Public insitu.live origin; the API client appends {@code /api/events}. */
  static final String INSITU_BASE_URL = "https://insitu.live";

  @Bean
  RestClient restClient(DigestProperties properties) {
    HttpClient http =
        HttpClient.newBuilder()
            .connectTimeout(properties.http().requestTimeout())
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(http);
    requestFactory.setReadTimeout(properties.http().requestTimeout());
    return RestClient.builder().requestFactory(requestFactory).build();
  }

  @Bean
  NotificationSender notificationSender(DigestProperties properties, RestClient restClient) {
    if (properties.dryRun()) {
      return message -> {
        throw new NotificationException("sending is disabled while digest.dry-run=true");
      };
    }
    DigestProperties.Telegram telegram = properties.telegram();
    TelegramClient client = new TelegramClient(telegram.toSettings(), restClient);
    return new TelegramNotificationSender(client, telegram.partLimit());
  }

  @Bean
  EventSource insituEventSource(RestClient restClient) {
    return new InsituEventSource(new InsituClient(INSITU_BASE_URL, restClient));
  }

  @Bean
  DigestPipeline digestPipeline(
      List<EventSource> sources,
      DigestProperties properties,
      EventStore store,
      DigestService digestService,
      NotificationSender sender,
      Clock clock) {
    List<EventSource> enabled =
        sources.stream().filter(source -> properties.enabled(source.id())).toList();
    return new DigestPipeline(
        enabled,
        store,
        digestService,
        sender,
        new DigestTextRenderer(),
        clock,
        new PipelineOptions(properties.dryRun(), properties.sendFailureNotice()));
  }
}
