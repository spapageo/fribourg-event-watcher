package ch.fribourgevents.notify.telegram;

import ch.fribourgevents.digest.DigestMessage;
import ch.fribourgevents.notify.DeliveryResult;
import ch.fribourgevents.notify.MessageChunker;
import ch.fribourgevents.notify.NotificationException;
import ch.fribourgevents.notify.NotificationSender;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@link NotificationSender} for Telegram.
 *
 * <p>Renders the digest as Telegram HTML, splits it with {@link MessageChunker}, and sends the
 * parts in order. If a later part fails the accepted ids are reported through {@link
 * DeliveryResult#partial} instead of being lost; if nothing was accepted the failure is thrown, so
 * the caller never mistakes "sent nothing" for a delivery.
 */
public final class TelegramNotificationSender implements NotificationSender {

  /** Value of {@link DeliveryResult#channel()} for this transport. */
  public static final String CHANNEL = "telegram";

  private final TelegramClient client;
  private final MessageChunker chunker;
  private final TelegramHtmlRenderer renderer;
  private final int partLimit;

  public TelegramNotificationSender(TelegramClient client) {
    this(client, new MessageChunker(), MessageChunker.DEFAULT_LIMIT);
  }

  /**
   * @param partLimit transport limit in characters, including the {@code (n/m)} suffix
   */
  public TelegramNotificationSender(TelegramClient client, int partLimit) {
    this(client, new MessageChunker(), partLimit);
  }

  TelegramNotificationSender(TelegramClient client, MessageChunker chunker, int partLimit) {
    this.client = Objects.requireNonNull(client, "client");
    this.chunker = Objects.requireNonNull(chunker, "chunker");
    this.renderer = new TelegramHtmlRenderer();
    if (partLimit < MessageChunker.MINIMUM_LIMIT) {
      throw new IllegalArgumentException(
          "partLimit must be at least " + MessageChunker.MINIMUM_LIMIT + ", was " + partLimit);
    }
    this.partLimit = partLimit;
  }

  @Override
  public DeliveryResult send(DigestMessage message) throws NotificationException {
    List<String> parts = chunker.chunk(renderer.render(message), partLimit);
    if (parts.isEmpty()) {
      throw new NotificationException("telegram: nothing to send");
    }

    List<String> accepted = new ArrayList<>(parts.size());
    for (int index = 0; index < parts.size(); index++) {
      try {
        accepted.add(Long.toString(client.sendMessage(parts.get(index))));
      } catch (NotificationException e) {
        if (accepted.isEmpty()) {
          throw e;
        }
        return DeliveryResult.partial(CHANNEL, index + 1, accepted);
      }
    }
    return DeliveryResult.delivered(CHANNEL, accepted);
  }
}
