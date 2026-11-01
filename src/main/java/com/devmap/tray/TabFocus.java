package com.devmap.tray;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FR-65: which ticket each visible browser tab shows. Tabs report on every change and every 30 s while visible, so a browser that
 * crashed without saying goodbye stops counting after 90 s.
 */
final class TabFocus {

  static final long EXPIRES_MILLIS = 90_000;

  private record Seen(String project, String ticket, long at) {
  }

  private final Map<String, Seen> tabs = new ConcurrentHashMap<>();

  /** ticket null = this tab is hidden, closed or not on a ticket. */
  void report(String tab, String project, String ticket, long now) {
    if (ticket == null) {
      tabs.remove(tab);
    } else {
      tabs.put(tab, new Seen(project, ticket, now));
    }
  }

  boolean viewing(String project, String ticket, long now) {
    tabs.values().removeIf(s -> now - s.at >= EXPIRES_MILLIS);
    return ticket != null && tabs.values().stream().anyMatch(s -> s.project.equals(project) && s.ticket.equals(ticket));
  }
}
