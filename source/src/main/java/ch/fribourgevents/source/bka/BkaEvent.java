package ch.fribourgevents.source.bka;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Wire shape of one page of the bka.ch agenda JSON API ({@code GET /api/events}).
 *
 * <p>The site's own frontend calls this endpoint; results are grouped by month ({@code
 * data[].events[]}) and paging is plain {@code page}/{@code limit}. The endpoint reports no
 * last-page marker: a page beyond the end carries an empty {@code data} list, which is how {@link
 * BkaClient} stops. Unknown fields are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record BkaPage(Integer code, String message, List<MonthGroup> data, Integer total) {

  /** All event rows of this page in upstream order. */
  List<BkaEvent> events() {
    if (data == null) {
      return List.of();
    }
    return data.stream()
        .filter(group -> group != null && group.events() != null)
        .flatMap(group -> group.events().stream())
        .toList();
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record MonthGroup(String date, List<BkaEvent> events) {}
}

/**
 * Wire shape of one agenda row. A row is one <em>showing</em>: recurring productions appear once
 * per date (as a {@code subevent} with {@code mainevent_id}), so the adapter groups rows by {@code
 * mainevent_id} when present.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record BkaEvent(
    Long id,
    String slug,
    String name,
    @JsonProperty("event_status") String eventStatus,
    Place place,
    @JsonProperty("place_proposed") Place placeProposed,
    List<Category> categories,
    @JsonProperty("date_from") String dateFrom,
    @JsonProperty("date_to") String dateTo,
    @JsonProperty("special_rate") String specialRate,
    List<Rate> rates,
    @JsonProperty("mainevent_id") Long maineventId,
    @JsonProperty("mainevent_slug") String maineventSlug,
    Labels labels) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Place(
      String name, String address, String zip, String city, Double latitude, Double longitude) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Category(Label label) {}

  /** Bilingual upstream label; German is preferred because the agenda is published in German. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record Label(String de, String fr) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Labels(Label description) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Rate(String price, RateLabels labels) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record RateLabels(Label designation) {}
}
