package com.devmap.web;

/**
 * Decides when `devmap serve` may exit: a tab has been open, none is left and no background work has run for GRACE_MILLIS.
 * The grace period covers a reload or moving between pages, which drop the event stream for a moment.
 */
public final class TabWatcher {

  public static final long GRACE_MILLIS = 15_000;

  private boolean tabSeen;
  private long lastActive;

  /** Never true before the first tab connects: the browser may take a while to open. */
  public boolean shouldExit(int tabs, boolean busy, long now) {
    if (tabs > 0) {
      tabSeen = true;
    }
    if (tabs > 0 || busy || !tabSeen) {
      lastActive = now;
      return false;
    }
    return now - lastActive >= GRACE_MILLIS;
  }
}
