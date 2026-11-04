package ch.fribourgevents.store;

/**
 * Outcome of one {@link EventStore#upsert} call.
 *
 * @param event the canonical event the source record now points at
 * @param eventCreated a new canonical event was inserted (never a merge)
 * @param sourceRefCreated a new {@code (source, source_event_id)} ref was inserted
 * @param payloadChanged the stored event/ref/occurrences were rewritten for a changed payload
 */
public record UpsertResult(
    EventEntity event, boolean eventCreated, boolean sourceRefCreated, boolean payloadChanged) {

  /**
   * True when this call attached a new source record to an event that already existed — the
   * cross-source merge case the run report counts.
   */
  public boolean merged() {
    return sourceRefCreated && !eventCreated;
  }
}
