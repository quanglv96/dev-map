package com.devmap.tray;

/**
 * The channel to the PMO's desktop. TrayService decides what to say and when; another channel (WinRT toast, Telegram) replaces only
 * this.
 */
public interface TrayNotifier {

  /** False when this machine has no tray: DevMap runs on without one (FR-58, AC-24). */
  boolean start(TrayActions actions);

  void show(TrayView view);

  /** A click on the pop-up opens popup.path(). */
  void notify(Popup popup);

  void stop();

  /** True when the OS itself drops our pop-ups (Windows: Settings → Notifications is off); the settings card says so. */
  default boolean silenced() {
    return false;
  }
}
