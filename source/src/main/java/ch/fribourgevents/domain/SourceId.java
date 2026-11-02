package ch.fribourgevents.domain;

import java.util.Locale;
import java.util.Optional;

/** Upstream agendas supported by the digest. */
public enum SourceId {
  INSITU("insitu"),
  BKA("bka"),
  EVENTFROG("eventfrog");

  private final String wireName;

  SourceId(String wireName) {
    this.wireName = wireName;
  }

  public String wireName() {
    return wireName;
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
