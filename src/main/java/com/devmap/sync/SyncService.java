package com.devmap.sync;

import com.devmap.config.Project;
import com.devmap.config.ProjectService;
import com.devmap.config.Workspace;
import com.devmap.config.WorkspaceRepository;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * FR-46 → FR-54, FR-56, FR-57: keeps the DevMap data root in a private git repo shared by the PMO's machines. A background
 * thread (started by `devmap serve` only) commits changes once they settle, pulls with rebase, pushes, and keeps the soft lock.
 * Every git operation is synchronized on this service, so the tick and the PMO's buttons never run git at the same time.
 */
@Service
public class SyncService {

  /** FR-56, in the order the top bar shows them. */
  public enum State { OFF, SYNCED, SYNCING, PENDING, ERROR, CONFLICT, LOCKED }

  /** A ticket file kept twice after a conflict: current = what is in the file, other = this or another machine's version. */
  public record Conflict(String project, String ticket, String file, String conflictFile, String machine, String current,
      String other) {
  }

  /**
   * lockChoice: another machine holds the lock and the PMO has not chosen yet (Chỉ xem / Chiếm quyền). confirmPush: auto_push is
   * off and commits wait for the PMO. blocked: file:line (kind) of values that look secret, nothing committed.
   */
  public record Status(State state, String machine, String dataDir, String remote, boolean autoPush, boolean readOnly,
      boolean lockChoice, Lock lock, int ahead, int dirty, boolean confirmPush, String lastSync, String message,
      List<String> blocked, List<Conflict> conflicts, String warning) {
  }

  /** Sent to every open tab over SSE ("sync"); pulled = new data arrived, refresh what is shown. */
  public record SyncChanged(Status status, boolean pulled) {
  }

  /** Result of init-sync / clone. */
  public record Setup(Status status, List<String> projects, String warning) {
  }

  static final Duration LOCK_TTL = Duration.ofMinutes(10);
  static final Duration HEARTBEAT = Duration.ofSeconds(60);
  private static final long TICK_SECONDS = 3;
  private static final Logger LOG = LoggerFactory.getLogger(SyncService.class);
  private static final Pattern MASKED = Pattern.compile("\\[MASKED_([A-Z]+)\\]");
  private static final Pattern CREDENTIALS_IN_URL = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*://[^/@]*:[^/@]*@.*");
  private static final Pattern TASK_DIR = Pattern.compile("([^/]+)/tasks/([^/]+)/(.+)");
  private static final long MAX_SCAN_BYTES = 2_000_000;

  private final WorkspaceRepository workspaces;
  private final ProjectService projects;
  private final SecretMasker masker;
  private final DataRepo data;
  private final ApplicationEventPublisher events;
  private Clock clock = Clock.systemUTC();
  private ScheduledExecutorService ticker;

  private volatile boolean syncing;
  private volatile boolean readOnly;
  private volatile boolean lockChoice;
  private volatile boolean lockOpened;
  private volatile boolean offline;
  private volatile boolean confirmPush;
  private volatile String lastError;
  private volatile String message;
  private volatile String lockMessage;
  private volatile String warning;
  private volatile Instant lastSync;
  private volatile Instant lastHeartbeat = Instant.EPOCH;
  private volatile int ahead;
  private volatile int dirty;
  private volatile List<String> blocked = List.of();
  private volatile List<Conflict> conflicts = List.of();
  private String settledFingerprint = "";
  private String blockedFingerprint;
  private Status published;

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

