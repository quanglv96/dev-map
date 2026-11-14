package com.devmap.ticket;

import com.devmap.audit.AuditLog;
import com.devmap.config.Project;
import com.devmap.config.ProjectConfig;
import com.devmap.config.ProjectService;
import com.devmap.config.RepoConfig;
import com.devmap.config.ServiceInfo;
import com.devmap.git.GitService;
import com.devmap.secret.SecretMasker;
import com.devmap.ticket.TaskRepository.Stored;
import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * FR-05 → FR-10, FR-44: tickets of a project. Every text written to the data folder passes the secret masker first
 * (FR-36), and the two PMO gates are enforced here, not in the UI (NFR-03).
 */
@Service
public class TaskService {

  /** Who asks for an action. Only the PMO passes the gates. DEVMAP: documents the tool itself writes (tests.md, changes.md). */
  public enum Actor { PMO, SA, DEV, DEVMAP }

  public record Action(String to, boolean pmoGate) {
  }

  /** reapproval: plan.md version when the ticket waits for "chốt lại · v<n>" (FR-87), null for the first approval. */
  public record Detail(Task task, List<String> docs, List<Action> actions, Integer queuePosition, Integer reapproval) {
  }

  public record Created(Task task, int maskedCount) {
  }

  public record Doc(String name, String content, int versions, boolean editable, int maskedCount) {
  }

  public record ProjectOverview(String project, String color, List<Task> running, List<Task> pending) {
  }

