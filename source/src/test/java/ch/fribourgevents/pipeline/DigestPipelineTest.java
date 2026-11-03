package ch.fribourgevents.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import ch.fribourgevents.digest.DigestMessage;
import ch.fribourgevents.digest.DigestService;
import ch.fribourgevents.digest.DigestTextRenderer;
import ch.fribourgevents.domain.EventOccurrence;
import ch.fribourgevents.domain.FetchWindow;
import ch.fribourgevents.domain.SourceEvent;
import ch.fribourgevents.domain.SourceId;
import ch.fribourgevents.notify.DeliveryResult.DeliveryStatus;
import ch.fribourgevents.notify.NotificationException;
import ch.fribourgevents.pipeline.PipelineResult.RunStatus;
import ch.fribourgevents.pipeline.SourceSyncResult.Status;
import ch.fribourgevents.source.EventSource;
import ch.fribourgevents.store.EventStore;
import ch.fribourgevents.store.SqliteTestDatabase;
import ch.fribourgevents.store.StoreConfiguration;
import ch.fribourgevents.store.StoreTestApplication;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * End-to-end pipeline tests: fake sources and a fake notifier against a real temporary SQLite
 * database. They assert the in-memory summary, the four structured log lines, and that the only
 * tables that change are canonical event tables — execution history stays in logs.
 */
@DataJpaTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "spring.flyway.enabled=true",
      "spring.datasource.hikari.maximum-pool-size=1",
      "spring.datasource.hikari.minimum-idle=1"
    })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = StoreTestApplication.class)
@Import({StoreConfiguration.class, EventStore.class, DigestService.class})
class DigestPipelineTest {

  private static final ZoneId ZURICH = ZoneId.of("Europe/Zurich");
  private static final FetchWindow WINDOW =
      new FetchWindow(
          Instant.parse("2026-11-03T00:00:00Z"), Instant.parse("2026-11-17T00:00:00Z"), ZURICH);
  private static final Path DB_FILE = SqliteTestDatabase.create("pipeline");

