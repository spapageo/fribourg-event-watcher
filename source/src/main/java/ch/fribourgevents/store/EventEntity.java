package ch.fribourgevents.store;

import ch.fribourgevents.domain.EventStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "event")
public class EventEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @JdbcTypeCode(SqlTypes.INTEGER)
  private Long id;

  @Column(name = "dedup_key", nullable = false, unique = true)
  private String dedupKey;

  @Column(nullable = false)
  private String title;

  @Column(name = "normalized_title", nullable = false)
  private String normalizedTitle;

  private String description;

  @Column(name = "venue_name")
  private String venueName;

  @Column(name = "address_line")
  private String addressLine;

  private String city;

  private String canton;

  @Column(nullable = false)
  private String country;

  private Double lat;

  private Double lon;

  private String url;

  /** Newline-separated category names, kept stable for future interest filtering. */
  private String categories;

  @Column(name = "price_info")
  private String priceInfo;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private EventStatus status;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected EventEntity() {}

  public Long getId() {
    return id;
  }

  public String getDedupKey() {
    return dedupKey;
  }

  public String getTitle() {
    return title;
  }

  public String getNormalizedTitle() {
    return normalizedTitle;
  }

  public String getDescription() {
    return description;
  }

  public String getVenueName() {
    return venueName;
  }

  public String getAddressLine() {
    return addressLine;
  }

  public String getCity() {
    return city;
  }

  public String getCanton() {
    return canton;
  }

  public String getCountry() {
    return country;
  }

  public Double getLat() {
    return lat;
  }

  public Double getLon() {
    return lon;
  }

  public String getUrl() {
    return url;
  }

  public String getCategories() {
    return categories;
  }

  public String getPriceInfo() {
    return priceInfo;
  }

  public EventStatus getStatus() {
    return status;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  void setDedupKey(String dedupKey) {
    this.dedupKey = dedupKey;
  }

  void setTitle(String title) {
    this.title = title;
  }

  void setNormalizedTitle(String normalizedTitle) {
    this.normalizedTitle = normalizedTitle;
  }

  void setDescription(String description) {
    this.description = description;
  }

  void setVenueName(String venueName) {
    this.venueName = venueName;
  }

  void setAddressLine(String addressLine) {
    this.addressLine = addressLine;
  }

  void setCity(String city) {
    this.city = city;
  }

  void setCanton(String canton) {
    this.canton = canton;
  }

  void setCountry(String country) {
    this.country = country;
  }

  void setLat(Double lat) {
    this.lat = lat;
  }

  void setLon(Double lon) {
    this.lon = lon;
  }

  void setUrl(String url) {
    this.url = url;
  }

  void setCategories(String categories) {
    this.categories = categories;
  }

  void setPriceInfo(String priceInfo) {
    this.priceInfo = priceInfo;
  }

  void setStatus(EventStatus status) {
    this.status = status;
  }

  void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }

  void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
  }
}
