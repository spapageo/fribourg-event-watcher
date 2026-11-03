package ch.fribourgevents.notify;

import ch.fribourgevents.digest.DigestMessage;

/**
 * Delivery contract for every channel.
 *
 * <p>A sender renders the digest for its own transport, splits it with {@link MessageChunker}, and
 * delivers every part. Implementations return a {@link DeliveryResult} for anything that reached
 * the transport; a delivery that reached nothing is reported by throwing {@link
 * NotificationException}, so callers never confuse "sent zero parts" with "sent".
 *
 * <p>Single abstract method on purpose: tests and the pipeline can supply a lambda instead of a
 * class.
 */
@FunctionalInterface
public interface NotificationSender {

  /**
   * Delivers {@code message}.
   *
   * @throws NotificationException when nothing was delivered; the message must be safe to log (no
   *     tokens, no chat ids)
   */
  DeliveryResult send(DigestMessage message) throws NotificationException;
}
