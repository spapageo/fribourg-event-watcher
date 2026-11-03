package ch.fribourgevents.pipeline;

import ch.fribourgevents.digest.DigestMessage;
import ch.fribourgevents.notify.DeliveryResult;
import ch.fribourgevents.notify.NotificationException;
import ch.fribourgevents.notify.NotificationSender;
import java.util.ArrayList;
import java.util.List;

/** Recording {@link NotificationSender} for pipeline tests; can be told to fail. */
public final class FakeNotificationSender implements NotificationSender {

  private final List<DigestMessage> sent = new ArrayList<>();
  private NotificationException failure;
  private int delivered;

  @Override
  public DeliveryResult send(DigestMessage message) throws NotificationException {
    if (failure != null) {
      throw failure;
    }
    sent.add(message);
    delivered++;
    return DeliveryResult.delivered("fake", List.of("message-" + delivered));
  }

  public void failWith(NotificationException failure) {
    this.failure = failure;
  }

  public List<DigestMessage> sent() {
    return List.copyOf(sent);
  }
}
