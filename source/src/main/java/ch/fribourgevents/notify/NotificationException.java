package ch.fribourgevents.notify;

/**
 * A delivery that reached nothing.
 *
 * <p>The message is written to the {@code notification_delivery} log line, so it must stay free of
 * secrets: never include bot tokens, chat ids, or full response bodies that echo them.
 */
public class NotificationException extends Exception {

  private static final long serialVersionUID = 1L;

  public NotificationException(String message) {
    super(message);
  }

  public NotificationException(String message, Throwable cause) {
    super(message, cause);
  }
}