  /** One background step: open the lock once, commit settled changes, heartbeat (pull + lock) every 60 s. */
  synchronized void tick() {
    try {
      if (!enabled()) {
        return;
      }
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

  public boolean enabled() {
    String dir = workspaces.dataDir();
    return dir != null && data.isRepo(Path.of(dir));
  }

  public boolean readOnly() {
    return readOnly;
  }

  public String machine() {
    return workspaces.machine();
  }

  // ---------------------------------------------------------------- setup (FR-46)

  /** `devmap data init-sync`: the data root (dir, or the common parent of the projects' data folders) becomes a git repo. */
  public synchronized Setup initSync(String remote, Path dir) {
    requireSafeRemote(remote);
    Path root = (dir != null ? dir : inferRoot()).toAbsolutePath().normalize();
    if (!Files.isDirectory(root)) {
      throw new IllegalArgumentException("Không có folder " + root);
    }
    data.init(root, remote);
    workspaces.setDataDir(slash(root));
    commitPending(true);
    pull();
    push(true);
    warning = publicWarning(remote);
    publishIfChanged(false);
    return new Setup(status(), projects.registerFound(root), warning);
  }

  /** `devmap data clone`: a new machine gets the data repo and registers every project in it. */
  public synchronized Setup cloneData(String remote, Path target) {
    requireSafeRemote(remote);
    Path root = target.toAbsolutePath().normalize();
    try (var children = Files.exists(root) ? Files.list(root) : java.util.stream.Stream.<Path>empty()) {
      if (children.findAny().isPresent()) {
        throw new IllegalArgumentException("Folder " + root + " đã có nội dung; chọn folder trống hoặc chưa có");
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    data.cloneTo(remote, root);
    workspaces.setDataDir(slash(root));
    commitPending(true);
    List<String> added = projects.registerFound(root);
    lastSync = Instant.now(clock);
    refresh();
    warning = publicWarning(remote);
    publishIfChanged(true);
    return new Setup(status(), added, warning);
  }

  public synchronized void setAutoPush(boolean on) {
    workspaces.setAutoPush(on);
    publishIfChanged(false);
  }

  private Path inferRoot() {
    if (workspaces.dataDir() != null) {
      return Path.of(workspaces.dataDir());
    }
    Set<Path> parents = new LinkedHashSet<>();
    workspaces.findAll().forEach(w -> parents.add(Path.of(w.dataDir()).toAbsolutePath().normalize().getParent()));
    if (parents.size() != 1) {
      throw new IllegalArgumentException("Chọn folder dữ liệu gốc (chứa các folder dự án) để đồng bộ");
    }
    return parents.iterator().next();
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

  // ---------------------------------------------------------------- sync (FR-47, FR-48, FR-49)

  /** "Đồng bộ ngay" / `devmap sync`: commit what changed, pull, push. */
  public synchronized Status syncNow() {
    requireEnabled();
    try {
      if (!readOnly) {
        commitPending(true);
      }
      pull();
      if (!readOnly && pushAllowed()) {
        push(false);
      }
    } catch (RuntimeException e) {
      fail(e);
    }
    publishIfChanged(false);
    return status();
  }

  /** Pull before a project page opens; skipped when the last pull is fresh. */
  public synchronized Status pullIfStale() {
    if (enabled() && (lastSync == null || lastSync.plusSeconds(15).isBefore(Instant.now(clock)))) {
      try {
        pull();
      } catch (RuntimeException e) {
        fail(e);
      }
      publishIfChanged(false);
    }
    return status();
  }

  /** The PMO's confirmation when auto_push is off (FR-49). */
  public synchronized Status confirmPush() {
    requireEnabled();
    try {
      push(true);
    } catch (RuntimeException e) {
      fail(e);
    }
    publishIfChanged(false);
    return status();
  }

  /**
   * Commits the working tree once it has settled (same files, sizes and times as on the previous tick), or right away when forced.
   * Refused when a changed file holds something that looks secret. Returns true when a commit was made.
   */
  synchronized boolean commitPending(boolean force) {
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
      LOG.warn("Chặn commit dữ liệu: {} chỗ giống bí mật chưa che ({})", findings.size(), String.join(", ", findings));
      return false;
    }
    blocked = List.of();
    blockedFingerprint = null;
    data.commit(root, message(root, changes));
    dirty = 0;
    ahead = data.ahead(root);
    return true;
  }

  private void pull() {
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
      boolean pulled = !before.equals(data.head(root));
      if (pulled) {
        projects.registerFound(root);
      }
      refresh();
      publishIfChanged(pulled);
    } finally {
      syncing = false;
    }
  }

  private boolean pushAllowed() {
    return workspaces.autoPush();
  }

  private void push(boolean confirmed) {
    Path root = root();
    ahead = data.ahead(root);
    if (data.remote(root) == null || ahead == 0) {
      confirmPush = false;
      return;
    }
    if (!confirmed && !workspaces.autoPush()) {
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

  // ---------------------------------------------------------------- soft lock (FR-53)

  private void openLock() {
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
  synchronized void heartbeat() {
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

  /** "Chiếm quyền": this machine writes the lock and pushes it right away; the other one notices on its next heartbeat. */
  public synchronized Status takeOver() {
    requireEnabled();
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
    publishIfChanged(false);
    return status();
  }

  /** "Chỉ xem": stays read-only, stops asking. */
  public synchronized Status viewOnly() {
    lockOpened = true;
    readOnly = true;
    lockChoice = false;
    publishIfChanged(false);
    return status();
  }

  private void writeLock(Instant since) {
    Instant now = Instant.now(clock);
    data.writeLock(root(), new Lock(machine(), since.toString(), now.toString()));
    lastHeartbeat = now;
    if (commitPending(true) && pushAllowed()) {
      push(false);
    }
  }

  private boolean mine(Lock lock) {
    return machine().equals(lock.machine());
  }

  boolean expired(Lock lock) {
    try {
      return Instant.parse(lock.heartbeat()).plus(LOCK_TTL).isBefore(Instant.now(clock));
    } catch (RuntimeException e) {
      return true;
    }
  }

  // ---------------------------------------------------------------- conflicts (FR-54)

  /** The PMO chose or merged: content goes to the original file (masked), the conflict copy is deleted. */
  public synchronized Status resolve(String conflictFile, String content) {
    requireEnabled();
    Path root = root();
    String original = DataRepo.original(conflictFile);
    Path copy = root.resolve(conflictFile).normalize();
    if (original == null || !copy.startsWith(root) || !Files.isRegularFile(copy)) {
      throw new IllegalArgumentException("Không có file xung đột: " + conflictFile);
    }
    String project = conflictFile.contains("/") ? conflictFile.substring(0, conflictFile.indexOf('/')) : "";
    try {
      Files.writeString(root.resolve(original), masker.mask(content == null ? "" : content, secrets(project)).text());
      Files.delete(copy);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    refresh();
    publishIfChanged(true);
    return status();
  }

  private void refresh() {
    Path root = root();
    List<Conflict> out = new ArrayList<>();
    for (String c : data.conflictFiles(root)) {
      String original = DataRepo.original(c);
      Matcher m = TASK_DIR.matcher(original);
      out.add(new Conflict(c.contains("/") ? c.substring(0, c.indexOf('/')) : "", m.matches() ? ticketId(root, m) : null, original, c,
          DataRepo.conflictMachine(c), read(root.resolve(original)), read(root.resolve(c))));
    }
    conflicts = List.copyOf(out);
    ahead = data.ahead(root);
  }

  public List<Conflict> conflicts(String project) {
    return conflicts.stream().filter(c -> project == null || project.equals(c.project())).toList();
  }

  // ---------------------------------------------------------------- export / import (FR-57)

  public void export(String project, OutputStream out) {
    data.exportZip(Path.of(projects.get(project).dataDir()), out);
  }

  /** Unpacks a project zip into dataRoot (default: the synced data root) and registers it. */
  public synchronized Project importZip(InputStream in, Path dataRoot) {
    Path root = dataRoot != null ? dataRoot : workspaces.dataDir() != null ? Path.of(workspaces.dataDir()) : null;
    if (root == null) {
      throw new IllegalArgumentException("Chọn folder dữ liệu để nhập vào");
    }
    String name = data.importZip(in, root.toAbsolutePath().normalize());
    if (workspaces.findAll().stream().map(Workspace::name).noneMatch(name::equals)) {
      projects.create(name, root.toAbsolutePath().normalize(), null, List.of());
    }
    return projects.get(name);
  }

  // ---------------------------------------------------------------- status (FR-56)

  public Status status() {
    String dir = workspaces.dataDir();
    boolean on = enabled();
    State state = !on ? State.OFF
        : readOnly ? State.LOCKED
        : syncing ? State.SYNCING
        : !conflicts.isEmpty() ? State.CONFLICT
        : lastError != null || !blocked.isEmpty() || offline && ahead + dirty == 0 ? State.ERROR
        : ahead + dirty > 0 ? State.PENDING
        : State.SYNCED;
    return new Status(state, machine(), dir, on ? data.remote(Path.of(dir)) : null, workspaces.autoPush(), readOnly, lockChoice,
        on ? data.readLock(Path.of(dir)) : null, ahead, dirty, confirmPush, lastSync == null ? null : lastSync.toString(),
        lastError != null ? lastError : lockMessage != null ? lockMessage : message, blocked, conflicts, warning);
  }

  private void publishIfChanged(boolean pulled) {
    Status s = status();
    Status last = published;
    if (pulled || last == null || last.state() != s.state() || !Objects.equals(last.message(), s.message())
        || last.ahead() != s.ahead() || last.dirty() != s.dirty() || last.readOnly() != s.readOnly()
        || last.lockChoice() != s.lockChoice() || !last.blocked().equals(s.blocked()) || last.conflicts().size() != s.conflicts().size()
        || last.confirmPush() != s.confirmPush()) {
      published = s;
      events.publishEvent(new SyncChanged(s, pulled));
    }
  }

  private void fail(RuntimeException e) {
    lastError = String.valueOf(e.getMessage());
    LOG.warn("Đồng bộ dữ liệu lỗi: {}", lastError);
  }

  // ---------------------------------------------------------------- helpers

  private Path root() {
    return Path.of(workspaces.dataDir());
  }

  private void requireEnabled() {
    if (!enabled()) {
      throw new IllegalStateException("Chưa bật đồng bộ: chạy `devmap data init-sync <remote>` hoặc `devmap data clone <remote>`");
    }
  }

  /** Security: every changed line is masked again; anything the masker would hide blocks the commit. */
  List<String> scan(Path root, List<Change> changes) {
    List<String> out = new ArrayList<>();
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
        SecretsConfig cfg = secrets(c.path().contains("/") ? c.path().substring(0, c.path().indexOf('/')) : "");
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

  /** data(shop/NYX-231): cập nhật analysis.md · data(shop): … · data(lock): heartbeat may-a. */
  String message(Path root, List<Change> changes) {
    if (changes.stream().allMatch(c -> c.path().equals(DataRepo.LOCK))) {
      return "data(lock): heartbeat " + machine();
    }
    List<Change> real = changes.stream().filter(c -> !c.path().equals(DataRepo.LOCK)).toList();
    Set<String> scopes = new LinkedHashSet<>();
    for (Change c : real) {
      Matcher m = TASK_DIR.matcher(c.path());
      String top = c.path().contains("/") ? c.path().substring(0, c.path().indexOf('/')) : "devmap";
      scopes.add(m.matches() ? m.group(1) + "/" + ticketId(root, m) : top);
    }
    String scope = scopes.size() == 1 ? scopes.iterator().next()
        : scopes.stream().map(s -> s.contains("/") ? s.substring(0, s.indexOf('/')) : s).distinct().count() == 1
        ? scopes.iterator().next().split("/")[0] : "devmap";
    List<String> what = real.stream().map(c -> verb(c) + " " + shortName(c.path())).distinct().toList();
    String action = what.size() <= 3 ? String.join(", ", what) : String.join(", ", what.subList(0, 3)) + " +" + (what.size() - 3);
    return "data(" + scope + "): " + action;
  }

  private static String verb(Change c) {
    return c.deleted() ? "xoá" : c.added() ? "thêm" : "cập nhật";
  }

  /** analysis.md for ticket files, path below the project otherwise. */
  private static String shortName(String path) {
    Matcher m = TASK_DIR.matcher(path);
    return m.matches() ? m.group(3) : path.contains("/") ? path.substring(path.indexOf('/') + 1) : path;
  }

  /** The id in task.md of the ticket folder; the folder name when task.md cannot be read. */
  private static String ticketId(Path root, Matcher m) {
    Path task = root.resolve(m.group(1)).resolve("tasks").resolve(m.group(2)).resolve("task.md");
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
    return m.group(2);
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
}
