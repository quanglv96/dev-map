package com.devmap.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class TabWatcherTest {

  private static final long GRACE = TabWatcher.GRACE_MILLIS;

  @Test
  void waitsForTheFirstTabHoweverLongTheBrowserTakes() {
    TabWatcher watcher = new TabWatcher();
    assertThat(watcher.shouldExit(0, false, 0)).isFalse();
    assertThat(watcher.shouldExit(0, false, 10 * GRACE)).isFalse();
  }

  @Test
  void exitsOnceTheLastTabHasBeenGoneForTheGracePeriod() {
    TabWatcher watcher = new TabWatcher();
    watcher.shouldExit(2, false, 0);
    watcher.shouldExit(1, false, 1000);
    assertThat(watcher.shouldExit(0, false, 1000 + GRACE - 1)).isFalse();
    assertThat(watcher.shouldExit(0, false, 1000 + GRACE)).isTrue();
  }

  @Test
  void aReloadInsideTheGracePeriodKeepsItRunning() {
    TabWatcher watcher = new TabWatcher();
    watcher.shouldExit(1, false, 0);
    watcher.shouldExit(0, false, 1000);
    watcher.shouldExit(1, false, 2000);
    assertThat(watcher.shouldExit(0, false, 2000 + GRACE - 1)).isFalse();
  }

  /** SRS: closing the tab does not stop the agent; DevMap exits only after the work ends. */
  @Test
  void runningAgentsKeepItAliveUntilTheyFinish() {
    TabWatcher watcher = new TabWatcher();
    watcher.shouldExit(1, false, 0);
    assertThat(watcher.shouldExit(0, true, 100 * GRACE)).isFalse();
    assertThat(watcher.shouldExit(0, false, 100 * GRACE + GRACE - 1)).isFalse();
    assertThat(watcher.shouldExit(0, false, 101 * GRACE)).isTrue();
  }

  @Test
  void pingDropsClosedStreams() {
    EventController events = new EventController();
    SseEmitter closed = events.events("");
    events.events("shop");
    assertThat(events.pingTabs()).isEqualTo(2);
    closed.complete();
    assertThat(events.pingTabs()).isEqualTo(1);
  }
}
