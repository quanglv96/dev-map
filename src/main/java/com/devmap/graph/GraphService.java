package com.devmap.graph;

import com.devmap.config.Project;
import com.devmap.config.ProjectConfig;
import com.devmap.config.ProjectService;
import com.devmap.config.RepoConfig;
import com.devmap.git.GitService;
import com.devmap.git.GitService.Change;
import com.devmap.secret.SecretMasker;
import com.devmap.ticket.TaskService;
import com.devmap.ticket.TicketGitService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.UnaryOperator;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * FR-17, FR-19 → FR-22, AIR-05 → AIR-07: the signature index and the graph built from it.
 * Nothing runs at startup. {@link #update} with full=false only re-parses files changed between the indexed commit and
 * the base branch after `git fetch`; full=true (PMO button / `devmap index --full`) parses everything once.
 * Only signatures leave this machine-local index; no code is sent to any agent here.
 */
@Service
public class GraphService {

  public record RepoReport(String repo, String commit, int parsed, int removed, String note) {
  }

  public record Report(boolean full, List<RepoReport> repos, long millis) {
  }

  public record RepoStatus(String repo, String baseBranch, String indexedCommit, String indexedAt, int files) {
  }

  public record Status(List<RepoStatus> repos, boolean running, Report last, String error, int nodes, int edges) {
  }

  public record Estimate(Map<String, Integer> filesByRepo, int files) {
  }

  /** Published when a job starts/ends or manual edges change; the web layer forwards it over SSE. */
  public record GraphChanged(String project, String state) {
  }

  private static final List<String> EDGE_TYPES = List.of("http", "calls", "depends", "publishes", "consumes", "uses_table",
      "config", "compose", "manual");
  private static final List<String> CONFIDENCE = List.of("high", "medium", "low");
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
  private static final ObjectMapper JSON = new ObjectMapper();

  private final ProjectService projects;
  private final GitService git;
  private final GraphRepository store;
  private final SourceIndexer indexer;
  private final SecretMasker masker;
  private final TaskService tasks;
  private final ApplicationEventPublisher events;
  /** Graph per project, valid while the project config (repos/services) it was built from is unchanged. */
  private record Cached(ProjectConfig config, Graph graph) {
  }

  private final Map<String, Cached> cache = new ConcurrentHashMap<>();
  private final Set<String> running = ConcurrentHashMap.newKeySet();
  private final Map<String, Report> lastReport = new ConcurrentHashMap<>();
  private final Map<String, String> lastError = new ConcurrentHashMap<>();
  // ponytail: one background job at a time for the whole machine; a pool if several projects need scanning at once
  private final ExecutorService jobs = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "devmap-index");
    t.setDaemon(true);
    return t;
  });

  public GraphService(ProjectService projects, GitService git, GraphRepository store, SourceIndexer indexer, SecretMasker masker,
      TaskService tasks, ApplicationEventPublisher events) {
    this.projects = projects;
    this.git = git;
    this.store = store;
    this.indexer = indexer;
    this.masker = masker;
    this.tasks = tasks;
    this.events = events;
  }

  /** Fetch every repo, then index: incrementally by git diff, or every file when full. Synchronous. */
  public Report update(String project, boolean full) {
    if (!running.add(project)) {
      throw new IllegalArgumentException("Đang cập nhật chỉ mục của " + project);
    }
    try {
      return doUpdate(project, full);
    } finally {
      running.remove(project);
    }
  }

  /** Same as {@link #update} in the background; progress arrives as GraphChanged events. */
  public void start(String project, boolean full) {
    project(project);
    if (running.contains(project)) {
      throw new IllegalArgumentException("Đang cập nhật chỉ mục của " + project);
    }
    events.publishEvent(new GraphChanged(project, "running"));
    jobs.submit(() -> {
      try {
        update(project, full);
        lastError.remove(project);
      } catch (RuntimeException e) {
        lastError.put(project, String.valueOf(e.getMessage()));
      }
      events.publishEvent(new GraphChanged(project, "done"));
    });
  }

  private Report doUpdate(String project, boolean full) {
    long t0 = System.currentTimeMillis();
    Project p = project(project);
    Path dir = Path.of(p.dataDir());
    UnaryOperator<String> mask = s -> s == null ? null : masker.mask(s, p.config().secrets()).text();
    List<RepoReport> reports = new ArrayList<>();
    for (RepoConfig rc : p.config().repos()) {
      reports.add(updateRepo(dir, rc, full, mask));
    }
    cache.remove(project);
    Report report = new Report(full, reports, System.currentTimeMillis() - t0);
    lastReport.put(project, report);
    return report;
  }

  private RepoReport updateRepo(Path dir, RepoConfig rc, boolean full, UnaryOperator<String> mask) {
    Path repoPath = Path.of(rc.path());
    String fetchError = git.fetch(repoPath);
    String head = git.resolveBranch(repoPath, rc.baseBranch());
    String note = fetchError == null ? "" : fetchError + "; ";
    if (head == null) {
      return new RepoReport(rc.name(), null, 0, 0, note + "không có nhánh nền " + rc.baseBranch());
    }
    Optional<RepoIndex> old = store.load(dir, rc.name());
    Map<String, FileIndex> files = new TreeMap<>();
    Set<String> wanted = null;
    int removed = 0;
    if (!full) {
      if (old.isEmpty()) {
        return new RepoReport(rc.name(), null, 0, 0, note + "chưa có chỉ mục: PMO bấm \"Quét toàn bộ dự án\" một lần");
      }
      if (old.get().commit().equals(head)) {
        return new RepoReport(rc.name(), head, 0, 0, note + "đã mới nhất");
      }
      files.putAll(old.get().files());
      wanted = new HashSet<>();
      List<Change> changes;
      try {
        changes = git.diff(repoPath, old.get().commit(), head);
      } catch (IllegalStateException e) {
        return new RepoReport(rc.name(), old.get().commit(), 0, 0, note + "lịch sử nhánh đã đổi, cần quét toàn bộ: " + e.getMessage());
      }
      for (Change c : changes) {
        if (c.deleted() || !indexer.indexable(c.path())) {
          removed += files.remove(c.path()) == null ? 0 : 1;
        } else {
          wanted.add(c.path());
        }
      }
    }
    Set<String> only = wanted;
    List<SimpleEntry<String, byte[]>> sources = new ArrayList<>();
    git.readFiles(repoPath, head, path -> indexer.indexable(path) && (only == null || only.contains(path)),
        (path, bytes) -> sources.add(new SimpleEntry<>(path, bytes)));
    sources.parallelStream()
        .map(s -> new SimpleEntry<>(s.getKey(), Optional.ofNullable(safeExtract(s.getKey(), s.getValue())).map(f -> f.mask(mask))))
        .toList()
        .forEach(e -> e.getValue().ifPresentOrElse(f -> files.put(e.getKey(), f), () -> files.remove(e.getKey())));
    store.save(dir, new RepoIndex(rc.name(), head, LocalDateTime.now().format(TIME), files));
    return new RepoReport(rc.name(), head, sources.size(), removed, note.isEmpty() ? "ok" : note + "dùng dữ liệu đã có");
  }

  private FileIndex safeExtract(String path, byte[] bytes) {
    try {
      return indexer.extract(path, bytes);
    } catch (RuntimeException | LinkageError e) {
      return null; // one unparsable file must not stop the scan
    }
  }

  /** NFR-05: what a full scan would read, shown to the PMO before it starts. */
  public Estimate estimate(String project) {
    Map<String, Integer> byRepo = new TreeMap<>();
    for (RepoConfig rc : project(project).config().repos()) {
      String head = git.resolveBranch(Path.of(rc.path()), rc.baseBranch());
      byRepo.put(rc.name(), head == null ? 0 : (int) git.listFiles(Path.of(rc.path()), head).stream().filter(indexer::indexable).count());
    }
    return new Estimate(byRepo, byRepo.values().stream().mapToInt(Integer::intValue).sum());
  }

  public Status status(String project) {
    Project p = project(project);
    Path dir = Path.of(p.dataDir());
    List<RepoStatus> repos = p.config().repos().stream().map(rc -> store.load(dir, rc.name())
        .map(ri -> new RepoStatus(rc.name(), rc.baseBranch(), ri.commit(), ri.indexedAt(), ri.files().size()))
        .orElse(new RepoStatus(rc.name(), rc.baseBranch(), null, null, 0))).toList();
    Graph g = graph(project);
    return new Status(repos, running.contains(project), lastReport.get(project), lastError.get(project), g.nodeCount(), g.edgeCount());
  }

  public Graph graph(String project) {
    Project p = project(project);
    Cached c = cache.get(project);
    if (c == null || !c.config().equals(p.config())) {
      Path dir = Path.of(p.dataDir());
      List<RepoIndex> indexes = p.config().repos().stream().map(rc -> store.load(dir, rc.name())).flatMap(Optional::stream).toList();
      c = new Cached(p.config(), GraphBuilder.build(p.config(), indexes, store.manualEdges(dir)));
      cache.put(project, c);
    }
    return c.graph();
  }

  /** Services a ticket touches (spec.json target_modules + task repos), for highlighting and blast radius. */
  public List<String> ticketServices(String project, String taskId) {
    Set<String> names = new LinkedHashSet<>(tasks.detail(project, taskId).task().repos());
    try {
      JsonNode spec = JSON.readTree(tasks.readDoc(project, taskId, "spec.json").content());
      spec.path("target_modules").forEach(m -> names.add(m.asText()));
    } catch (IOException | NoSuchElementException e) {
      // no spec: repos only
    }
    return graph(project).servicesNamed(names);
  }

  public List<ManualEdge> manualEdges(String project) {
    return store.manualEdges(Path.of(project(project).dataDir()));
  }

  public synchronized ManualEdge addManualEdge(String project, String from, String to, String type, String confidence, String note) {
    Graph g = graph(project);
    if (g.node(from) == null || g.node(to) == null || from.equals(to)) {
      throw new IllegalArgumentException("Cần hai node khác nhau đã có trong graph");
    }
    String t = type == null || type.isBlank() ? "manual" : type;
    String c = confidence == null || confidence.isBlank() ? "high" : confidence;
    if (!EDGE_TYPES.contains(t) || !CONFIDENCE.contains(c)) {
      throw new IllegalArgumentException("Loại edge phải thuộc " + EDGE_TYPES + ", độ tin cậy thuộc " + CONFIDENCE);
    }
    Path dir = Path.of(project(project).dataDir());
    List<ManualEdge> all = new ArrayList<>(store.manualEdges(dir));
    ManualEdge edge = new ManualEdge(UUID.randomUUID().toString().substring(0, 8), from, to, t, c,
        note == null ? "" : masker.mask(note).text(), LocalDateTime.now().format(TIME));
    all.add(edge);
    store.saveManualEdges(dir, all);
    changed(project);
    return edge;
  }

  public synchronized void removeManualEdge(String project, String id) {
    Path dir = Path.of(project(project).dataDir());
    List<ManualEdge> all = new ArrayList<>(store.manualEdges(dir));
    if (!all.removeIf(e -> e.id().equals(id))) {
      throw new NoSuchElementException("Không có edge " + id);
    }
    store.saveManualEdges(dir, all);
    changed(project);
  }

  /** FR-21 / D-21: after Dev pulled base branches, update the index by git diff in the background (only once it exists). */
  @EventListener
  public void onBasePulled(TicketGitService.BasePulled e) {
    Path dir = Path.of(project(e.project()).dataDir());
    boolean indexed = project(e.project()).config().repos().stream().anyMatch(rc -> store.load(dir, rc.name()).isPresent());
    if (indexed && !running.contains(e.project())) {
      start(e.project(), false);
    }
  }

  private void changed(String project) {
    cache.remove(project);
    events.publishEvent(new GraphChanged(project, "edges"));
  }

  private Project project(String name) {
    Project p = projects.get(name);
    if (p.error() != null) {
      throw new IllegalStateException(p.error());
    }
    return p;
  }
}
