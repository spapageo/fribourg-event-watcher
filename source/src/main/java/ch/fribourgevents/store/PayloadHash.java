package ch.fribourgevents.store;

import ch.fribourgevents.domain.EventOccurrence;
import ch.fribourgevents.domain.SourceEvent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.stream.Collectors;

/** Stable fingerprint of every source-owned field, used to detect real payload changes. */
final class PayloadHash {

  private PayloadHash() {}

  static String of(SourceEvent event) {
    StringBuilder payload = new StringBuilder();
    field(payload, event.title());
    field(payload, event.description());
    field(payload, event.venueName());
    field(payload, event.addressLine());
    field(payload, event.city());
    field(payload, event.canton());
    field(payload, event.country());
    field(
        payload,
        event.latitude() == null ? null : event.latitude().stripTrailingZeros().toPlainString());
    field(
        payload,
        event.longitude() == null ? null : event.longitude().stripTrailingZeros().toPlainString());
    field(payload, event.url() == null ? null : event.url().toString());
    field(payload, event.categories().stream().sorted().collect(Collectors.joining("\u001f")));
    field(payload, event.priceInfo());
    field(payload, event.status().name());
    event.occurrences().stream()
        .sorted(java.util.Comparator.comparing(EventOccurrence::start))
        .forEach(occurrence -> {
          field(payload, InstantText.format(occurrence.start()));
          field(payload, occurrence.end() == null ? null : InstantText.format(occurrence.end()));
          field(payload, Boolean.toString(occurrence.allDay()));
        });
    return sha256(payload.toString());
  }

  private static void field(StringBuilder payload, String value) {
    if (value == null) {
      payload.append('\u0000');
    } else {
      payload.append(value.length()).append(':').append(value);
    }
    payload.append('\u001e');
  }

  private static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }
}
