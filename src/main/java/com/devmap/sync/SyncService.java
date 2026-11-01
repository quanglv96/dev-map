package com.devmap.sync;

import com.devmap.config.Project;
import com.devmap.config.ProjectService;
import com.devmap.config.Workspace;
import com.devmap.config.WorkspaceRepository;
import com.devmap.config.Yaml;
import com.devmap.secret.SecretFiles;
import com.devmap.secret.SecretMasker;
import com.devmap.secret.SecretsConfig;
import com.devmap.sync.DataRepo.Change;
import com.devmap.sync.DataRepo.Lock;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * FR-46 → FR-54, FR-56, FR-57: each project's data folder is its own private git repo (own remote, lock and auto push, see
 * D-53), shared by the PMO's machines. A background thread (started by `devmap serve` only) commits changes once they settle,
 * pulls with rebase, pushes, and keeps the soft lock of every synced project. Every git operation is synchronized on this
 * service, so the tick and the PMO's buttons never run git at the same time.
 */
@Service
public class SyncService {

  /** FR-56, in the order the top bar shows them. */
  public enum State { OFF, SYNCED, SYNCING, PENDING, ERROR, CONFLICT, LOCKED }

  /**
   * A ticket file kept twice after a conflict: file and conflictFile are relative to the project folder; current = what is in the
   * file, other = this or another machine's version.
   */
  public record Conflict(String project, String ticket, String file, String conflictFile, String machine, String current,
      String other) {
  }

  /**
   * lockChoice: another machine holds the lock and the PMO has not chosen yet (Chỉ xem / Chiếm quyền). confirmPush: auto_push is
   * off and commits wait for the PMO. blocked: file:line (kind) of values that look secret, nothing committed.
   */
  public record Status(String project, State state, String machine, String dataDir, String remote, boolean autoPush,
      boolean readOnly, boolean lockChoice, Lock lock, int ahead, int dirty, boolean confirmPush, String lastSync, String message,
      List<String> blocked, List<Conflict> conflicts, String warning) {
  }

  /** Sent over SSE ("sync") to the tabs of status.project and the home page; pulled = new data arrived, refresh what is shown. */
  public record SyncChanged(Status status, boolean pulled) {
  }

  /** Result of init-sync / clone. */
  public record Setup(Status status, String warning) {
  }

  static final Duration LOCK_TTL = Duration.ofMinutes(10);
  static final Duration HEARTBEAT = Duration.ofSeconds(60);
  private static final long TICK_SECONDS = 3;
  private static final Logger LOG = LoggerFactory.getLogger(SyncService.class);
  private static final Pattern MASKED = Pattern.compile("\\[MASKED_([A-Z]+)\\]");
  private static final Pattern CREDENTIALS_IN_URL = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*://[^/@]*:[^/@]*@.*");
  private static final Pattern TASK_DIR = Pattern.compile("tasks/([^/]+)/(.+)");
  private static final long MAX_SCAN_BYTES = 2_000_000;

  private final WorkspaceRepository workspaces;
  private final ProjectService projects;
  private final SecretMasker masker;
  private final DataRepo data;
  private final ApplicationEventPublisher events;
  private final Map<String, ProjectSync> syncs = new ConcurrentHashMap<>();
  private Clock clock = Clock.systemUTC();
  private ScheduledExecutorService ticker;

  public SyncService(WorkspaceRepository workspaces, ProjectService projects, SecretMasker masker, DataRepo data,
      ApplicationEventPublisher events) {
    this.workspaces = workspaces;
    this.projects = projects;
    this.masker = masker;
    this.data = data;
    this.events = events;
  }

  void setClock(Clock clock) {
    this.clock = clock;
  }