  private static final Pattern CODE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,39}");
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

  private final ProjectService projects;
  private final TaskRepository repo;
  private final SecretMasker masker;
  private final RequestCleaner cleaner;
  private final ApplicationEventPublisher events;
  private final GitService git;

  public TaskService(ProjectService projects, TaskRepository repo, SecretMasker masker, RequestCleaner cleaner,
      ApplicationEventPublisher events, GitService git) {
    this.projects = projects;
    this.repo = repo;
    this.masker = masker;
    this.cleaner = cleaner;
    this.events = events;
    this.git = git;
  }

  public List<Task> list(String project) {
    return repo.list(dir(project)).stream().map(Stored::task)
        .sorted(Comparator.comparing(Task::created).reversed()).toList();
  }

  public Detail detail(String project, String id) {
    Stored s = stored(project, id);
    TaskStatus from = s.task().status();
    boolean before = implementedBefore(s);
    // DONE → ANALYSIS only through "Báo bug / Bổ sung logic"; approval goes to READY the first time, IMPLEMENT after (FR-86)
    List<Action> actions = from.next().stream().sorted()
        .filter(to -> from != TaskStatus.DONE)
        .filter(to -> from != TaskStatus.WAIT_APPROVAL || to == TaskStatus.ANALYSIS || (to == TaskStatus.IMPLEMENT) == before)
        .map(to -> new Action(to.code(), from.isPmoGate(to) || from.isReanalysis(to))).toList();
    int pos = repo.readQueue(dir(project)).indexOf(id);
    Integer re = from == TaskStatus.WAIT_APPROVAL && before ? repo.versionCount(s.folder(), "plan.md") : null;
    return new Detail(s.task(), repo.docs(s.folder()), actions, pos < 0 ? null : pos + 1, re);
  }

  /** The ticket went through IMPLEMENT before (worktrees exist): approving again returns straight to IMPLEMENT (FR-86). */
  public boolean implementedBefore(String project, String id) {
    return implementedBefore(stored(project, id));
  }

  boolean implementedBefore(Stored s) {
    return !repo.readDevState(s.folder()).repos().isEmpty();
  }

  // ponytail: one lock for all ticket writes; per-project locks if parallel agents ever contend
  public synchronized Created create(String project, String type, String code, String title, String request) {
    Project p = project(project);
    ProjectConfig cfg = p.config();
    Path dir = Path.of(p.dataDir());
    if (!Task.TYPES.contains(type)) {
      throw new IllegalArgumentException("Loại ticket phải là một trong " + Task.TYPES);
    }
    if (request == null || request.isBlank()) {
      throw new IllegalArgumentException("Chưa nhập yêu cầu");
    }
    SecretMasker.Result req = masker.mask(request.strip(), cfg.secrets());
    SecretMasker.Result head = masker.mask(title == null ? "" : title.strip(), cfg.secrets());
    String id = ticketId(dir, cfg, type, code == null ? "" : code.strip());

    RequestCleaner.Cleaned cleaned = cleaner.clean(req.text());
    String name = head.text().isBlank() ? firstWords(cleaned.summary().isBlank() ? req.text() : cleaned.summary()) : head.text();
    String slug = RequestCleaner.slug(name);
    List<String> modules = cleaner.mentioned(name + "\n" + req.text(), moduleNames(cfg));
    String now = now();
    Task task = new Task(id, type, name, TaskStatus.NEW, reposOf(cfg, modules),
        cfg.branchPatterns().get(type).replace("{code}", id).replace("{slug}", slug), 0, 0, now, now, null, null);

    Path folder = dir.resolve("tasks").resolve(id + "-" + slug);
    repo.writeTask(folder, task, "# " + name + "\n\n## Yêu cầu gốc của PMO\n\n" + req.text() + "\n");
    repo.writeSpec(folder, new Spec(id, project, type, modules,
        cleaned.summary().isBlank() ? name : cleaned.summary(), cleaned.constraints(), "task.md"));
    int masked = req.count() + head.count();
    repo.writeDoc(folder, "history.md", "# Nhật ký – " + id + "\n\n");
    history(new Stored(folder, task), Actor.PMO,
        "Tạo ticket " + type + (masked > 0 ? ", đã che " + masked + " giá trị giống bí mật" : ""));
    changed(project, id);
    return new Created(task, masked);
  }

  public synchronized Task transition(String project, String id, String toCode, Actor actor) {
    Stored s = stored(project, id);
    TaskStatus from = s.task().status();
    TaskStatus to = TaskStatus.parse(toCode);
    if (!from.next().contains(to)) {
      throw new IllegalArgumentException("Không chuyển được từ " + from.code() + " sang " + to.code() + " (ngoài bảng FR-86)");
    }
    if (from == TaskStatus.DONE) {
      throw new IllegalArgumentException("Ticket đã Done: dùng \"Báo bug\" hoặc \"Bổ sung logic\" để mở lại");
    }
    if (from.isPmoGate(to) && actor != Actor.PMO) {
      throw new PmoOnlyException("Chỉ PMO được " + (from == TaskStatus.WAIT_APPROVAL ? to == TaskStatus.ANALYSIS
          ? "yêu cầu phân tích lại" : "chốt giải pháp" : from == TaskStatus.CANCELLED ? "mở lại ticket đã huỷ"
          : to == TaskStatus.IMPLEMENT ? "yêu cầu sửa" : "xác nhận push"));
    }
    if (from.isReanalysis(to) && actor != Actor.PMO && actor != Actor.DEVMAP) {
      throw new PmoOnlyException("Chỉ PMO (yêu cầu phân tích lại) hoặc DevMap (thay đổi ảnh hưởng AC, business rule, phạm vi) "
          + "đưa ticket về phân tích");
    }
    boolean approve = from == TaskStatus.WAIT_APPROVAL && to != TaskStatus.ANALYSIS;
    if (approve) {
      if (repo.readDoc(s.folder(), "plan.md").map(String::isBlank).orElse(true)) {
        throw new IllegalArgumentException("Chưa có plan.md, chưa chốt được giải pháp");
      }
      boolean before = implementedBefore(s);
      if (to == TaskStatus.READY && before) {
        throw new IllegalArgumentException("Ticket đã từng triển khai: chốt lại đưa thẳng về implement, không xếp lại hàng đợi");
      }
      if (to == TaskStatus.IMPLEMENT && !before) {
        throw new IllegalArgumentException("Chốt lần đầu: ticket vào hàng đợi (ready)");
      }
    }
    if (from == TaskStatus.IMPLEMENT && to == TaskStatus.VERIFY) {
      requireGatePassed(project, s);
    }
    if (to == TaskStatus.DONE) {
      requirePushed(project, s);
    }
    Task updated = s.task().withStatus(to, now());
    if (approve) {
      updated = updated.withFlags(updated.flags().withApprovedPlan(repo.versionCount(s.folder(), "plan.md"))
          .withMidChange(false).withNeedsPmo(null), now());
    }
    repo.writeTask(s.folder(), updated, repo.readTaskBody(s.folder()));
    updateQueue(dir(project), id, to);
    history(s, actor, null, from.isPmoGate(to) ? "cổng duyệt PMO (NFR-03)" : "bảng chuyển trạng thái FR-86",
        "Chuyển trạng thái " + from.code() + " → " + to.code());
    changed(project, id);
    return updated;
  }

  public Doc readDoc(String project, String id, String name) {
    Stored s = stored(project, id);
    if (!TaskRepository.isDocName(name)) {
      throw new IllegalArgumentException("Tài liệu không hợp lệ: " + name);
    }
    String content = repo.readDoc(s.folder(), name).orElseThrow(() -> new NoSuchElementException("Chưa có " + name));
    return new Doc(name, content, repo.versionCount(s.folder(), name), TaskDocs.PMO_EDITABLE.contains(name), 0);
  }

  public synchronized Doc writeDoc(String project, String id, String name, String content, Actor actor) {
    Stored s = stored(project, id);
    if (!TaskRepository.isDocName(name) || name.equals("task.md") || name.equals("spec.json")) {
      throw new IllegalArgumentException("Không ghi được tài liệu: " + name);
    }
    if (actor == Actor.PMO && !TaskDocs.PMO_EDITABLE.contains(name)) {
      throw new IllegalArgumentException("PMO chỉ sửa tay được " + TaskDocs.PMO_EDITABLE);
    }
    SecretMasker.Result masked = masker.mask(content == null ? "" : content, project(project).config().secrets());
    if (masked.text().equals(repo.readDoc(s.folder(), name).orElse(null))) {
      return new Doc(name, masked.text(), repo.versionCount(s.folder(), name), true, masked.count());
    }
    repo.writeDoc(s.folder(), name, masked.text());
    String note = "Sửa " + name;
    if (TaskDocs.VERSIONED.contains(name)) {
      int v = repo.saveVersion(s.folder(), name, masked.text());
      repo.writeTask(s.folder(), s.task().withVersion(s.task().version() + 1, now()), repo.readTaskBody(s.folder()));
      note += " → v" + v;
    }
    history(s, actor, note + (masked.count() > 0 ? ", đã che " + masked.count() + " giá trị" : ""));
    changed(project, id);
    return new Doc(name, masked.text(), repo.versionCount(s.folder(), name), true, masked.count());
  }

  public String version(String project, String id, String doc, int n) {
    if (!TaskDocs.VERSIONED.contains(doc)) {
      throw new IllegalArgumentException("Không có version cho " + doc);
    }
    Stored s = stored(project, id);
    return repo.readVersion(s.folder(), doc, n).orElseThrow(() -> new NoSuchElementException(doc + " không có v" + n));
  }

  /**
   * FR-80 / AC-38 "Hoàn tác": version n of the document becomes the newest version again (a new version, so the undo itself can be
   * undone and the diff stays readable).
   */
  public synchronized Doc restore(String project, String id, String doc, int n, Actor actor) {
    String content = version(project, id, doc, n);
    Doc d = writeDoc(project, id, doc, content, Actor.DEVMAP);
    note(project, id, actor, "Hoàn tác " + doc + " về nội dung v" + n + " (→ v" + d.versions() + ")");
    return d;
  }

  /** FR-88: changes the flags of a ticket; note (may be null) goes to history.md. */
  public synchronized Task flags(String project, String id, UnaryOperator<Task.Flags> change, Actor actor, String note) {
    Stored s = stored(project, id);
    Task.Flags next = change.apply(s.task().flags());
    if (next.equals(s.task().flags())) {
      return s.task();
    }
    Task updated = s.task().withFlags(next, now());
    repo.writeTask(s.folder(), updated, repo.readTaskBody(s.folder()));
    if (note != null) {
      history(s, actor, note);
    }
    changed(project, id);
    return updated;
  }

  /** FR-85 / AC-41: rewrites task.md files that still carry an old status code; nothing else in the file changes. */
  public int migrate() {
    int n = 0;
    for (Project p : projects.list()) {
      if (p.error() != null || !Files.isDirectory(Path.of(p.dataDir()))) {
        continue;
      }
      for (Stored s : repo.list(Path.of(p.dataDir()))) {
        synchronized (this) {
          if (repo.hasLegacyStatus(s.folder())) {
            repo.writeTask(s.folder(), s.task(), repo.readTaskBody(s.folder()));
            history(s, Actor.DEVMAP, "Migration mô hình trạng thái mới (FR-85): trạng thái hiện là " + s.task().status().code()
                + (s.task().reopened() > 0 ? ", mở lại " + s.task().reopened() + " lần" : ""));
            n++;
          }
        }
      }
    }
    return n;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void migrateOnStart() {
    try {
      migrate();
    } catch (RuntimeException e) {
      // a broken project must not stop DevMap; its tickets still read through TaskStatus.parse
    }
  }

  /** Unified diff between two versions of analysis.md or plan.md (FR-09). */
  public String diff(String project, String id, String doc, int from, int to) {
    List<String> a = version(project, id, doc, from).lines().toList();
    List<String> b = version(project, id, doc, to).lines().toList();
    return String.join("\n", UnifiedDiffUtils.generateUnifiedDiff(doc + " v" + from, doc + " v" + to, a, DiffUtils.diff(a, b), 3));
  }

  /** FR-10: "Báo bug" / "Bổ sung logic" on a Done ticket; same code, entry in bugs/, back to SA analysis. */
  public synchronized Task reopen(String project, String id, String kind, String description, Actor actor) {
    if (actor != Actor.PMO) {
      throw new PmoOnlyException("Chỉ PMO được mở lại ticket");
    }
    boolean bug = "bug".equals(kind);
    if (!bug && !"logic".equals(kind)) {
      throw new IllegalArgumentException("kind phải là bug hoặc logic");
    }
    if (description == null || description.isBlank()) {
      throw new IllegalArgumentException("Chưa mô tả bug/logic cần bổ sung");
    }
    Stored s = stored(project, id);
    if (s.task().status() != TaskStatus.DONE) {
      throw new IllegalArgumentException("Chỉ mở lại được ticket đã Done");
    }
    ProjectConfig cfg = project(project).config();
    SecretMasker.Result desc = masker.mask(description.strip(), cfg.secrets());
    String prefix = bug ? "BUG" : "LOGIC";
    long n = repo.docs(s.folder()).stream().filter(d -> d.startsWith("bugs/" + prefix + "-")).count() + 1;
    String entry = String.format("bugs/%s-%02d.md", prefix, n);
    String now = now();
    repo.writeDoc(s.folder(), entry, "# " + entry.substring(5, entry.length() - 3) + " – " + now + "\n\n" + desc.text() + "\n");

    Task updated = s.task().reopen(now);
    repo.writeTask(s.folder(), updated, repo.readTaskBody(s.folder()));
    RequestCleaner.Cleaned cleaned = cleaner.clean(desc.text());
    List<String> modules = cleaner.mentioned(desc.text(), moduleNames(cfg));
    repo.writeSpec(s.folder(), new Spec(id, project, bug ? "REOPEN_BUG" : "ADD_LOGIC",
        modules.isEmpty() ? repo.readSpec(s.folder()).targetModules() : modules,
        cleaned.summary().isBlank() ? s.task().title() : cleaned.summary(), cleaned.constraints(), entry));
    history(s, actor, (bug ? "Báo bug " : "Bổ sung logic ") + entry + ", mở lại lần " + updated.reopened());
    changed(project, id);
    return updated;
  }

  /** D-66: the PMO cancels a ticket from any open state, with a reason; it is final, nothing is pushed. */
  public synchronized Task cancel(String project, String id, String reason, Actor actor) {
    if (actor != Actor.PMO) {
      throw new PmoOnlyException("Chỉ PMO được huỷ ticket");
    }
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("Chưa ghi lý do huỷ ticket");
    }
    Stored s = stored(project, id);
    TaskStatus from = s.task().status();
    if (from.isClosed()) {
      throw new IllegalArgumentException("Ticket đã " + from.code());
    }
    SecretMasker.Result why = masker.mask(reason.strip(), project(project).config().secrets());
    Task updated = s.task().withStatus(TaskStatus.CANCELLED, now());
    repo.writeTask(s.folder(), updated, repo.readTaskBody(s.folder()));
    updateQueue(dir(project), id, TaskStatus.CANCELLED);
    history(s, actor, "Huỷ ticket từ " + from.code() + ", lý do: " + why.text());
    changed(project, id);
    return updated;
  }

  /**
   * D-72: the PMO sets any status by hand, with a reason, when the normal flow cannot move the ticket (stuck state, gate or push
   * check that cannot pass). Skips next() and the gate/push checks on purpose; the reason goes to history.md.
   */
  public synchronized Task forceStatus(String project, String id, String toCode, String reason, Actor actor) {
    if (actor != Actor.PMO) {
      throw new PmoOnlyException("Chỉ PMO được đổi trạng thái bằng tay");
    }
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("Chưa ghi lý do đổi trạng thái");
    }
    Stored s = stored(project, id);
    TaskStatus from = s.task().status();
    TaskStatus to = TaskStatus.parse(toCode);
    if (from == to) {
      throw new IllegalArgumentException("Ticket đã ở " + to.code());
    }
    SecretMasker.Result why = masker.mask(reason.strip(), project(project).config().secrets());
    Task updated = s.task().withStatus(to, now());
    repo.writeTask(s.folder(), updated, repo.readTaskBody(s.folder()));
    updateQueue(dir(project), id, to);
    history(s, actor, "Đổi trạng thái bằng tay " + from.code() + " → " + to.code() + ", lý do: " + why.text());
    changed(project, id);
    return updated;
  }

  /** Repos the ticket touches; they get a worktree each when Dev starts. Fixed once the ticket is Done. */
  public synchronized Task setRepos(String project, String id, List<String> names, Actor actor) {
    Stored s = stored(project, id);
    if (s.task().status().isClosed()) {
      throw new IllegalArgumentException("Ticket đã " + s.task().status().code());
    }
    List<String> known = project(project).config().repos().stream().map(RepoConfig::name).toList();
    List<String> wanted = names == null ? List.of() : names.stream().distinct().toList();
    if (!known.containsAll(wanted)) {
      throw new IllegalArgumentException("Repo phải thuộc dự án: " + known);
    }
    Task t = s.task();
    Task updated = t.withRepos(wanted, now());
    repo.writeTask(s.folder(), updated, repo.readTaskBody(s.folder()));
    history(s, actor, "Repo của ticket: " + wanted);
    changed(project, id);
    return updated;
  }

  /**
   * SA names the branch in short English ("order-cancel-refund") once it understands the ticket: the default slug comes from the
   * Vietnamese title. Only before the branch exists in any repo, so a renamed branch never orphans commits.
   */
  public synchronized Task setBranch(String project, String id, String english, Actor actor) {
    Stored s = stored(project, id);
    if (!devState(project, s).repos().isEmpty()) {
      throw new IllegalArgumentException("Branch " + s.task().branch() + " đã được tạo, không đổi tên được nữa");
    }
    String words = String.join("-", Arrays.stream(RequestCleaner.slug(english).split("-")).limit(4).toList());
    String branch = project(project).config().branchPatterns().get(s.task().type()).replace("{code}", id).replace("{slug}", words);
    if (branch.equals(s.task().branch())) {
      return s.task();
    }
    Task updated = s.task().withBranch(branch, now());
    repo.writeTask(s.folder(), updated, repo.readTaskBody(s.folder()));
    history(s, actor, "Branch của ticket: " + branch);
    changed(project, id);
    return updated;
  }

  /** AIR-13 / FR-31: SA verifies only code whose current commit passed the quality gate, with nothing left uncommitted. */
  private void requireGatePassed(String project, Stored s) {
    DevState dev = devState(project, s);
    List<String> problems = new ArrayList<>();
    for (String name : s.task().repos()) {
      DevState.RepoWork w = dev.repo(name);
      if (w == null) {
        problems.add(name + ": chưa tạo worktree");
        continue;
      }
      Path wt = Path.of(w.worktree());
      if (w.gateHead() == null || !w.gateHead().equals(git.tip(wt, w.branch()))) {
        problems.add(name + ": quality gate chưa đạt trên commit hiện tại");
      } else if (!git.uncommitted(wt).isEmpty()) {
        problems.add(name + ": còn thay đổi chưa commit");
      }
    }
    if (!problems.isEmpty()) {
      throw new IllegalArgumentException("Chưa chuyển sang kiểm chứng được: " + String.join("; ", problems));
    }
  }

  /** FR-27: Done only after the verified commit of every changed repo was pushed through "Xác nhận push". */
  private void requirePushed(String project, Stored s) {
    List<String> missing = devState(project, s).repos().stream()
        .filter(w -> !git.tip(Path.of(w.worktree()), w.branch()).equals(w.baseCommit()))
        .filter(w -> w.pushedHead() == null || !w.pushedHead().equals(w.gateHead()))
        .map(DevState.RepoWork::repo).toList();
    if (!missing.isEmpty()) {
      throw new IllegalArgumentException("Chưa push commit đã kiểm chứng ở " + missing + "; dùng \"Xác nhận push\"");
    }
  }

  /** Ready tickets in the order the PMO set (FR-07); no priorities. */
  public List<Task> queue(String project) {
    List<Task> tasks = list(project);
    return repo.readQueue(dir(project)).stream()
        .flatMap(id -> tasks.stream().filter(t -> t.id().equals(id) && t.status() == TaskStatus.READY)).toList();
  }

  public synchronized List<Task> reorderQueue(String project, List<String> order) {
    Set<String> ready = new LinkedHashSet<>(queue(project).stream().map(Task::id).toList());
    if (order == null || !new LinkedHashSet<>(order).equals(ready) || order.size() != ready.size()) {
      throw new IllegalArgumentException("Hàng đợi phải gồm đúng các ticket đang Sẵn sàng: " + ready);
    }
    repo.writeQueue(dir(project), order);
    changed(project, null);
    return queue(project);
  }

  /** FR-41: running tickets and decisions waiting for the PMO in every project. */
  public List<ProjectOverview> overview() {
    List<ProjectOverview> out = new ArrayList<>();
    for (Project p : projects.list()) {
      if (p.error() != null) {
        continue;
      }
      List<Task> tasks = list(p.name());
      out.add(new ProjectOverview(p.name(), p.config().color(),
          tasks.stream().filter(t -> t.status().isRunning() || t.status() == TaskStatus.READY).toList(),
          tasks.stream().filter(t -> t.status().waitsForPmo()).toList()));
    }
    return out;
  }

  private String ticketId(Path dir, ProjectConfig cfg, String type, String code) {
    if ("auto".equals(cfg.ticketCode()) || code.isEmpty()) {
      Pattern own = Pattern.compile(Pattern.quote(type) + "-(\\d+)");
      int max = repo.list(dir).stream().map(s -> own.matcher(s.task().id()))
          .filter(Matcher::matches).mapToInt(m -> Integer.parseInt(m.group(1))).max().orElse(0);
      return String.format("%s-%03d", type, max + 1);
    }
    if (!CODE.matcher(code).matches()) {
      throw new IllegalArgumentException("Mã ticket chỉ gồm chữ, số, '.', '_', '-' (tối đa 40 ký tự): " + code);
    }
    if (cfg.ticketPattern() != null && !cfg.ticketPattern().isBlank() && !code.matches(cfg.ticketPattern())) {
      throw new IllegalArgumentException("Mã ticket không khớp mẫu của dự án " + cfg.ticketPattern() + ": " + code);
    }
    if (repo.find(dir, code).isPresent()) {
      throw new IllegalArgumentException("Mã ticket đã tồn tại: " + code);
    }
    return code;
  }

  private static List<String> moduleNames(ProjectConfig cfg) {
    Set<String> names = new LinkedHashSet<>();
    for (RepoConfig r : cfg.repos()) {
      r.services().forEach(s -> names.add(s.name()));
      names.add(r.name());
    }
    return List.copyOf(names);
  }

  private static List<String> reposOf(ProjectConfig cfg, List<String> modules) {
    return cfg.repos().stream()
        .filter(r -> modules.contains(r.name()) || r.services().stream().map(ServiceInfo::name).anyMatch(modules::contains))
        .map(RepoConfig::name).toList();
  }

  private void updateQueue(Path dir, String id, TaskStatus to) {
    List<String> q = new ArrayList<>(repo.readQueue(dir));
    boolean changed = to == TaskStatus.READY ? !q.contains(id) && q.add(id) : q.remove(id);
    if (changed) {
      repo.writeQueue(dir, q);
    }
  }

  /** NFR-10: a line in history.md for something done outside this service (agent sessions, Q&A). */
  public void note(String project, String id, Actor actor, String text) {
    history(stored(project, id), actor, text);
  }

  void history(Stored s, Actor actor, String text) {
    history(s, actor, null, null, text);
  }

  /** history.md of the ticket plus the project audit log (NFR-10); repoName and rule may be null. */
  void history(Stored s, Actor actor, String repoName, String rule, String text) {
    String clean = masker.mask(text).text();
    repo.appendDoc(s.folder(), "history.md", "- " + now() + " · " + actor + " · " + clean + "\n");
    events.publishEvent(new AuditLog.Event(s.folder().getParent().getParent(), actor.name(), s.task().id(), repoName, null, rule, clean));
  }

  void changed(String project, String id) {
    events.publishEvent(new TaskChanged(project, id));
  }

  /** First sentence, at most 80 chars: the title when the PMO leaves it blank. */
  private static String firstWords(String text) {
    String line = text.strip().split("(?<=[.!?;])\\s+|\\n", 2)[0].replaceAll("[.;]$", "");
    return line.length() <= 80 ? line : line.substring(0, 80).replaceAll("\\s+\\S*$", "") + "…";
  }

  private static String now() {
    return LocalDateTime.now().format(TIME);
  }

  Project project(String name) {
    Project p = projects.get(name);
    if (p.error() != null) {
      throw new IllegalStateException(p.error());
    }
    return p;
  }

  private Path dir(String project) {
    Path d = Path.of(project(project).dataDir());
    if (!Files.isDirectory(d)) {
      throw new IllegalStateException("Không có folder dữ liệu " + d);
    }
    return d;
  }

  /** The ticket folder in the data folder (chat.json lives there next to the documents). */
  public Path folder(String project, String id) {
    return stored(project, id).folder();
  }

  /**
   * dev.json syncs with the data folder, so a repo's path may be the other machine's. A path missing here means this machine's
   * copy of that repo (workspaces.yml); a path that exists, such as an old worktree of this machine, stays as it is.
   */
  DevState devState(String project, Stored s) {
    DevState dev = repo.readDevState(s.folder());
    List<RepoConfig> known = project(project).config().repos();
    return new DevState(dev.repos().stream().map(w -> Files.exists(Path.of(w.worktree())) ? w
        : known.stream().filter(r -> r.name().equals(w.repo()) && r.path() != null && !r.path().isBlank()).findFirst()
            .map(r -> w.withWorktree(r.dir().toString().replace('\\', '/'))).orElse(w)).map(this::healBase).toList(),
        dev.approved(), dev.direct());
  }

  /**
   * The recorded base commit is gone when the repo's history was rewritten: fall back to where the ticket branch leaves the
   * base branch now (or the base tip), so log/diff/gate keep working instead of "Invalid revision range".
   */
  private DevState.RepoWork healBase(DevState.RepoWork w) {
    Path wt = Path.of(w.worktree());
    if (w.baseCommit() == null || !Files.exists(wt) || git.hasCommit(wt, w.baseCommit())) {
      return w;
    }
    String base = git.resolveBranch(wt, w.baseBranch());
    if (base == null) {
      return w;
    }
    String fork = git.mergeBase(wt, base, git.tip(wt, w.branch()));
    return new DevState.RepoWork(w.repo(), w.worktree(), w.branch(), w.baseBranch(), fork == null ? base : fork, w.gateHead(),
        w.pushedHead(), w.pulledAt());
  }

  Stored stored(String project, String id) {
    return repo.find(dir(project), id).orElseThrow(() -> new NoSuchElementException("Không có ticket " + id));
  }
}
