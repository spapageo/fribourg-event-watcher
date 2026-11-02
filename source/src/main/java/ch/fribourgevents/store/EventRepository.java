package ch.fribourgevents.store;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventRepository extends JpaRepository<EventEntity, Long> {

  Optional<EventEntity> findByDedupKey(String dedupKey);
}