  /** `devmap serve`: pull first, take the lock, then a tick every 3 s. CLI commands never start it. */
  public synchronized void start() {
    if (ticker == null) {
      ticker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "devmap-sync");
        t.setDaemon(true);
        return t;
      });
      ticker.scheduleWithFixedDelay(this::tick, 0, TICK_SECONDS, TimeUnit.SECONDS);
    }
  }

  /**
   * FR-67, quitting from the tray: no more ticks, and every lock this machine holds is removed from the data repo so another machine
   * need not wait out the 10 minutes. One project failing (no network) leaves its lock to expire as usual.
   */
  public synchronized void releaseLocks() {
    if (ticker != null) {
      ticker.shutdown();
    }
    for (Workspace w : workspaces.findAll()) {
      if (enabled(w.name())) {
        try {
          of(w.name()).releaseLock();
        } catch (RuntimeException e) {
          LOG.warn("Chưa nhả được khoá của {}: {}", w.name(), e.getMessage());
        }
      }
    }
  }

  /** One background step for every synced project. */
  synchronized void tick() {
    for (Workspace w : workspaces.findAll()) {
      if (enabled(w.name())) {
        of(w.name()).tick();
      }
    }
  }

  public boolean enabled(String project) {
    Path dir = dirOrNull(project);
    return dir != null && data.isRepo(dir);
  }

  public boolean readOnly(String project) {
    ProjectSync s = syncs.get(project);
    return s != null && s.readOnly;
  }

  public String machine() {
    return workspaces.machine();
  }

  // ---------------------------------------------------------------- setup (FR-46)

  /** `devmap data init-sync`: the project's data folder becomes a git repo with its own remote. */
  public synchronized Setup initSync(String project, String remote) {
    requireSafeRemote(remote);
    Path root = dir(project);
    data.init(root, remote);
    ProjectSync s = of(project);
    s.commitPending(true);
    s.pull();
    s.push(true);
    s.warning = publicWarning(remote);
    s.publishIfChanged(false);
    return new Setup(s.status(), s.warning);
  }

  /** `devmap data clone`: a new machine gets one project's data repo into dataRoot/<project> and registers it. */
  public synchronized Setup cloneProject(String remote, Path dataRoot) {
    requireSafeRemote(remote);
    if (dataRoot == null || dataRoot.toString().isBlank()) {
      throw new IllegalArgumentException("Chọn folder dữ liệu để clone dự án vào");
    }
    Path root = dataRoot.toAbsolutePath().normalize();
    Path tmp = root.resolve(".clone-" + System.currentTimeMillis());
    data.cloneTo(remote, tmp);
    String name;
    try {
      name = clonedProject(remote, tmp);
      if (Files.exists(root.resolve(name))) {
        throw new IllegalArgumentException("Đã có folder " + root.resolve(name) + "; không ghi đè");
      }
      if (nestedProject(tmp, name)) {
        data.flatten(tmp, name, machine());
      }
      Files.move(tmp, root.resolve(name));
    } catch (IOException e) {
      DataRepo.deleteTree(tmp);
      throw new UncheckedIOException(e);
    } catch (RuntimeException e) {
      DataRepo.deleteTree(tmp);
      throw e;
    }
    String oldDir = registerClone(name, root);
    if (workspaces.dataDir() == null) {
      workspaces.setDataDir(slash(root));
    }
    syncs.remove(name); // state of the previous folder
    ProjectSync s = of(name);
    s.commitPending(true);
    s.lastSync = Instant.now(clock);
    s.refresh();
    s.warning = joinWarnings(oldDir == null ? null : "Dự án " + name + " chuyển sang folder vừa clone; dữ liệu cũ vẫn ở " + oldDir
        + " nhưng không còn được dùng", publicWarning(remote));
    s.publishIfChanged(true);
    return new Setup(s.status(), s.warning);
  }

  /** Each machine keeps the data where it likes: a project known here under another folder now points at the clone. */
  private String registerClone(String name, Path root) {
    Workspace known = workspaces.findAll().stream().filter(w -> w.name().equals(name)).findFirst().orElse(null);
    if (known == null) {
      projects.create(name, root, null, List.of());
      return null;
    }
    workspaces.moveProject(name, slash(root.resolve(name)));
    return Files.exists(Path.of(known.dataDir())) ? known.dataDir() : null;
  }

  /** The project in the repo root, else the only one in a sub-folder (a repo pushed under D-52, when the data root was the repo). */
  private String clonedProject(String remote, Path repo) throws IOException {
    String name = projectIn(repo);
    if (name != null) {
      return name;
    }
    try (Stream<Path> children = Files.list(repo)) {
      List<String> nested = children.filter(Files::isDirectory).map(SyncService::projectIn).filter(Objects::nonNull).sorted().toList();
      if (nested.size() > 1) {
        throw new IllegalArgumentException("Repo " + remote + " chứa nhiều dự án (" + String.join(", ", nested)
            + "); mỗi dự án cần một repo dữ liệu riêng");
      }
      if (nested.isEmpty()) {
        throw new IllegalArgumentException("Repo " + remote + " không phải dữ liệu một dự án DevMap (thiếu config.yml)");
      }
      return nested.get(0);
    }
  }

  /** The project named by dir/config.yml, null when there is none. */
  private static String projectIn(Path dir) {
    Path config = dir.resolve("config.yml");
    String name = Files.isRegularFile(config) ? Yaml.readTree(config).path("project").asText(null) : null;
    return name == null || name.isBlank() ? null : name;
  }

  private static boolean nestedProject(Path root, String project) {
    return project.equals(projectIn(root.resolve(project)));
  }

  private static String joinWarnings(String first, String second) {
    return first == null ? second : second == null ? first : first + ". " + second;
  }

  public synchronized void setAutoPush(String project, boolean on) {
    workspaces.setAutoPush(project, on);
    of(project).publishIfChanged(false);
  }

  /** The token comes from the OS credential store via git, never from the URL (it would end up in .git/config). */
  static void requireSafeRemote(String remote) {
    if (remote == null || remote.isBlank() || remote.startsWith("-")) {
      throw new IllegalArgumentException("Chưa nhập URL remote của repo dữ liệu");
    }
    if (CREDENTIALS_IN_URL.matcher(remote.strip()).matches()) {
      throw new IllegalArgumentException("Không đưa mật khẩu/token vào URL remote; git credential manager của máy sẽ hỏi và giữ token");
    }
  }

  // ---------------------------------------------------------------- per-project actions

  /** "Đồng bộ ngay" / `devmap sync`: commit what changed, pull, push. */
  public synchronized Status syncNow(String project) {
    return of(requireEnabled(project)).syncNow();
  }

  /** Pull before a project page opens; skipped when sync is off or the last pull is fresh. */
  public synchronized Status pullIfStale(String project) {
    ProjectSync s = of(project);
    if (enabled(project) && (s.lastSync == null || s.lastSync.plusSeconds(15).isBefore(Instant.now(clock)))) {
      s.guarded(s::pull);
    }
    return s.status();
  }

  /** The PMO's confirmation when auto_push is off (FR-49). */
  public synchronized Status confirmPush(String project) {
    ProjectSync s = of(requireEnabled(project));
    s.guarded(() -> s.push(true));
    return s.status();
  }

  /** "Chiếm quyền": this machine writes the lock and pushes it right away; the other one notices on its next heartbeat. */
  public synchronized Status takeOver(String project) {
    return of(requireEnabled(project)).takeOver();
  }

  /** "Chỉ xem": stays read-only, stops asking. */
  public synchronized Status viewOnly(String project) {
    ProjectSync s = of(project);
    s.lockOpened = true;
    s.readOnly = true;
    s.lockChoice = false;
    s.publishIfChanged(false);
    return s.status();
  }

  /** The PMO chose or merged: content goes to the original file (masked), the conflict copy is deleted. */
  public synchronized Status resolve(String project, String conflictFile, String content) {
    return of(requireEnabled(project)).resolve(conflictFile, content);
  }

  public List<Conflict> conflicts(String project) {
    ProjectSync s = syncs.get(project);
    return s == null ? List.of() : s.conflicts;
  }

  synchronized boolean commitPending(String project, boolean force) {
    return of(project).commitPending(force);
  }

  synchronized void heartbeat(String project) {
    of(project).heartbeat();
  }

  // ---------------------------------------------------------------- export / import (FR-57)

  public void export(String project, OutputStream out) {
    data.exportZip(Path.of(projects.get(project).dataDir()), out);
  }

  /** Unpacks a project zip into dataRoot (default: the data root of the first project) and registers it. */
  public synchronized Project importZip(InputStream in, Path dataRoot) {
    Path root = dataRoot != null ? dataRoot : defaultDataRoot();
    if (root == null) {
      throw new IllegalArgumentException("Chọn folder dữ liệu để nhập vào");
    }
    String name = data.importZip(in, root.toAbsolutePath().normalize());
    if (workspaces.findAll().stream().map(Workspace::name).noneMatch(name::equals)) {
      projects.create(name, root.toAbsolutePath().normalize(), null, List.of());
    }
    return projects.get(name);
  }

  private Path defaultDataRoot() {
    if (workspaces.dataDir() != null) {
      return Path.of(workspaces.dataDir());
    }
    return workspaces.findAll().stream().findFirst().map(w -> Path.of(w.dataDir()).getParent()).orElse(null);
  }

  // ---------------------------------------------------------------- status (FR-56)

  public Status status(String project) {
    return of(project).status();
  }

  /** Home page: one status per registered project. */
  public List<Status> statuses() {
    return workspaces.findAll().stream().map(w -> status(w.name())).toList();
  }

  // ---------------------------------------------------------------- helpers

  private ProjectSync of(String project) {
    return syncs.computeIfAbsent(project, ProjectSync::new);
  }

  private Path dirOrNull(String project) {
    return workspaces.findAll().stream().filter(w -> w.name().equals(project)).findFirst().map(w -> Path.of(w.dataDir()))
        .orElse(null);
  }

  private Path dir(String project) {
    Path d = dirOrNull(project);
    if (d == null) {
      throw new NoSuchElementException("Không có dự án " + project);
    }
    return d;
  }

  private String requireEnabled(String project) {
    if (!enabled(project)) {
      throw new IllegalStateException("Dự án " + project + " chưa bật đồng bộ: chạy `devmap data init-sync " + project
          + " <remote>` hoặc bật ở Cấu hình dự án");
    }
    return project;
  }

  /** data(shop/NYX-231): cập nhật analysis.md · data(shop): … · data(shop/lock): heartbeat may-a. Paths relative to the project. */
  String message(String project, List<Change> changes) {
    if (changes.stream().allMatch(c -> c.path().equals(DataRepo.LOCK))) {
      return "data(" + project + "/lock): heartbeat " + machine();
    }
    Path root = dir(project);
    List<Change> real = changes.stream().filter(c -> !c.path().equals(DataRepo.LOCK)).toList();
    Set<String> tickets = new LinkedHashSet<>();
    for (Change c : real) {
      Matcher m = TASK_DIR.matcher(c.path());
      tickets.add(m.matches() ? ticketId(root, m) : "");
    }
    String scope = tickets.size() == 1 && !tickets.iterator().next().isEmpty() ? project + "/" + tickets.iterator().next() : project;
    List<String> what = real.stream().map(c -> verb(c) + " " + shortName(c.path())).distinct().toList();
    String action = what.size() <= 3 ? String.join(", ", what) : String.join(", ", what.subList(0, 3)) + " +" + (what.size() - 3);
    return "data(" + scope + "): " + action;
  }

  private static String verb(Change c) {
    return c.deleted() ? "xoá" : c.added() ? "thêm" : "cập nhật";
  }

  /** analysis.md for ticket files, the path in the project folder otherwise. */
  private static String shortName(String path) {
    Matcher m = TASK_DIR.matcher(path);
    return m.matches() ? m.group(2) : path;
  }

  /** The id in task.md of the ticket folder; the folder name when task.md cannot be read. */
  private static String ticketId(Path root, Matcher m) {
    Path task = root.resolve("tasks").resolve(m.group(1)).resolve("task.md");
    try {
      if (Files.exists(task)) {
        for (String line : Files.readAllLines(task, StandardCharsets.UTF_8)) {
          if (line.startsWith("id: ")) {
            return line.substring(4).strip().replace("\"", "").replace("'", "");
          }
        }
      }
    } catch (IOException e) {
      // fall back to the folder name
    }
    return m.group(1);
  }

  private static String newLabel(String before, String after) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    MASKED.matcher(after).results().forEach(m -> counts.merge(m.group(1), 1, Integer::sum));
    MASKED.matcher(before).results().forEach(m -> counts.merge(m.group(1), -1, Integer::sum));
    return counts.entrySet().stream().filter(e -> e.getValue() > 0).map(Map.Entry::getKey).findFirst().orElse("SECRET");
  }

  private SecretsConfig secrets(String project) {
    try {
      Project p = projects.get(project);
      return p.config() == null ? null : p.config().secrets();
    } catch (NoSuchElementException e) {
      return null;
    }
  }

  private static String read(Path f) {
    try {
      return Files.exists(f) ? Files.readString(f, StandardCharsets.UTF_8) : "";
    } catch (IOException e) {
      return "";
    }
  }

  private static String slash(Path p) {
    return p.toString().replace('\\', '/');
  }

  /** Warns (never blocks) when GitHub/GitLab says anyone can read the data repo. Unknown host or no network = no warning. */
  static String publicWarning(String remote) {
    String n = com.devmap.config.RepoConfig.normalize(remote);
    String[] parts = n.split("/", 2);
    if (parts.length < 2) {
      return null;
    }
    String api = switch (parts[0]) {
      case "github.com" -> "https://api.github.com/repos/" + parts[1];
      case "gitlab.com" -> "https://gitlab.com/api/v4/projects/" + URLEncoder.encode(parts[1], StandardCharsets.UTF_8);
      default -> null;
    };
    if (api == null) {
      return null;
    }
    try {
      HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
      HttpResponse<Void> res = http.send(HttpRequest.newBuilder(URI.create(api)).timeout(Duration.ofSeconds(5)).GET().build(),
          HttpResponse.BodyHandlers.discarding());
      return res.statusCode() == 200 ? "Repo dữ liệu " + n + " đang PUBLIC: ai cũng đọc được ticket và tài liệu; nên chuyển sang private"
          : null;
    } catch (IOException e) {
      return null;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    }
  }

  boolean expired(Lock lock) {
    try {
      return Instant.parse(lock.heartbeat()).plus(LOCK_TTL).isBefore(Instant.now(clock));
    } catch (RuntimeException e) {
      return true;
    }
  }

  /** Sync state of one project; only touched while holding the service's monitor. */
  private final class ProjectSync {

    final String name;
    volatile boolean syncing;
    volatile boolean readOnly;
    volatile boolean lockChoice;
    volatile boolean lockOpened;
    volatile boolean offline;
    volatile boolean confirmPush;
    volatile String lastError;
    volatile String message;
    volatile String lockMessage;
    volatile String warning;
    volatile Instant lastSync;
    volatile Instant lastHeartbeat = Instant.EPOCH;
    volatile int ahead;
    volatile int dirty;
    volatile List<String> blocked = List.of();
    volatile List<Conflict> conflicts = List.of();
    private String settledFingerprint = "";
    private String blockedFingerprint;
    private Status published;

    ProjectSync(String name) {
      this.name = name;
    }

    Path root() {
      return dir(name);
    }

    /** Open the lock once, commit settled changes, heartbeat (pull + lock) every 60 s. */
    void tick() {
      try {
        if (!lockOpened) {
          openLock();
        }
        if (!readOnly && commitPending(false) && pushAllowed()) {
          push(false);
        }
        if (!Instant.now(clock).isBefore(lastHeartbeat.plus(HEARTBEAT))) {
          heartbeat();
        }
      } catch (RuntimeException e) {
        fail(e);
      } finally {
        publishIfChanged(false);
      }
    }

    void guarded(Runnable step) {
      try {
        step.run();
      } catch (RuntimeException e) {
        fail(e);
      }
      publishIfChanged(false);
    }

    Status syncNow() {
      guarded(() -> {
        if (!readOnly) {
          commitPending(true);
        }
        pull();
        if (!readOnly && pushAllowed()) {
          push(false);
        }
      });
      return status();
    }

    /**
     * Commits the working tree once it has settled (same files, sizes and times as on the previous tick), or right away when
     * forced. Refused when a changed file holds something that looks secret. Returns true when a commit was made.
     */
    boolean commitPending(boolean force) {
      Path root = root();
      List<Change> changes = data.changes(root);
      dirty = changes.size();
      if (changes.isEmpty()) {
        blocked = List.of();
        settledFingerprint = "";
        return false;
      }
      String fp = data.fingerprint(root, changes);
      boolean settled = fp.equals(settledFingerprint);
      settledFingerprint = fp;
      if (!force && !settled || fp.equals(blockedFingerprint)) {
        return false;
      }
      List<String> findings = scan(root, changes);
      if (!findings.isEmpty()) {
        blocked = findings;
        blockedFingerprint = fp;
        // values never reach the log: file and line only
        LOG.warn("Chặn commit dữ liệu {}: {} chỗ giống bí mật chưa che ({})", name, findings.size(), String.join(", ", findings));
        return false;
      }
      blocked = List.of();
      blockedFingerprint = null;
      data.commit(root, message(name, changes));
      dirty = 0;
      ahead = data.ahead(root);
      return true;
    }

    void pull() {
      Path root = root();
      if (data.remote(root) == null) {
        return;
      }
      syncing = true;
      publishIfChanged(false);
      try {
        String before = data.head(root);
        String err = data.fetch(root);
        offline = err != null;
        if (offline) {
          message = "Không kết nối được remote, làm việc cục bộ và sẽ thử lại: " + err;
          ahead = data.ahead(root);
          return;
        }
        List<String> created = data.hasUpstream(root) && data.hasCommits(root) ? data.rebase(root, machine()) : List.of();
        lastError = null;
        message = created.isEmpty() ? null : "Có xung đột, đã giữ cả hai bản: " + String.join(", ", created);
        lastSync = Instant.now(clock);
        flattenNested();
        boolean pulled = !before.equals(data.head(root));
        refresh();
        publishIfChanged(pulled);
      } finally {
        syncing = false;
      }
    }

    /** The repo brought the project in a sub-folder (pushed under D-52): move it to the root, unless another machine holds the lock. */
    void flattenNested() {
      Path root = root();
      Lock lock = data.readLock(root);
      boolean mayWrite = !readOnly && (lock == null || mine(lock) || expired(lock));
      if (mayWrite && nestedProject(root, name) && data.flatten(root, name, machine())) {
        LOG.info("Đã chuyển dữ liệu {} từ {}/ lên gốc repo", name, name);
        commitPending(true);
      }
    }

    boolean pushAllowed() {
      return workspaces.autoPush(name);
    }

    void push(boolean confirmed) {
      Path root = root();
      ahead = data.ahead(root);
      if (data.remote(root) == null || ahead == 0) {
        confirmPush = false;
        return;
      }
      if (!confirmed && !pushAllowed()) {
        confirmPush = true;
        return;
      }
      try {
        data.push(root);
      } catch (IllegalStateException first) {
        // someone pushed in between: bring their commits in, then push once more
        pull();
        if (offline) {
          return;
        }
        data.push(root);
      }
      offline = false;
      confirmPush = false;
      lastError = null;
      lastSync = Instant.now(clock);
      ahead = data.ahead(root);
    }

    // -------------------------------------------------------------- soft lock (FR-53)

    void openLock() {
      lockOpened = true;
      pull();
      Lock lock = data.readLock(root());
      if (lock != null && !mine(lock) && !expired(lock)) {
        readOnly = true;
        lockChoice = true;
        lockMessage = "Đang mở ở máy " + lock.machine();
        lastHeartbeat = Instant.now(clock);
        return;
      }
      writeLock(Instant.now(clock));
    }

    /** Every 60 s: pull; a newer lock of another machine = it took over, so this one turns read-only; else refresh ours. */
    void heartbeat() {
      lastHeartbeat = Instant.now(clock);
      pull();
      if (readOnly) {
        return;
      }
      Lock lock = data.readLock(root());
      if (lock != null && !mine(lock) && !expired(lock)) {
        readOnly = true;
        lockChoice = true;
        lockMessage = "Máy " + lock.machine() + " đã chiếm quyền lúc " + lock.since() + "; máy này chuyển sang chỉ xem";
        return;
      }
      writeLock(lock != null && mine(lock) ? Instant.parse(lock.since()) : Instant.now(clock));
      if (pushAllowed() && data.ahead(root()) > 0) {
        push(false); // commits made while offline
      }
    }

    Status takeOver() {
      lockOpened = true;
      try {
        pull();
      } catch (RuntimeException e) {
        fail(e);
      }
      readOnly = false;
      lockChoice = false;
      lockMessage = null;
      writeLock(Instant.now(clock));
      flattenNested();
      if (pushAllowed() && data.ahead(root()) > 0) {
        push(false);
      }
      publishIfChanged(true);
      return status();
    }

    void writeLock(Instant since) {
      Instant now = Instant.now(clock);
      data.writeLock(root(), new Lock(machine(), since.toString(), now.toString()));
      lastHeartbeat = now;
      if (commitPending(true) && pushAllowed()) {
        push(false);
      }
    }

    void releaseLock() {
      Lock lock = data.readLock(root());
      if (readOnly || lock == null || !mine(lock)) {
        return;
      }
      data.deleteLock(root());
      if (commitPending(true) && pushAllowed()) {
        push(false);
      }
    }

    boolean mine(Lock lock) {
      return machine().equals(lock.machine());
    }

    // -------------------------------------------------------------- conflicts (FR-54)

    Status resolve(String conflictFile, String content) {
      Path root = root();
      String original = DataRepo.original(conflictFile);
      Path copy = root.resolve(conflictFile).normalize();
      if (original == null || !copy.startsWith(root) || !Files.isRegularFile(copy)) {
        throw new IllegalArgumentException("Không có file xung đột: " + conflictFile);
      }
      requireValidYaml(original, content);
      try {
        Files.writeString(root.resolve(original), masker.mask(content == null ? "" : content, secrets(name)).text());
        Files.delete(copy);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      refresh();
      publishIfChanged(true);
      return status();
    }

    /** A broken merge of config.yml would stop the project from opening, and with it the page to repair the merge. */
    private void requireValidYaml(String file, String content) {
      if (!file.endsWith(".yml") && !file.endsWith(".yaml")) {
        return;
      }
      LoaderOptions options = new LoaderOptions();
      options.setAllowDuplicateKeys(false);
      try {
        new org.yaml.snakeyaml.Yaml(options).load(content == null ? "" : content); // the project's Yaml lets duplicate keys through
      } catch (YAMLException e) {
        throw new IllegalArgumentException("Bản ghép của " + file + " không phải YAML hợp lệ: " + e.getMessage(), e);
      }
    }

    void refresh() {
      Path root = root();
      List<Conflict> out = new ArrayList<>();
      for (String c : data.conflictFiles(root)) {
        String original = DataRepo.original(c);
        Matcher m = TASK_DIR.matcher(original);
        out.add(new Conflict(name, m.matches() ? ticketId(root, m) : null, original, c, DataRepo.conflictMachine(c),
            read(root.resolve(original)), read(root.resolve(c))));
      }
      conflicts = List.copyOf(out);
      ahead = data.ahead(root);
    }

    /** Security: every changed line is masked again; anything the masker would hide blocks the commit. */
    List<String> scan(Path root, List<Change> changes) {
      List<String> out = new ArrayList<>();
      SecretsConfig cfg = secrets(name);
      for (Change c : changes) {
        Path f = root.resolve(c.path());
        if (c.deleted() || !Files.isRegularFile(f)) {
          continue;
        }
        if (SecretFiles.isSecret(c.path())) {
          out.add(c.path() + " (file bí mật)");
          continue;
        }
        try {
          if (Files.size(f) > MAX_SCAN_BYTES) {
            continue;
          }
          List<String> lines = Files.readString(f, StandardCharsets.UTF_8).lines().toList();
          for (int i = 0; i < lines.size(); i++) {
            SecretMasker.Result r = masker.mask(lines.get(i), cfg);
            if (r.count() > 0) {
              out.add(c.path() + ":" + (i + 1) + " (" + newLabel(lines.get(i), r.text()) + ")");
            }
          }
        } catch (IOException | UncheckedIOException e) {
          // not UTF-8 text (an image a PMO dropped in): nothing the masker could read either
        }
      }
      return out;
    }

    Status status() {
      Path dir = dirOrNull(name);
      boolean on = dir != null && data.isRepo(dir);
      State state = !on ? State.OFF
          : readOnly ? State.LOCKED
          : syncing ? State.SYNCING
          : !conflicts.isEmpty() ? State.CONFLICT
          : lastError != null || !blocked.isEmpty() || offline && ahead + dirty == 0 ? State.ERROR
          : ahead + dirty > 0 ? State.PENDING
          : State.SYNCED;
      return new Status(name, state, machine(), dir == null ? null : slash(dir), on ? data.remote(dir) : null,
          workspaces.autoPush(name), readOnly, lockChoice, on ? data.readLock(dir) : null, ahead, dirty, confirmPush,
          lastSync == null ? null : lastSync.toString(), lastError != null ? lastError : lockMessage != null ? lockMessage : message,
          blocked, conflicts, warning);
    }

    void publishIfChanged(boolean pulled) {
      Status s = status();
      Status last = published;
      if (pulled || last == null || last.state() != s.state() || !Objects.equals(last.message(), s.message())
          || last.ahead() != s.ahead() || last.dirty() != s.dirty() || last.readOnly() != s.readOnly()
          || last.lockChoice() != s.lockChoice() || !last.blocked().equals(s.blocked())
          || last.conflicts().size() != s.conflicts().size() || last.confirmPush() != s.confirmPush()
          || last.autoPush() != s.autoPush()) {
        published = s;
        events.publishEvent(new SyncChanged(s, pulled));
      }
    }

    void fail(RuntimeException e) {
      lastError = String.valueOf(e.getMessage());
      LOG.warn("Đồng bộ dữ liệu {} lỗi: {}", name, lastError);
    }
  }
}
