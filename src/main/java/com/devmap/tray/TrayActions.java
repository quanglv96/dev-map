package com.devmap.tray;

/** What the icon and its menu can ask for. Called on the AWT thread, so implementations hand slow work to another thread. */
public interface TrayActions {

  void open(String path);

  void snoozeOneHour();

  void snoozeUntilMorning();

  void resumeNotifications();

  void syncNow();

  void setAutostart(boolean on);

  /** Sessions running right now, for the question before quitting (FR-67). */
  int runningAgents();

  void exit();
}
