package com.devmap.tray;

import java.util.List;

/**
 * Everything the icon, tooltip and menu show (FR-59, FR-60, FR-62). A record, so the notifier can tell whether anything changed.
 * pending counts every waiting ticket; rows holds the first ones, a menu cannot grow without limit.
 */
public record TrayView(int pending, List<Waiting> rows, int running, boolean error, List<String> projects, boolean canSync, boolean snoozed,
    boolean autostart, boolean canAutostart) {

  /** A row under "Việc chờ bạn": path is what a click opens. */
  public record Waiting(String label, String path) {
  }

  /** FR-60 */
  public String tooltip() {
    return "DevMap · " + pending + " việc chờ bạn · " + running + " agent đang chạy";
  }

  /** The same view without what only the icon shows, to tell whether the menu must be rebuilt. */
  TrayView menuPart() {
    return new TrayView(pending, rows, 0, false, projects, canSync, snoozed, autostart, canAutostart);
  }
}
