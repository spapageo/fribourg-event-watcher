package ch.fribourgevents.store;

import ch.fribourgevents.domain.SourceId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
    name = "event_source_ref",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_event_source_ref_source_event",
            columnNames = {"source", "source_event_id"}))
public class EventSourceRefEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @JdbcTypeCode(SqlTypes.INTEGER)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "event_id", nullable = false)
  private EventEntity event;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private SourceId source;

  @Column(name = "source_event_id", nullable = false)
  private String sourceEventId;

  @Column(name = "source_url")
  private String sourceUrl;

  @Column(name = "payload_hash", nullable = false)
  private String payloadHash;

  @Column(name = "first_seen_at", nullable = false)
  private Instant firstSeenAt;

  @Column(name = "last_seen_at", nullable = false)
  private Instant lastSeenAt;

  protected EventSourceRefEntity() {}

  EventSourceRefEntity(
      EventEntity event,
      SourceId source,
      String sourceEventId,
      String sourceUrl,
      String payloadHash,
      Instant firstSeenAt,
      Instant lastSeenAt) {
    this.event = event;
    this.source = source;
    this.sourceEventId = sourceEventId;
    this.sourceUrl = sourceUrl;
    this.payloadHash = payloadHash;
    this.firstSeenAt = firstSeenAt;
    this.lastSeenAt = lastSeenAt;
  }

  public Long getId() {
    return id;
  }

  public EventEntity getEvent() {
    return event;
  }

  public SourceId getSource() {
    return source;
  }

  public String getSourceEventId() {
    return sourceEventId;
  }

  public String getSourceUrl() {
    return sourceUrl;
  }

  public String getPayloadHash() {
    return payloadHash;
  }

  public Instant getFirstSeenAt() {
    return firstSeenAt;
  }

  public Instant getLastSeenAt() {
    return lastSeenAt;
  }

  void setEvent(EventEntity event) {
    this.event = event;
  }

  void setSourceUrl(String sourceUrl) {
    this.sourceUrl = sourceUrl;
  }

  void setPayloadHash(String payloadHash) {
    this.payloadHash = payloadHash;
  }

  void setLastSeenAt(Instant lastSeenAt) {
    this.lastSeenAt = lastSeenAt;
  }
}
