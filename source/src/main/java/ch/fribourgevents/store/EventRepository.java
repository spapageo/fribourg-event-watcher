package ch.fribourgevents.store;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventRepository extends JpaRepository<EventEntity, Long> {

  Optional<EventEntity> findByDedupKey(String dedupKey);

  /**
   * Canonical events with at least one occurrence whose digest-local date falls in {@code [from,
   * to]}. Used to build the bounded candidate set for cross-source dedup.
   */
  @Query(
      """
      select distinct o.event from EventOccurrenceEntity o
      where o.localDate between :from and :to
      """)
  List<EventEntity> findWithOccurrenceLocalDateBetween(
      @Param("from") LocalDate from, @Param("to") LocalDate to);
}
