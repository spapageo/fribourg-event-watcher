package ch.fribourgevents.pipeline;

/**
 * Per-run switches owned by the runtime configuration.
 *
 * @param dryRun render and log the digest without calling the notification sender
 * @param sendFailureNotice when every source fails, send a short failure notice instead of a
 *     misleading empty digest (off by default: a failed run is visible in the logs)
 * @param showMergedCount append a footer line when cross-source dedup merged events this run
 */
public record PipelineOptions(boolean dryRun, boolean sendFailureNotice, boolean showMergedCount) {

  public static PipelineOptions defaults() {
    return new PipelineOptions(false, false, true);
  }
}
