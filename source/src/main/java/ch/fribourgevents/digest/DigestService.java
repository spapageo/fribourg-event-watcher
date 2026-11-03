package ch.fribourgevents.digest;

import ch.fribourgevents.domain.FetchWindow;
import ch.fribourgevents.store.EventEntity;
import ch.fribourgevents.store.EventOccurrenceEntity;
import ch.fribourgevents.store.EventStore;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Read side of the digest: turns the occurrences that intersect a window into a {@link
 * DigestMessage}.
 *
 * <p>Grouping and ordering are delegated to {@link DigestMessage#build}; this service only reads
 * through the store and maps rows to items, so no SQL leaves the {@code store} module.
 */
@Service
public class DigestService {

  /** Default cap on rendered items; the rest is summarised in a footer. */
  public static final int DEFAULT_ITEM_CAP = 150;

  private final EventStore store;

  public DigestService(EventStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  public DigestMessage select(FetchWindow window) {
    return select(window, DEFAULT_ITEM_CAP);
  }

  /**
   * Selects the digest for {@code window}, keeping at most {@code itemCap} items.
   *
   * <p>The cap is applied after sorting, so a truncated digest still shows the earliest events.
   */
  public DigestMessage select(FetchWindow window, int itemCap) {
    Objects.requireNonNull(window, "window");
    List<EventOccurrenceEntity> occurrences =
        store.findOccurrencesOverlapping(window.from(), window.to());
    List<DigestMessage.Item> items = occurrences.stream().map(DigestService::toItem).toList();
    return DigestMessage.build(window, items, itemCap);
  }

  private static DigestMessage.Item toItem(EventOccurrenceEntity occurrence) {
    EventEntity event = occurrence.getEvent();
    return new DigestMessage.Item(
        event.getId(),
        occurrence.getId(),
        occurrence.getStartUtc(),
        occurrence.getEndUtc(),
        occurrence.isAllDay(),
        event.getTitle(),
        event.getVenueName(),
        event.getCity(),
        event.getUrl() == null ? null : URI.create(event.getUrl()));
  }
}
