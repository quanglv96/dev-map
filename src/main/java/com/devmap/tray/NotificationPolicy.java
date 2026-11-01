package com.devmap.tray;

import java.time.LocalDateTime;
import java.time.LocalTime;

/** FR-65, FR-66: may this notice pop up right now? No state and no AWT, so all of it runs in a plain test (AC-21). */
public final class NotificationPolicy {

  private NotificationPolicy() {
  }

  /** viewing: the PMO has this ticket on screen in a visible tab. Quiet hours hold for errors too: they only change the icon. */
  public static boolean allows(Notice notice, NotificationSettings settings, LocalDateTime now, boolean viewing) {
    return !viewing
        && !settings.mutedKinds().contains(notice.kind())
        && !settings.mutedProjects().contains(notice.project())
        && !snoozed(settings, now)
        && !quiet(settings, now.toLocalTime());
  }

  public static boolean snoozed(NotificationSettings settings, LocalDateTime now) {
    return settings.snoozeUntil() != null && now.isBefore(LocalDateTime.parse(settings.snoozeUntil()));
  }

  /** The range may cross midnight (22:00-07:00); its end is not part of it. from == to means no quiet hours. */
  public static boolean quiet(NotificationSettings settings, LocalTime time) {
    if (!settings.quietEnabled()) {
      return false;
    }
    LocalTime from = LocalTime.parse(settings.quietFrom());
    LocalTime to = LocalTime.parse(settings.quietTo());
    if (from.equals(to)) {
      return false;
    }
    return from.isBefore(to) ? !time.isBefore(from) && time.isBefore(to) : !time.isBefore(from) || time.isBefore(to);
  }

  /** "Đến 7 giờ sáng" in the tray menu: the next 07:00. */
  public static LocalDateTime nextSevenAm(LocalDateTime now) {
    LocalDateTime today = now.toLocalDate().atTime(7, 0);
    return now.isBefore(today) ? today : today.plusDays(1);
  }
}