  @DynamicPropertySource
  static void sqlite(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> SqliteTestDatabase.url(DB_FILE));
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add(
        "spring.jpa.database-platform", () -> "org.hibernate.community.dialect.SQLiteDialect");
  }

  @Autowired private EventStore store;
  @Autowired private DigestService digestService;
  @Autowired private JdbcTemplate jdbc;

  private FakeNotificationSender notifier;
  private Logger pipelineLogger;
  private ListAppender<ILoggingEvent> logAppender;

  @BeforeEach
  void attachLogs() {
    notifier = new FakeNotificationSender();
    pipelineLogger = (Logger) LoggerFactory.getLogger(DigestPipeline.class);
    logAppender = new ListAppender<>();
    logAppender.start();
    pipelineLogger.addAppender(logAppender);
  }

  @AfterEach
  void detachLogs() {
    pipelineLogger.detachAppender(logAppender);
    logAppender.stop();
  }

  @AfterAll
  static void deleteDatabase() {
    SqliteTestDatabase.delete(DB_FILE);
  }

  @Test
  void storesSelectsAndDeliversEverySource() {
    FakeEventSource insitu =
        FakeEventSource.returning(
            SourceId.INSITU,
            List.of(
                event(SourceId.INSITU, "i-1", "Cinéma Zéro", "2026-11-03T19:00:00Z"),
                event(SourceId.INSITU, "i-2", "Concert baroque", "2026-11-05T20:00:00Z")),
            2);
    FakeEventSource eventfrog =
        FakeEventSource.returning(
            SourceId.EVENTFROG,
            List.of(event(SourceId.EVENTFROG, "e-1", "Theatre du Nord", "2026-11-06T18:30:00Z")),
            3);

    PipelineResult result =
        pipeline(List.of(insitu, eventfrog), PipelineOptions.defaults()).run(WINDOW);

    assertThat(result.status()).isEqualTo(RunStatus.SUCCESS);
    assertThat(result.window()).isEqualTo(WINDOW);
    assertThat(result.sources())
        .extracting(SourceSyncResult::status)
        .containsExactly(Status.OK, Status.OK);
    assertThat(result.sources()).extracting(SourceSyncResult::pagesFetched).containsExactly(2, 3);
    assertThat(result.sources()).extracting(SourceSyncResult::eventsSeen).containsExactly(2, 1);
    assertThat(result.selectedOccurrenceIds()).hasSize(3);
    assertThat(result.delivery()).isPresent();
    assertThat(result.delivery().orElseThrow().status()).isEqualTo(DeliveryStatus.DELIVERED);
    assertThat(insitu.lastWindow()).contains(WINDOW);

    assertThat(notifier.sent()).hasSize(1);
    assertThat(notifier.sent().getFirst().itemCount()).isEqualTo(3);

    assertThat(rowCount("event")).isEqualTo(3);
    assertThat(rowCount("event_source_ref")).isEqualTo(3);
    assertThat(rowCount("event_occurrence")).isEqualTo(3);
    assertThat(tables())
        .contains("event", "event_source_ref", "event_occurrence")
        .doesNotContain("digest_run", "digest_run_item", "source_sync", "notification_delivery");

    assertThat(logs()).filteredOn(line -> line.startsWith("digest_run_started ")).hasSize(1);
    assertThat(logs()).filteredOn(line -> line.startsWith("source_sync ")).hasSize(2);
    assertThat(logs()).filteredOn(line -> line.startsWith("notification_delivery ")).hasSize(1);
    assertThat(logs()).filteredOn(line -> line.startsWith("digest_run_finished ")).hasSize(1);
  }

  @Test
  void keepsDeliveringWhenOneSourceFailsAndNamesItInTheFooter() {
    FakeEventSource failing =
        FakeEventSource.failing(SourceId.INSITU, "insitu: request failed for agenda page 1");
    FakeEventSource eventfrog =
        FakeEventSource.returning(
            SourceId.EVENTFROG,
            List.of(event(SourceId.EVENTFROG, "e-1", "Theatre du Nord", "2026-11-06T18:30:00Z")),
            1);

    PipelineResult result =
        pipeline(List.of(failing, eventfrog), PipelineOptions.defaults()).run(WINDOW);

    assertThat(result.status()).isEqualTo(RunStatus.PARTIAL);
    assertThat(result.failedSources()).containsExactly(SourceId.INSITU);
    assertThat(result.sources().get(0).status()).isEqualTo(Status.FAILED);
    assertThat(result.sources().get(0).error()).contains("request failed");
    assertThat(result.delivery()).isPresent();
    assertThat(result.delivery().orElseThrow().status()).isEqualTo(DeliveryStatus.DELIVERED);

    assertThat(notifier.sent()).hasSize(1);
    DigestMessage sent = notifier.sent().getFirst();
    assertThat(sent.itemCount()).isEqualTo(1);
    assertThat(sent.notes())
        .containsExactly("Source unavailable this run: insitu. Events may be missing.");
    assertThat(new DigestTextRenderer().render(sent))
        .contains("Theatre du Nord", "Source unavailable this run: insitu");

    assertThat(logs())
        .filteredOn(line -> line.startsWith("digest_run_finished "))
        .singleElement()
        .satisfies(line ->
            assertThat(line).contains("status=PARTIAL", "events_selected=1", "delivered=true"));
  }

  @Test
  void failsWhenNoSourceCanBeReadAndSendsNoEmptyDigest() {
    FakeEventSource insitu = FakeEventSource.failing(SourceId.INSITU, "insitu: boom");
    FakeEventSource eventfrog = FakeEventSource.failing(SourceId.EVENTFROG, "eventfrog: boom");

    PipelineResult result =
        pipeline(List.of(insitu, eventfrog), PipelineOptions.defaults()).run(WINDOW);

    assertThat(result.status()).isEqualTo(RunStatus.FAILED);
    assertThat(result.delivery()).isEmpty();
    assertThat(result.selectedOccurrenceIds()).isEmpty();
    assertThat(notifier.sent()).isEmpty();
    assertThat(rowCount("event")).isZero();
    assertThat(logs())
        .filteredOn(line -> line.startsWith("notification_delivery "))
        .singleElement()
        .satisfies(line -> assertThat(line).contains("status=SKIPPED", "failure notice disabled"));
  }

  @Test
  void sendsAFailureNoticeOnlyWhenConfigured() {
    FakeEventSource insitu = FakeEventSource.failing(SourceId.INSITU, "insitu: boom");

    PipelineResult result = pipeline(List.of(insitu), new PipelineOptions(false, true)).run(WINDOW);

    assertThat(result.status()).isEqualTo(RunStatus.FAILED);
    assertThat(notifier.sent()).hasSize(1);
    DigestMessage notice = notifier.sent().getFirst();
    assertThat(notice.itemCount()).isZero();
    assertThat(notice.notes()).singleElement().asString().contains("Digest failed", "insitu");
    assertThat(new DigestTextRenderer().render(notice)).doesNotContain("No events found");
  }

  @Test
  void dryRunRendersAndLogsWithoutSending() {
    FakeEventSource insitu =
        FakeEventSource.returning(
            SourceId.INSITU,
            List.of(event(SourceId.INSITU, "i-1", "Cinéma Zéro", "2026-11-03T19:00:00Z")),
            1);

    PipelineResult result = pipeline(List.of(insitu), new PipelineOptions(true, false)).run(WINDOW);

    assertThat(result.status()).isEqualTo(RunStatus.SUCCESS);
    assertThat(result.delivery()).isEmpty();
    assertThat(notifier.sent()).isEmpty();
    assertThat(rowCount("event")).isEqualTo(1);
    assertThat(logs())
        .anySatisfy(line -> assertThat(line).contains("digest_run_dry_run", "Cinéma Zéro"));
    assertThat(logs())
        .filteredOn(line -> line.startsWith("notification_delivery "))
        .singleElement()
        .satisfies(line -> assertThat(line).contains("channel=dry-run", "status=SKIPPED"));
  }

  @Test
  void marksTheRunFailedWhenTheDeliveryFails() {
    notifier.failWith(new NotificationException("telegram 400: Bad Request"));
    FakeEventSource insitu =
        FakeEventSource.returning(
            SourceId.INSITU,
            List.of(event(SourceId.INSITU, "i-1", "Cinéma Zéro", "2026-11-03T19:00:00Z")),
            1);

    PipelineResult result = pipeline(List.of(insitu), PipelineOptions.defaults()).run(WINDOW);

    assertThat(result.status()).isEqualTo(RunStatus.FAILED);
    assertThat(result.delivery()).isEmpty();
    assertThat(rowCount("event")).isEqualTo(1);
    assertThat(logs())
        .filteredOn(line -> line.startsWith("notification_delivery "))
        .singleElement()
        .satisfies(line -> assertThat(line).contains("status=FAILED", "telegram 400: Bad Request"));
  }

  private DigestPipeline pipeline(List<EventSource> sources, PipelineOptions options) {
    return new DigestPipeline(
        sources,
        store,
        digestService,
        notifier,
        new DigestTextRenderer(),
        Clock.systemUTC(),
        options);
  }

  private int rowCount(String table) {
    Integer count = jdbc.queryForObject("select count(*) from " + table, Integer.class);
    return count == null ? 0 : count;
  }

  private List<String> tables() {
    return jdbc.queryForList("select name from sqlite_master where type = 'table'", String.class);
  }

  private List<String> logs() {
    return logAppender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
  }

  private static SourceEvent event(SourceId source, String id, String title, String start) {
    return new SourceEvent(
        source,
        id,
        title,
        null,
        "Venue " + id,
        null,
        "Fribourg",
        null,
        null,
        null,
        null,
        URI.create("https://example.test/" + id),
        Set.of("culture"),
        null,
        null,
        List.of(new EventOccurrence(Instant.parse(start), null, false)));
  }
}
