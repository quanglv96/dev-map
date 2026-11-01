package com.devmap.tray;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Set;
import java.util.TreeSet;

/**
 * FR-66, ~/.devmap/notifications.yml (this machine only, never synced). What is listed under muted_* is off, so a kind or a project
 * added later is on by default. snoozeUntil = "Tạm tắt" from the tray menu, ISO local time.
 */
public record NotificationSettings(Set<NoticeKind> mutedKinds, Set<String> mutedProjects, Boolean quietEnabled, String quietFrom,
    String quietTo, String snoozeUntil) {

  public NotificationSettings {
    mutedKinds = mutedKinds == null ? Set.of() : Set.copyOf(mutedKinds);
    mutedProjects = mutedProjects == null ? Set.of() : new TreeSet<>(mutedProjects);
    quietEnabled = quietEnabled == null ? Boolean.TRUE : quietEnabled;
    quietFrom = quietFrom == null ? "22:00" : quietFrom;
    quietTo = quietTo == null ? "07:00" : quietTo;
    LocalTime.parse(quietFrom);
    LocalTime.parse(quietTo);
    if (snoozeUntil != null) {
      LocalDateTime.parse(snoozeUntil);
    }
  }

  public static NotificationSettings defaults() {
    return new NotificationSettings(null, null, null, null, null, null);
  }

  public NotificationSettings withSnooze(LocalDateTime until) {
    return new NotificationSettings(mutedKinds, mutedProjects, quietEnabled, quietFrom, quietTo, until == null ? null : until.toString());
  }
}
