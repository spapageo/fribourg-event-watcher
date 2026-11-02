package ch.fribourgevents.store;

import ch.fribourgevents.domain.DedupKey;
import ch.fribourgevents.domain.EventOccurrence;
import ch.fribourgevents.domain.Normalizer;
import ch.fribourgevents.domain.SourceEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Idempotent write side of the canonical event model. */
@Service
public class EventStore {

  private final EventRepository events;
  private final EventSourceRefRepository sourceRefs;
  private final EventOccurrenceRepository occurrences;
  private final ZoneId zone;
  private final Clock clock;

  public EventStore(
      EventRepository events,
      EventSourceRefRepository sourceRefs,
      EventOccurrenceRepository occurrences,
      ZoneId zone,
      Clock clock) {
    this.events = events;
    this.sourceRefs = sourceRefs;
    this.occurrences = occurrences;
    this.zone = zone;
    this.clock = clock;
  }

  public UpsertResult upsert(SourceEvent sourceEvent) {
    return upsert(sourceEvent, clock.instant());
  }

  @Transactional
  public UpsertResult upsert(SourceEvent sourceEvent, Instant seenAt) {
    Objects.requireNonNull(sourceEvent, "sourceEvent");
    Objects.requireNonNull(seenAt, "seenAt");

    String payloadHash = PayloadHash.of(sourceEvent);
    Optional<EventSourceRefEntity> existingRef =
        sourceRefs.findBySourceAndSourceEventId(sourceEvent.source(), sourceEvent.sourceEventId());

    if (existingRef.isPresent()) {
      return updateExistingRef(existingRef.get(), sourceEvent, payloadHash, seenAt);
    }
    return createNewRef(sourceEvent, payloadHash, seenAt);
  }

  @Transactional(readOnly = true)
  public List<EventOccurrenceEntity> findOccurrencesOverlapping(Instant from, Instant to) {
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    if (!from.isBefore(to)) {
      throw new IllegalArgumentException("from must be before to");
    }
    return occurrences.findOverlapping(from, to);
  }

  @Transactional(readOnly = true)
  public Optional<EventEntity> findEvent(Long id) {
    return events.findById(id);
  }

  private UpsertResult updateExistingRef(
      EventSourceRefEntity ref, SourceEvent input, String payloadHash, Instant seenAt) {
    EventEntity event = ref.getEvent();
    boolean changed = !payloadHash.equals(ref.getPayloadHash());
    if (changed) {
      applyEvent(event, input, seenAt);
      events.save(event);
      boolean soleOwner = sourceRefs.countByEvent_Id(event.getId()) == 1;
      syncOccurrences(event, input, soleOwner);
      ref.setSourceUrl(urlOf(input));
      ref.setPayloadHash(payloadHash);
    }
    ref.setLastSeenAt(seenAt);
    sourceRefs.save(ref);
    return new UpsertResult(event, false, false, changed);
  }

  private UpsertResult createNewRef(SourceEvent input, String payloadHash, Instant seenAt) {
    String dedupKey = DedupKey.of(input, zone);
    Optional<EventEntity> canonical = events.findByDedupKey(dedupKey);
    boolean eventCreated = canonical.isEmpty();
    EventEntity event =
        canonical.orElseGet(() -> {
          EventEntity created = new EventEntity();
          created.setDedupKey(dedupKey);
          applyEvent(created, input, seenAt);
          created.setCreatedAt(seenAt);
          return events.save(created);
        });

    if (!eventCreated) {
      applyEvent(event, input, seenAt);
      events.save(event);
    }

    // A new source ref never removes occurrences: another source may own them.
    syncOccurrences(event, input, eventCreated);
    sourceRefs.save(
        new EventSourceRefEntity(
            event,
            input.source(),
            input.sourceEventId(),
            urlOf(input),
            payloadHash,
            seenAt,
            seenAt));
    return new UpsertResult(event, eventCreated, true, true);
  }

  private void applyEvent(EventEntity event, SourceEvent input, Instant seenAt) {
    event.setTitle(input.title());
    event.setNormalizedTitle(Normalizer.title(input.title()));
    event.setDescription(input.description());
    event.setVenueName(input.venueName());
    event.setAddressLine(input.addressLine());
    event.setCity(input.city());
    event.setCanton(input.canton());
    event.setCountry(input.country());
    event.setLat(input.latitude() == null ? null : input.latitude().doubleValue());
    event.setLon(input.longitude() == null ? null : input.longitude().doubleValue());
    event.setUrl(urlOf(input));
    event.setCategories(encodeCategories(input.categories()));
    event.setPriceInfo(input.priceInfo());
    event.setStatus(input.status());
    event.setUpdatedAt(seenAt);
  }

  private void syncOccurrences(EventEntity event, SourceEvent input, boolean removeMissing) {
    Map<Instant, EventOccurrenceEntity> existing = new LinkedHashMap<>();
    for (EventOccurrenceEntity occurrence : occurrences.findByEvent_Id(event.getId())) {
      existing.put(occurrence.getStartUtc(), occurrence);
    }

    Map<Instant, EventOccurrence> desired = new LinkedHashMap<>();
    for (EventOccurrence occurrence : input.occurrences()) {
      desired.put(occurrence.start(), occurrence);
    }

    for (EventOccurrence occurrence : desired.values()) {
      EventOccurrenceEntity entity = existing.remove(occurrence.start());
      if (entity == null) {
        occurrences.save(
            new EventOccurrenceEntity(
                event,
                occurrence.start(),
                occurrence.end(),
                occurrence.allDay(),
                occurrence.localDate(zone)));
      } else {
        entity.setEndUtc(occurrence.end());
        entity.setAllDay(occurrence.allDay());
        entity.setLocalDate(occurrence.localDate(zone));
        occurrences.save(entity);
      }
    }

    if (removeMissing && !existing.isEmpty()) {
      occurrences.deleteAll(existing.values());
    }
  }

  private static String urlOf(SourceEvent input) {
    return input.url() == null ? null : input.url().toString();
  }

  static String encodeCategories(Set<String> categories) {
    if (categories == null || categories.isEmpty()) {
      return null;
    }
    return categories.stream()
        .map(category -> category.replace('\n', ' ').replace('\r', ' ').strip())
        .filter(category -> !category.isEmpty())
        .sorted()
        .collect(Collectors.joining("\n"));
  }
}
