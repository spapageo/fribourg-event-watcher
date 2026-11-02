package ch.fribourgevents.store;

import ch.fribourgevents.domain.SourceId;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventSourceRefRepository extends JpaRepository<EventSourceRefEntity, Long> {

  Optional<EventSourceRefEntity> findBySourceAndSourceEventId(
      SourceId source, String sourceEventId);

  long countByEvent_Id(Long eventId);
}
