package ch.fribourgevents.store;

import ch.fribourgevents.domain.SourceId;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventSourceRefRepository extends JpaRepository<EventSourceRefEntity, Long> {

  Optional<EventSourceRefEntity> findBySourceAndSourceEventId(
      SourceId source, String sourceEventId);

  List<EventSourceRefEntity> findByEvent_Id(Long eventId);

  long countByEvent_Id(Long eventId);
}
