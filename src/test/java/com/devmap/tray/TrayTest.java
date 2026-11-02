package com.devmap.tray;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.awt.Color;
import java.awt.EventQueue;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** AC-24 and the parts of the tray that do not need a screen. */
class TrayTest {

  /** AC-24: a machine without a tray: start says so, and nothing else in the notifier touches AWT. */
  @Test
  void withoutATrayTheNotifierDeclinesQuietly() {
    AwtTrayNotifier notifier = new AwtTrayNotifier(() -> false);
    assertThat(notifier.start(null)).isFalse();
  }

  /** A hung AWT thread (AWT's own shutdown hook got there first) must not keep the JVM and its lock on devmap.jar alive. */
  @Test
  void stopReturnsWhenTheEventThreadIsStuck() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    EventQueue.invokeLater(() -> {
      try {
        release.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
    try {
      assertTimeoutPreemptively(Duration.ofSeconds(10), () -> new AwtTrayNotifier(() -> false).stop());
    } finally {
      release.countDown();
    }
  }

  private static long differing(BufferedImage a, BufferedImage b) {
    long n = 0;
    for (int x = 0; x < a.getWidth(); x++) {
      for (int y = 0; y < a.getHeight(); y++) {
        if (a.getRGB(x, y) != b.getRGB(x, y)) {
          n++;
        }
      }
    }
    return n;
  }

  private static boolean mostlyRed(int rgb) {
    Color c = new Color(rgb);
    return c.getRed() > 180 && c.getGreen() < 80 && c.getBlue() < 80;
  }

  private static boolean hasRed(BufferedImage image) {
    for (int x = 0; x < image.getWidth(); x++) {
      for (int y = 0; y < image.getHeight(); y++) {
        if (mostlyRed(image.getRGB(x, y))) {
          return true;
        }
      }
    }
    return false;
  }

  /** FR-59: the four states look different at every prepared size, and the base icon is in the jar. */
  @Test
  void eachStateIsDrawnAtEachSize() {
    for (int size : new int[] {16, 24, 32}) {
      assertThat(TrayService.class.getResource("/tray/icon-" + size + ".png")).isNotNull();
      BufferedImage normal = TrayIconRenderer.render(size, 0, 0, false);
      BufferedImage count = TrayIconRenderer.render(size, 3, 0, false);
      BufferedImage running = TrayIconRenderer.render(size, 0, 1, false);
      BufferedImage error = TrayIconRenderer.render(size, 0, 0, true);
      assertThat(normal.getWidth()).isEqualTo(size);
      assertThat(hasRed(normal)).isFalse();
      assertThat(hasRed(count)).isTrue();
      assertThat(hasRed(error)).isTrue();
      assertThat(hasRed(running)).isFalse();
      assertThat(differing(normal, running)).isPositive();
      assertThat(differing(count, TrayIconRenderer.render(size, 5, 0, false))).isPositive();
      assertThat(differing(count, TrayIconRenderer.render(size, 3, 1, true))).isZero(); // the count wins
    }
  }

  @Test
  void picksThePreparedSizeForTheScreenScale() {
    assertThat(TrayIconRenderer.pickSize(16)).isEqualTo(16);
    assertThat(TrayIconRenderer.pickSize(20)).isEqualTo(24);
    assertThat(TrayIconRenderer.pickSize(24)).isEqualTo(24);
    assertThat(TrayIconRenderer.pickSize(32)).isEqualTo(32);
    assertThat(TrayIconRenderer.pickSize(64)).isEqualTo(32);
  }

  @Test
  void loginLinksWorkOnceAndExpire() {
    LoginLinks links = new LoginLinks();
    String code = links.issue(0);
    assertThat(links.consume("wrong", 1)).isFalse();
    assertThat(links.consume(null, 1)).isFalse();
    assertThat(links.consume(code, 1)).isTrue();
    assertThat(links.consume(code, 2)).isFalse();
    assertThat(links.consume(links.issue(0), LoginLinks.TTL_MILLIS)).isFalse();
  }

  /** FR-60 */
  @Test
  void tooltipCountsWaitingAndRunning() {
    TrayView view = new TrayView(4, java.util.List.of(), 2, false, java.util.List.of(), false, false, false, false);
    assertThat(view.tooltip()).isEqualTo("DevMap · 4 việc chờ bạn · 2 agent đang chạy");
  }

  @Test
  void settingsSurviveAFileAndABrokenFileFallsBack(@TempDir Path home) throws Exception {
    NotificationRepository repo = new NotificationRepository(home);
    assertThat(repo.read()).isEqualTo(NotificationSettings.defaults());
    NotificationSettings mine = new NotificationSettings(java.util.Set.of(NoticeKind.SYNC), java.util.Set.of("shop"), false, "21:30",
        "06:00",
        "2026-11-02T07:00");
    repo.write(mine);
    assertThat(repo.read()).isEqualTo(mine);
    assertThat(Files.readString(home.resolve("notifications.yml"))).contains("muted_kinds", "quiet_from");
    Files.writeString(home.resolve("notifications.yml"), "quiet_from: never");
    assertThat(repo.read()).isEqualTo(NotificationSettings.defaults());
  }

  /** FR-70: the argument line starts DevMap in the background with no browser. */
  @Test
  void autostartStartsInTheBackground() {
    assertThat(StartupShortcut.arguments("C:\\Dev Map\\devmap.jar", 7070))
        .isEqualTo("-jar \"C:\\Dev Map\\devmap.jar\" serve --background --no-open --port 7070");
    assertThat(new StartupShortcut((Path) null).supported()).isFalse();
    assertThat(new StartupShortcut((Path) null).enabled()).isFalse();
  }

  /** FR-70: a real .lnk, here in a temp folder; turning it off removes it. */
  @Test
  @EnabledOnOs(OS.WINDOWS)
  void autostartWritesAndRemovesTheShortcut(@TempDir Path folder) throws Exception {
    StartupShortcut shortcut = new StartupShortcut(folder.resolve("Thư mục có dấu"));
    assertThat(shortcut.enabled()).isFalse();
    shortcut.write("C:\\Dev Map\\devmap.jar", "C:\\Java\\bin\\javaw.exe", 7070);
    assertThat(shortcut.enabled()).isTrue();
    shortcut.set(false, 7070);
    assertThat(shortcut.enabled()).isFalse();
  }
}
