package com.devmap.ticket;

import com.devmap.config.Project;
import com.devmap.config.ProjectConfig;
import com.devmap.config.ProjectService;
import com.devmap.config.RepoConfig;
import com.devmap.config.ServiceInfo;
import com.devmap.secret.SecretMasker;
import com.devmap.ticket.TaskRepository.Stored;
import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * FR-05 → FR-10, FR-44: tickets of a project. Every text written to the data folder passes the secret masker first
 * (FR-36), and the two PMO gates are enforced here, not in the UI (NFR-03).
 */
@Service
public class TaskService {

  /** Who asks for an action. Only the PMO passes the gates; agents get their own actor in Phase 5. */
  public enum Actor { PMO, SA, DEV }

  public record Action(String to, boolean pmoGate) {
  }

  public record Detail(Task task, List<String> docs, List<Action> actions, Integer queuePosition) {
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

  public TaskService(ProjectService projects, TaskRepository repo, SecretMasker masker, RequestCleaner cleaner,
      ApplicationEventPublisher events) {
    this.projects = projects;
    this.repo = repo;
    this.masker = masker;
    this.cleaner = cleaner;
    this.events = events;
  }

  public List<Task> list(String project) {
    return repo.list(dir(project)).stream().map(Stored::task)
        .sorted(Comparator.comparing(Task::created).reversed()).toList();
  }

  public Detail detail(String project, String id) {
    Stored s = stored(project, id);
    TaskStatus from = s.task().status();
    List<Action> actions = from.next().stream().sorted()
        .map(to -> new Action(to.code(), from.isPmoGate(to))).toList();
    int pos = repo.readQueue(dir(project)).indexOf(id);
    return new Detail(s.task(), repo.docs(s.folder()), actions, pos < 0 ? null : pos + 1);
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
        cfg.branchPatterns().get(type).replace("{code}", id).replace("{slug}", slug), 0, 0, now, now);

    Path folder = dir.resolve("tasks").resolve(id + "-" + slug);
    repo.writeTask(folder, task, "# " + name + "\n\n## Yêu cầu gốc của PMO\n\n" + req.text() + "\n");
    repo.writeSpec(folder, new Spec(id, project, type, modules,
        cleaned.summary().isBlank() ? name : cleaned.summary(), cleaned.constraints(), "task.md"));
    int masked = req.count() + head.count();
    repo.writeDoc(folder, "history.md", "# Nhật ký – " + id + "\n\n");
    history(folder, Actor.PMO, "Tạo ticket " + type + (masked > 0 ? ", đã che " + masked + " giá trị giống bí mật" : ""));
    changed(project, id);
    return new Created(task, masked);
  }

  public synchronized Task transition(String project, String id, String toCode, Actor actor) {
    Stored s = stored(project, id);
    TaskStatus from = s.task().status();
    TaskStatus to = TaskStatus.parse(toCode);
    if (!from.next().contains(to)) {
      throw new IllegalArgumentException("Không chuyển được từ " + from.code() + " sang " + to.code());
    }
    if (from.isPmoGate(to) && actor != Actor.PMO) {
      throw new PmoOnlyException("Chỉ PMO được " + (to == TaskStatus.READY ? "chốt giải pháp" : "xác nhận push"));
    }
    if (to == TaskStatus.READY && repo.readDoc(s.folder(), "plan.md").map(String::isBlank).orElse(true)) {
      throw new IllegalArgumentException("Chưa có plan.md, chưa chốt được giải pháp");
    }
    Task updated = s.task().withStatus(to, now());
    repo.writeTask(s.folder(), updated, repo.readTaskBody(s.folder()));
    updateQueue(dir(project), id, to);
    history(s.folder(), actor, "Chuyển trạng thái " + from.code() + " → " + to.code());
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
    history(s.folder(), actor, note + (masked.count() > 0 ? ", đã che " + masked.count() + " giá trị" : ""));
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
    history(s.folder(), actor, (bug ? "Báo bug " : "Bổ sung logic ") + entry + ", mở lại lần " + updated.reopened());
    changed(project, id);
    return updated;
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

  private void history(Path folder, Actor actor, String text) {
    repo.appendDoc(folder, "history.md", "- " + now() + " · " + actor + " · " + masker.mask(text).text() + "\n");
  }

  private void changed(String project, String id) {
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

  private Project project(String name) {
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

  private Stored stored(String project, String id) {
    return repo.find(dir(project), id).orElseThrow(() -> new NoSuchElementException("Không có ticket " + id));
  }
}
