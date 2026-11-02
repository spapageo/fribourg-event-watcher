package ch.fribourgevents.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
    name = "event_occurrence",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_event_occurrence_event_start",
            columnNames = {"event_id", "start_utc"}))
public class EventOccurrenceEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @JdbcTypeCode(SqlTypes.INTEGER)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "event_id", nullable = false)
  private EventEntity event;

  @Column(name = "start_utc", nullable = false)
  private Instant startUtc;

  @Column(name = "end_utc")
  private Instant endUtc;

  @Column(name = "all_day", nullable = false)
  private boolean allDay;

  @Column(name = "local_date", nullable = false)
  private LocalDate localDate;

  protected EventOccurrenceEntity() {}

  EventOccurrenceEntity(
      EventEntity event, Instant startUtc, Instant endUtc, boolean allDay, LocalDate localDate) {
    this.event = event;
    this.startUtc = startUtc;
    this.endUtc = endUtc;
    this.allDay = allDay;
    this.localDate = localDate;
  }

  public Long getId() {
    return id;
  }

  public EventEntity getEvent() {
    return event;
  }

  public Instant getStartUtc() {
    return startUtc;
  }

  public Instant getEndUtc() {
    return endUtc;
  }

  public boolean isAllDay() {
    return allDay;
  }

  public LocalDate getLocalDate() {
    return localDate;
  }

  void setEvent(EventEntity event) {
    this.event = event;
  }

  void setStartUtc(Instant startUtc) {
    this.startUtc = startUtc;
  }

  void setEndUtc(Instant endUtc) {
    this.endUtc = endUtc;
  }

  void setAllDay(boolean allDay) {
    this.allDay = allDay;
  }

  void setLocalDate(LocalDate localDate) {
    this.localDate = localDate;
  }
}
