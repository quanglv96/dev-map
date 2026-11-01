package com.devmap.tray;

import com.devmap.agent.AgentService;
import com.devmap.agent.AgentService.AgentProgress;
import com.devmap.config.Project;
import com.devmap.config.ProjectService;
import com.devmap.secret.SecretMasker;
import com.devmap.sync.SyncService;
import com.devmap.sync.SyncService.State;
import com.devmap.sync.SyncService.Status;
import com.devmap.sync.SyncService.SyncChanged;
import com.devmap.ticket.Task;
import com.devmap.ticket.TaskChanged;
import com.devmap.ticket.TaskService;
import com.devmap.ticket.TaskStatus;
import com.devmap.ticket.TicketGitService.GateFailed;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * FR-58 → FR-67: turns what happens in DevMap into the tray icon and pop-ups. Listeners only leave a mark; one background thread reads
 * tickets and decides, so a request or an agent step never waits for the tray. Nothing runs until `devmap serve` calls start().
 */
@Service
public class TrayService implements TrayActions {

  /** For the settings card. active = this run has a tray icon; autostart is the shortcut itself, not a setting. */
  public record SettingsView(boolean active, NotificationSettings settings, boolean snoozed, boolean autostart, boolean canAutostart) {
  }

  private static final Logger LOG = LoggerFactory.getLogger(TrayService.class);
  private static final long REFRESH_ANYWAY_MILLIS = 60_000;
  private static final long MIN_REFRESH_MILLIS = 2_000;
  private static final long RELEASE_LOCKS_SECONDS = 15;
  private static final int MAX_MENU_ROWS = 15;

  private final ProjectService projects;
  private final TaskService tasks;
  private final AgentService agents;
  private final SyncService sync;
  private final SecretMasker masker;
  private final NotificationRepository settings;
  private final TrayNotifier notifier;
  private final LoginLinks links;
  private final StartupShortcut shortcut;
  private final Environment env;

  private final NotificationBatcher batcher = new NotificationBatcher();
  private final TabFocus focus = new TabFocus();
  private final Queue<Notice> events = new ConcurrentLinkedQueue<>();
  private final Map<String, Status> syncStates = new ConcurrentHashMap<>();
  private volatile boolean active;
  private volatile boolean dirty = true;
  private ScheduledExecutorService ticker;
  private Runnable onExit = () -> { };
  private TraySnapshot snapshot;
  private int lastRunning;
  private long lastRefresh;

  public TrayService(ProjectService projects, TaskService tasks, AgentService agents, SyncService sync, SecretMasker masker,
      NotificationRepository settings, TrayNotifier notifier, LoginLinks links, StartupShortcut shortcut, Environment env) {
    this.projects = projects;
    this.tasks = tasks;
    this.agents = agents;
    this.sync = sync;
    this.masker = masker;
    this.settings = settings;
    this.notifier = notifier;
    this.links = links;
    this.shortcut = shortcut;
    this.env = env;
  }

