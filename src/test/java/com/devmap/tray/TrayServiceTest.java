package com.devmap.tray;

import static org.assertj.core.api.Assertions.assertThat;

import com.devmap.SampleRepos;
import com.devmap.agent.AgentService;
import com.devmap.config.ProjectService;
import com.devmap.config.ProjectService.RepoInput;
import com.devmap.secret.SecretMasker;
import com.devmap.sync.SyncService;
import com.devmap.ticket.TaskService;
import com.devmap.ticket.TaskService.Actor;
import com.devmap.ticket.TicketGitService.GateFailed;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Real tickets through a fake notifier: from a status change to what the PMO would see. Time only moves through tick(). */
@SpringBootTest
class TrayServiceTest {

  private static final LocalDateTime NOON = LocalDateTime.of(2026, 11, 2, 12, 0);
  private static final long SETTLE = 3_000;

  static Path tmp;

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add("devmap.home", () -> tmp.resolve("home").toString());
  }

  @BeforeAll
  static void repos() throws Exception {
    tmp = Files.createTempDirectory("devmap-tray");
    SampleRepos.create(tmp.resolve("repos"));
  }

  static final class FakeNotifier implements TrayNotifier {
    final List<Popup> popups = new ArrayList<>();
    final List<TrayView> views = new ArrayList<>();
    boolean started;
    boolean stopped;

    @Override
    public boolean start(TrayActions actions) {
      started = true;
      return true;
    }

    @Override
    public void show(TrayView view) {
      views.add(view);
    }

    @Override
    public void notify(Popup popup) {
      popups.add(popup);
    }

    @Override
    public void stop() {
      stopped = true;
    }

    TrayView last() {
      return views.get(views.size() - 1);
    }
  }

  @Autowired
  ProjectService projects;

  @Autowired
  TaskService tasks;

  @Autowired
  AgentService agents;

  @Autowired
  SyncService sync;

  @Autowired
  NotificationRepository settings;

  @Autowired
  LoginLinks links;

  @Autowired
  Environment env;

  FakeNotifier notifier;
  TrayService tray;
  long now;

  @BeforeEach
  void setUp() {
    if (projects.list().isEmpty()) {
      projects.create("shop", tmp.resolve("data"), "#ff8800",
          List.of(new RepoInput(tmp.resolve("repos/order-service").toString(), "main")));
    }
    settings.write(NotificationSettings.defaults());
    notifier = new FakeNotifier();
    tray = new TrayService(projects, tasks, agents, sync, new SecretMasker(), settings, notifier, links, new StartupShortcut((Path) null),
        env);
    tray.activate(() -> { });
    now = 1_000_000;
    step();
  }

  private void step() {
    tray.tick(NOON, now);
    now += SETTLE;
    tray.onTaskChanged(null);
  }

  private String ticket() {
    return tasks.create("shop", "FEAT", "", "", "Thêm báo cáo").task().id();
  }

  private String awaitingApproval() {
    String id = ticket();
    tasks.transition("shop", id, "sa_analysis", Actor.SA);
    tasks.transition("shop", id, "awaiting_approval", Actor.SA);
    return id;
  }

  /** Enough ticks for the 10 s window to close. */
  private void settle() {
    for (int i = 0; i < 5; i++) {
      step();
    }
  }

  /** FR-63, FR-64 */
  @Test
  void aTicketReachingAGatePopsUpOnceAndCounts() {
    int before = notifier.last().pending();
    String id = awaitingApproval();
    settle();
    assertThat(notifier.popups).containsExactly(new Popup("[shop] " + id, "Chờ bạn chốt giải pháp", "/shop?ticket=" + id));
    assertThat(notifier.last().pending()).isEqualTo(before + 1);
    assertThat(notifier.last().rows()).anyMatch(r -> r.path().equals("/shop?ticket=" + id));
    settle();
    assertThat(notifier.popups).hasSize(1);
  }

  /** Nothing pops up for what was already waiting when DevMap started. */
  @Test
  void whatWaitedBeforeStartIsCountedButNotAnnounced() {
    awaitingApproval();
    FakeNotifier fresh = new FakeNotifier();
    TrayService restarted = new TrayService(projects, tasks, agents, sync, new SecretMasker(), settings, fresh, links,
        new StartupShortcut((Path) null), env);
    restarted.activate(() -> { });
    for (int i = 0; i < 6; i++) {
      restarted.tick(NOON, now + i * SETTLE);
    }
    assertThat(fresh.popups).isEmpty();
    assertThat(fresh.last().pending()).isPositive();
  }

  /** FR-65 */
  @Test
  void theTicketOpenInAVisibleTabStaysQuiet() {
    String id = ticket();
    step();
    tray.reportFocus("tab-1", "shop", id);
    tasks.transition("shop", id, "sa_analysis", Actor.SA);
    tasks.transition("shop", id, "awaiting_approval", Actor.SA);
    settle();
    assertThat(notifier.popups).isEmpty();
    assertThat(notifier.last().rows()).anyMatch(r -> r.path().endsWith(id));
  }

  /** AC-21: quiet hours change the icon, not the screen. */
  @Test
  void quietHoursStillUpdateTheIcon() {
    int before = notifier.last().pending();
    awaitingApproval();
    for (int i = 0; i < 5; i++) {
      tray.tick(NOON.withHour(23), now);
      now += SETTLE;
      tray.onTaskChanged(null);
    }
    assertThat(notifier.popups).isEmpty();
    assertThat(notifier.last().pending()).isEqualTo(before + 1);
  }

  /** FR-64 */
  @Test
  void aFailedGatePopsUp() {
    String id = ticket();
    step();
    tray.onGateFailed(new GateFailed("shop", id));
    settle();
    assertThat(notifier.popups).containsExactly(new Popup("[shop] " + id, "Quality gate không đạt", "/shop?ticket=" + id));
  }

  /** FR-58: until `devmap serve` starts it, the service does nothing with events. */
  @Test
  void beforeStartEventsAreIgnored() {
    FakeNotifier idle = new FakeNotifier();
    TrayService notStarted = new TrayService(projects, tasks, agents, sync, new SecretMasker(), settings, idle, links,
        new StartupShortcut((Path) null), env);
    notStarted.onGateFailed(new GateFailed("shop", "NYX-1"));
    notStarted.stop();
    assertThat(idle.started).isFalse();
    assertThat(idle.stopped).isFalse();
  }

  /** FR-62: a link from the menu carries a fresh one-time code and no run token. */
  @Test
  void linksCarryAOneTimeCode() {
    String url = tray.loginUrl("/shop?ticket=NYX-1");
    assertThat(url).contains("/shop?ticket=NYX-1&once=").doesNotContain("token");
    assertThat(links.consume(url.substring(url.indexOf("once=") + 5))).isTrue();
    assertThat(tray.loginUrl("/")).contains("/?once=");
  }

  /** FR-67 */
  @Test
  void stoppingRemovesTheIcon() {
    tray.stop();
    assertThat(notifier.stopped).isTrue();
  }
}
