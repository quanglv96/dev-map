package com.devmap.tray;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * FR-65: everything offered within 10 s of the first notice leaves as one batch, and the same event of the same ticket is dropped for
 * 5 minutes. Times are passed in so tests need no clock; not thread safe, TrayService calls it from its one tick thread.
 */
public final class NotificationBatcher {

  public static final long WINDOW_MILLIS = 10_000;
  public static final long REPEAT_MILLIS = 5 * 60_000;

  private final List<Notice> waiting = new ArrayList<>();
  private final Map<String, Long> lastSeen = new HashMap<>();
  private long firstAt;

  /** False when it was dropped as a repeat. */
  public boolean offer(Notice notice, long now) {
    lastSeen.values().removeIf(at -> now - at >= REPEAT_MILLIS);
    String key = notice.kind() + "/" + notice.project() + "/" + notice.ticket();
    if (lastSeen.containsKey(key)) {
      return false;
    }
    lastSeen.put(key, now);
    if (waiting.isEmpty()) {
      firstAt = now;
    }
    waiting.add(notice);
    return true;
  }

  /** The finished batch, or empty while the window is still open. */
  public List<Notice> poll(long now) {
    if (waiting.isEmpty() || now - firstAt < WINDOW_MILLIS) {
      return List.of();
    }
    List<Notice> batch = List.copyOf(waiting);
    waiting.clear();
    return batch;
  }
}
