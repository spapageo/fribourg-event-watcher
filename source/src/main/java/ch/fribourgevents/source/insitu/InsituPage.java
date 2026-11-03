package ch.fribourgevents.source.insitu;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Wire shape of one page of the insitu.live agenda listing.
 *
 * <p>The API groups results by month ({@code member[].events[]}) and reports paging through {@code
 * view}. Only the fields the digest uses are modelled; unknown fields are ignored.
 *
 * <p>A row is <em>one open day</em> of an event, not one event: a two-month exhibition appears once
 * per open day, always with the same {@code date_from}/{@code date_to} run range and a different
 * {@code next_date}. {@link InsituEventSource} therefore collapses rows by {@code id}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record InsituPage(List<MonthGroup> member, View view) {

  /** All event rows of this page in upstream order. */
  List<InsituEvent> events() {
    if (member == null) {
      return List.of();
    }
    return member.stream()
        .filter(group -> group != null && group.events() != null)
        .flatMap(group -> group.events().stream())
        .toList();
  }

  /** True when upstream advertises a further page. */
  boolean hasNext() {
    return view != null && view.next() != null && !view.next().isBlank();
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record MonthGroup(String date, List<InsituEvent> events) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record View(String first, String last, String next) {}
}
