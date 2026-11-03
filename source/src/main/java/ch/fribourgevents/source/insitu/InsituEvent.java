package ch.fribourgevents.source.insitu;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Wire shape of one agenda row of insitu.live.
 *
 * <p>Instants are kept as raw ISO-8601 text (with offset) so that a date the adapter cannot parse
 * is skipped as a malformed row instead of aborting the whole source. {@code id} is the stable
 * identity; {@code slug} only feeds the public URL.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record InsituEvent(
    Long id,
    String slug,
    String name,
    @JsonProperty("event_status") String eventStatus,
    Place place,
    List<Category> categories,
    @JsonProperty("date_from") String dateFrom,
    @JsonProperty("date_to") String dateTo,
    @JsonProperty("next_date") String nextDate,
    Labels labels) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Place(
      String name, String address, String zip, String city, Double latitude, Double longitude) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Category(Label label) {}

  /** Bilingual upstream label; {@code fr} is preferred, {@code de} is the fallback. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record Label(String fr, String de) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Labels(Label name, Label description) {}
}
