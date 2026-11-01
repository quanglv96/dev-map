package com.devmap.web;

import com.devmap.tray.NotificationSettings;
import com.devmap.tray.TrayService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Notification settings (FR-66), which ticket a tab shows (FR-65), and a second `devmap` asking this one to open a page (FR-68). */
@RestController
public class TrayController {

  public record FocusRequest(String tab, String project, String ticket) {
  }

  private final TrayService tray;

  public TrayController(TrayService tray) {
    this.tray = tray;
  }

  @GetMapping("/api/tray/settings")
  public TrayService.SettingsView settings() {
    return tray.settingsView();
  }

  @PutMapping("/api/tray/settings")
  public TrayService.SettingsView save(@RequestBody NotificationSettings settings) {
    return tray.saveSettings(settings);
  }

  @PostMapping("/api/tray/focus")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void focus(@RequestBody FocusRequest req) {
    tray.reportFocus(req.tab(), req.project(), req.ticket());
  }

  @PostMapping("/api/tray/open")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void open() {
    tray.open("/");
  }
}
