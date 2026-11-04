package ch.fribourgevents.store;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventOccurrenceRepository extends JpaRepository<EventOccurrenceEntity, Long> {

  List<EventOccurrenceEntity> findByEvent_Id(Long eventId);

  List<EventOccurrenceEntity> findByEvent_IdIn(Collection<Long> eventIds);

  /**
   * Occurrences intersecting {@code [from, to)}. An occurrence without an end is treated as
   * instantaneous at its start time, so a past all-day marker does not reappear in every window.
   */
  @Query(
      """
      select o from EventOccurrenceEntity o
      join fetch o.event e
      where o.startUtc < :to
        and (o.startUtc >= :from or (o.endUtc is not null and o.endUtc >= :from))
      order by o.startUtc asc, e.title asc
      """)
  List<EventOccurrenceEntity> findOverlapping(@Param("from") Instant from, @Param("to") Instant to);
}
