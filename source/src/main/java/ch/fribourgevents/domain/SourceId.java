package ch.fribourgevents.domain;

import java.util.Locale;
import java.util.Optional;

/**
 * Upstream agendas supported by the digest.
 *
 * <p>{@link #priority()} orders the sources when several describe the same canonical event: the
 * lowest priority wins the event's {@code url}, so a merged digest item links to the richest source
 * rather than whichever adapter happened to run last.
 */
public enum SourceId {
  INSITU("insitu", 0),
  BKA("bka", 1),
  EVENTFROG("eventfrog", 2);

  private final String wireName;
  private final int priority;

  SourceId(String wireName, int priority) {
    this.wireName = wireName;
    this.priority = priority;
  }

  public String wireName() {
    return wireName;
  }

  /** Lower value wins when a canonical event is described by more than one source. */
  public int priority() {
    return priority;
  }

  public static Optional<SourceId> fromWireName(String value) {
    if (value == null) {
      return Optional.empty();
    }
    String normalized = value.strip().toLowerCase(Locale.ROOT);
    for (SourceId id : values()) {
      if (id.wireName.equals(normalized)) {
        return Optional.of(id);
      }
    }
    return Optional.empty();
  }
}
