package ch.fribourgevents.store;

import ch.fribourgevents.domain.DedupKey;
import ch.fribourgevents.domain.EventOccurrence;
import ch.fribourgevents.domain.EventStatus;
import ch.fribourgevents.domain.Normalizer;
import ch.fribourgevents.domain.SourceEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
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
    return upsert(sourceEvent, clock.instant(), null);
  }

  @Transactional
  public UpsertResult upsert(SourceEvent sourceEvent, Instant seenAt) {
    return upsert(sourceEvent, seenAt, null);
  }

  /**
   * Stores {@code sourceEvent}, attaching it to {@code canonicalEventId} when the dedup resolver
   * already matched it to a stored event.
   *
   * @param canonicalEventId the matched canonical event, or {@code null} to fall back to the exact
   *     dedup-key lookup inside this call
   */
  @Transactional
  public UpsertResult upsert(SourceEvent sourceEvent, Instant seenAt, Long canonicalEventId) {
    Objects.requireNonNull(sourceEvent, "sourceEvent");
    Objects.requireNonNull(seenAt, "seenAt");

    String payloadHash = PayloadHash.of(sourceEvent);
    Optional<EventSourceRefEntity> existingRef =
        sourceRefs.findBySourceAndSourceEventId(sourceEvent.source(), sourceEvent.sourceEventId());

    if (existingRef.isPresent()) {
      return updateExistingRef(existingRef.get(), sourceEvent, payloadHash, seenAt);
    }
    return createNewRef(sourceEvent, payloadHash, seenAt, canonicalEventId);
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

  /**
   * Canonical events that could be the same event as {@code input}: any event with an occurrence
   * whose local date is within {@code dayWindowDays} of the input's first occurrence.
   *
   * <p>This is only the blocking set. City and title/venue similarity are decided by the {@code
   * dedup} module, which consumes these candidates through {@code DedupResolver}.
   */
  @Transactional(readOnly = true)
  public List<DedupCandidate> findDedupCandidates(SourceEvent input, int dayWindowDays) {
    Objects.requireNonNull(input, "input");
    if (dayWindowDays < 0) {
      throw new IllegalArgumentException("dayWindowDays must not be negative");
    }
    LocalDate firstDate =
        input.occurrences().stream()
            .min(Comparator.comparing(EventOccurrence::start))
            .orElseThrow(() -> new IllegalArgumentException("event has no occurrences"))
            .localDate(zone);

    List<EventEntity> candidates =
        events.findWithOccurrenceLocalDateBetween(
            firstDate.minusDays(dayWindowDays), firstDate.plusDays(dayWindowDays));
    if (candidates.isEmpty()) {
      return List.of();
    }

    List<Long> ids = candidates.stream().map(EventEntity::getId).toList();
    Map<Long, List<EventOccurrence>> occurrencesByEvent =
        occurrences.findByEvent_IdIn(ids).stream()
            .collect(
                Collectors.groupingBy(
                    occurrence -> occurrence.getEvent().getId(),
                    Collectors.mapping(EventStore::toOccurrence, Collectors.toList())));

    return candidates.stream()
        .filter(candidate -> occurrencesByEvent.containsKey(candidate.getId()))
        .map(candidate ->
            new DedupCandidate(
                candidate.getId(),
                candidate.getDedupKey(),
                candidate.getTitle(),
                candidate.getVenueName(),
                candidate.getCity(),
                occurrencesByEvent.get(candidate.getId())))
        .toList();
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
    if (changed) {
      refreshPreferredUrl(event);
    }
    return new UpsertResult(event, false, false, changed);
  }

  private UpsertResult createNewRef(
      SourceEvent input, String payloadHash, Instant seenAt, Long canonicalEventId) {
    EventEntity target = resolveTarget(input, canonicalEventId);
    boolean eventCreated = target == null;
    EventEntity event;
    if (eventCreated) {
      event = new EventEntity();
      event.setDedupKey(DedupKey.of(input, zone));
      applyEvent(event, input, seenAt);
      event.setCreatedAt(seenAt);
      event = events.save(event);
    } else {
      event = target;
      mergeEvent(event, input, seenAt);
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
    refreshPreferredUrl(event);
    return new UpsertResult(event, eventCreated, true, true);
  }

  /** Prefer the resolver's match; fall back to the exact dedup key, then to a new event. */
  private EventEntity resolveTarget(SourceEvent input, Long canonicalEventId) {
    if (canonicalEventId != null) {
      EventEntity matched = events.findById(canonicalEventId).orElse(null);
      if (matched != null) {
        return matched;
      }
    }
    return events.findByDedupKey(DedupKey.of(input, zone)).orElse(null);
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
    event.setCategories(encodeCategories(input.categories()));
    event.setPriceInfo(input.priceInfo());
    event.setStatus(input.status());
    event.setUpdatedAt(seenAt);
  }

  /**
   * Merges an incoming source event into an existing canonical event without weakening it: missing
   * fields are filled, descriptions and prices keep the longer text, categories are united, and a
   * cancellation or confirmation survives an {@code UNKNOWN}/tentative contributor.
   *
   * <p>The canonical title and dedup key are never rewritten: they are the event's identity.
   */
  private void mergeEvent(EventEntity event, SourceEvent input, Instant seenAt) {
    event.setDescription(richerText(event.getDescription(), input.description()));
    event.setVenueName(fillMissing(event.getVenueName(), input.venueName()));
    event.setAddressLine(fillMissing(event.getAddressLine(), input.addressLine()));
    event.setCity(fillMissing(event.getCity(), input.city()));
    event.setCanton(fillMissing(event.getCanton(), input.canton()));
    event.setCountry(fillMissing(event.getCountry(), input.country()));
    if (event.getLat() == null && input.latitude() != null) {
      event.setLat(input.latitude().doubleValue());
    }
    if (event.getLon() == null && input.longitude() != null) {
      event.setLon(input.longitude().doubleValue());
    }
    event.setCategories(unionCategories(event.getCategories(), input.categories()));
    event.setPriceInfo(richerText(event.getPriceInfo(), input.priceInfo()));
    event.setStatus(richerStatus(event.getStatus(), input.status()));
    event.setUpdatedAt(seenAt);
    events.save(event);
  }

  /**
   * Points {@code event.url} at the highest-priority source that has a URL for this event, so a
   * merged digest item links to the primary source regardless of ingestion order. Tie-breaks by
   * source event id to stay deterministic when one source contributes several records.
   */
  private void refreshPreferredUrl(EventEntity event) {
    String preferred =
        sourceRefs.findByEvent_Id(event.getId()).stream()
            .filter(ref -> ref.getSourceUrl() != null && !ref.getSourceUrl().isBlank())
            .min(
                Comparator.comparingInt((EventSourceRefEntity ref) -> ref.getSource().priority())
                    .thenComparing(EventSourceRefEntity::getSourceEventId))
            .map(EventSourceRefEntity::getSourceUrl)
            .orElse(null);
    if (!Objects.equals(preferred, event.getUrl())) {
      event.setUrl(preferred);
      events.save(event);
    }
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

  private static EventOccurrence toOccurrence(EventOccurrenceEntity entity) {
    return new EventOccurrence(entity.getStartUtc(), entity.getEndUtc(), entity.isAllDay());
  }

  /** Keeps the longer non-blank text; returns {@code null} only when both are blank. */
  private static String richerText(String existing, String incoming) {
    String left = blankToNull(existing);
    String right = blankToNull(incoming);
    if (left == null) {
      return right;
    }
    if (right == null) {
      return left;
    }
    return right.length() > left.length() ? right : left;
  }

  private static String fillMissing(String existing, String incoming) {
    return blankToNull(existing) == null ? blankToNull(incoming) : existing;
  }

  /**
   * Cancellation outranks confirmation, which outranks a tentative entry; {@code UNKNOWN} never
   * replaces a specific status.
   */
  private static EventStatus richerStatus(EventStatus existing, EventStatus incoming) {
    if (existing == EventStatus.CANCELLED || incoming == EventStatus.CANCELLED) {
      return EventStatus.CANCELLED;
    }
    if (existing == EventStatus.CONFIRMED || incoming == EventStatus.CONFIRMED) {
      return EventStatus.CONFIRMED;
    }
    if (existing == EventStatus.TENTATIVE || incoming == EventStatus.TENTATIVE) {
      return EventStatus.TENTATIVE;
    }
    return EventStatus.UNKNOWN;
  }

  private static String unionCategories(String existing, Set<String> incoming) {
    Set<String> merged = new TreeSet<>();
    if (existing != null) {
      for (String category : existing.split("\n")) {
        if (!category.isBlank()) {
          merged.add(category.strip());
        }
      }
    }
    if (incoming != null) {
      incoming.stream()
          .filter(Objects::nonNull)
          .map(category -> category.replace('\n', ' ').replace('\r', ' ').strip())
          .filter(category -> !category.isEmpty())
          .forEach(merged::add);
    }
    return encodeCategories(merged);
  }

  private static String blankToNull(String value) {
    if (value == null) {
      return null;
    }
    String stripped = value.strip();
    return stripped.isEmpty() ? null : stripped;
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
