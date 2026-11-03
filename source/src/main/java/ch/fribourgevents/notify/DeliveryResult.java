package ch.fribourgevents.notify;

import java.util.List;
import java.util.Objects;

/**
 * Outcome of one {@link NotificationSender#send} call.
 *
 * @param channel the transport that delivered, for example {@code telegram}
 * @param parts how many message parts the sender attempted
 * @param externalIds transport-side identifiers, one per accepted part, in send order
 * @param status whether every part was accepted or the send stopped part-way
 */
public record DeliveryResult(
    String channel, int parts, List<String> externalIds, DeliveryStatus status) {

  public enum DeliveryStatus {
    /** Every part was accepted by the transport. */
    DELIVERED,
    /**
     * Some parts were accepted before a permanent failure; the sender reported it instead of
     * throwing, so the accepted ids are not lost.
     */
    PARTIAL
  }

  public DeliveryResult {
    if (channel == null || channel.isBlank()) {
      throw new IllegalArgumentException("channel must not be blank");
    }
    if (parts < 0) {
      throw new IllegalArgumentException("parts must not be negative");
    }
    externalIds = List.copyOf(Objects.requireNonNull(externalIds, "externalIds"));
    if (externalIds.size() > parts) {
      throw new IllegalArgumentException("externalIds must not outnumber parts");
    }
    Objects.requireNonNull(status, "status");
  }

  /** A delivery where every part was accepted, one id per part. */
  public static DeliveryResult delivered(String channel, List<String> externalIds) {
    List<String> ids = List.copyOf(externalIds);
    return new DeliveryResult(channel, ids.size(), ids, DeliveryStatus.DELIVERED);
  }

  /** A delivery that stopped after some parts; {@code attemptedParts} counts what was tried. */
  public static DeliveryResult partial(
      String channel, int attemptedParts, List<String> acceptedIds) {
    return new DeliveryResult(channel, attemptedParts, acceptedIds, DeliveryStatus.PARTIAL);
  }
}