  /** `devmap serve`. False = no tray on this machine: everything here stays idle and the server runs as usual (AC-24). */
  public synchronized boolean start(Runnable exit) {
    activate(exit);
    if (active) {
      ticker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "devmap-tray");
        t.setDaemon(true);
        return t;
      });
      ticker.scheduleWithFixedDelay(this::tickSafely, 0, 1, TimeUnit.SECONDS);
    }
    return active;
  }

  /** Separate from the timer so tests can call tick() themselves. */
  void activate(Runnable exit) {
    onExit = exit;
    active = notifier.start(this);
  }

  private void tickSafely() {
    try {
      tick(LocalDateTime.now(), System.currentTimeMillis());
    } catch (RuntimeException e) {
      LOG.warn("Không cập nhật được biểu tượng khay: {}", e.toString()); // the next tick tries again; the thread must survive
    }
  }

  /** One step of the background thread; times are parameters for the tests. */
  void tick(LocalDateTime now, long millis) {
    int running = agents.runningCount();
    boolean due = dirty && millis - lastRefresh >= MIN_REFRESH_MILLIS || running != lastRunning
        || millis - lastRefresh >= REFRESH_ANYWAY_MILLIS;
    if (snapshot == null || due) {
      refresh(now, millis, running);
    }
    for (Notice n = events.poll(); n != null; n = events.poll()) {
      offer(n, now, millis);
    }
    List<Notice> batch = batcher.poll(millis);
    if (!batch.isEmpty()) {
      notifier.notify(Popup.of(batch, masker));
    }
  }

  private void refresh(LocalDateTime now, long millis, int running) {
    dirty = false;
    lastRefresh = millis;
    lastRunning = running;
    TraySnapshot next = read();
    if (snapshot != null) {
      next.noticesSince(snapshot).forEach(n -> offer(n, now, millis));
    }
    snapshot = next;
    notifier.show(view(next, running, now));
  }

  private void offer(Notice notice, LocalDateTime now, long millis) {
    if (NotificationPolicy.allows(notice, settings.read(), now, focus.viewing(notice.project(), notice.ticket(), millis))) {
      batcher.offer(notice, millis);
    }
  }

  private TraySnapshot read() {
    Map<String, TraySnapshot.Item> items = new LinkedHashMap<>();
    for (Project p : projects.list()) {
      if (p.error() != null) {
        continue;
      }
      AgentService.Activity activity = agents.activity(p.name());
      for (Task t : tasks.list(p.name())) {
        if (t.status() != TaskStatus.DONE) {
          NoticeKind waiting = t.status() == TaskStatus.AWAITING_APPROVAL ? NoticeKind.DECISION
              : t.status() == TaskStatus.AWAITING_PUSH ? NoticeKind.PUSH : null;
          items.put(p.name() + "/" + t.id(), new TraySnapshot.Item(p.name(), t.id(), waiting,
              activity.questions().getOrDefault(t.id(), 0), activity.failed().contains(t.id())));
        }
      }
    }
    return new TraySnapshot(items);
  }

  private TrayView view(TraySnapshot s, int running, LocalDateTime now) {
    List<TrayView.Waiting> rows = new ArrayList<>();
    boolean error = syncStates.values().stream().anyMatch(st -> st.state() == State.ERROR || st.state() == State.CONFLICT);
    for (TraySnapshot.Item item : s.items().values()) {
      error |= item.failed();
      if (item.waitsForPmo()) {
        rows.add(new TrayView.Waiting(Popup.clean("[" + item.project() + "] " + item.ticket() + " · " + item.reason(), masker),
            Notice.path(item.project(), item.ticket())));
      }
    }
    List<String> names = projects.list().stream().map(Project::name).toList();
    return new TrayView(rows.size(), List.copyOf(rows.subList(0, Math.min(rows.size(), MAX_MENU_ROWS))), running, error, names,
        names.stream().anyMatch(sync::enabled), NotificationPolicy.snoozed(settings.read(), now), shortcut.enabled(), shortcut.supported());
  }

  // ---------------------------------------------------------------- events (FR-64)

  @EventListener
  public void onTaskChanged(TaskChanged e) {
    dirty = true;
  }

  @EventListener
  public void onAgent(AgentProgress e) {
    if (!"running".equals(e.state())) {
      dirty = true;
    }
  }

  @EventListener
  public void onGateFailed(GateFailed e) {
    if (active) {
      events.add(new Notice(NoticeKind.FAILURE, e.project(), e.taskId(), "Quality gate không đạt"));
    }
  }

  /** Only a new conflict or a lost lock pops up; a finished sync just refreshes the icon. */
  @EventListener
  public void onSync(SyncChanged e) {
    Status now = e.status();
    Status before = syncStates.put(now.project(), now);
    dirty = true;
    if (!active) {
      return;
    }
    if (now.conflicts().size() > (before == null ? 0 : before.conflicts().size())) {
      events.add(new Notice(NoticeKind.SYNC, now.project(), now.conflicts().get(0).ticket(), "Xung đột đồng bộ, cần bạn xử lý"));
    }
    if (now.lockChoice() && (before == null || !before.lockChoice())) {
      events.add(new Notice(NoticeKind.SYNC, now.project(), null, "Máy khác đang giữ quyền, máy này chỉ xem"));
    }
  }

  // ---------------------------------------------------------------- browser tabs and settings

  public void reportFocus(String tab, String project, String ticket) {
    focus.report(tab, project, ticket == null || ticket.isBlank() ? null : ticket, System.currentTimeMillis());
  }

  public SettingsView settingsView() {
    NotificationSettings s = settings.read();
    return new SettingsView(active, s, NotificationPolicy.snoozed(s, LocalDateTime.now()), shortcut.enabled(), shortcut.supported());
  }

  public SettingsView saveSettings(NotificationSettings changed) {
    settings.write(changed);
    dirty = true;
    return settingsView();
  }

  // ---------------------------------------------------------------- the icon and its menu

  @Override
  public void open(String path) {
    BrowserOpener.open(loginUrl(path));
  }

  /** A fresh one-time code per click; a path with a query keeps it. */
  public String loginUrl(String path) {
    return "http://localhost:" + port() + path + (path.contains("?") ? "&" : "?") + "once=" + links.issue();
  }

  private int port() {
    return Integer.parseInt(env.getProperty("local.server.port", env.getProperty("server.port", "7070")));
  }

  @Override
  public void snoozeOneHour() {
    snooze(LocalDateTime.now().plusHours(1));
  }

  @Override
  public void snoozeUntilMorning() {
    snooze(NotificationPolicy.nextSevenAm(LocalDateTime.now()));
  }

  @Override
  public void resumeNotifications() {
    snooze(null);
  }

  private void snooze(LocalDateTime until) {
    background("devmap-tray-snooze", () -> saveSettings(settings.read().withSnooze(until)));
  }

  @Override
  public void syncNow() {
    background("devmap-tray-sync", () -> projects.list().stream().map(Project::name).filter(sync::enabled).forEach(sync::syncNow));
  }

  @Override
  public void setAutostart(boolean on) {
    background("devmap-tray-autostart", () -> {
      try {
        shortcut.set(on, port());
      } catch (IOException e) {
        LOG.warn("Không đổi được Khởi động cùng Windows: {}", e.getMessage());
      }
      dirty = true;
    });
  }

  @Override
  public int runningAgents() {
    return agents.runningCount();
  }

  /** FR-67, the PMO already confirmed: free the sync locks (bounded: no network must not block quitting), stop child processes, leave. */
  @Override
  public void exit() {
    background("devmap-tray-exit", () -> {
      Thread release = new Thread(sync::releaseLocks, "devmap-release-locks");
      release.setDaemon(true);
      release.start();
      try {
        release.join(TimeUnit.SECONDS.toMillis(RELEASE_LOCKS_SECONDS));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      ProcessHandle.current().descendants().forEach(ProcessHandle::destroy);
      stop();
      onExit.run();
    });
  }

  private static void background(String name, Runnable work) {
    Thread t = new Thread(() -> {
      try {
        work.run();
      } catch (RuntimeException e) {
        LOG.warn("{}: {}", name, e.toString());
      }
    }, name);
    t.setDaemon(true);
    t.start();
  }

  @PreDestroy
  public synchronized void stop() {
    if (ticker != null) {
      ticker.shutdownNow();
      ticker = null;
    }
    if (active) {
      active = false;
      notifier.stop();
    }
  }
}
