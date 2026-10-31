package com.devmap.config;

import com.devmap.git.GitService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** FR-01 → FR-04: register projects, their data folders, repos, base branches and services. */
@Service
public class ProjectService {

  /** A repo as typed by the PMO when adding a project. */
  public record RepoInput(String path, String baseBranch) {

    /** Parses "path@branch"; branch defaults to main. */
    public static RepoInput parse(String spec) {
      int at = spec.lastIndexOf('@');
      return at > 0 ? new RepoInput(spec.substring(0, at), spec.substring(at + 1)) : new RepoInput(spec, "main");
    }
  }

  private static final Logger LOG = LoggerFactory.getLogger(ProjectService.class);
  private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,39}");
  private static final Set<String> RESERVED = Set.of("api", "assets", "vendor", "error");
  private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}");

  private final WorkspaceRepository workspaces;
  private final ProjectRepository projects;
  private final ServiceDetector detector;
  private final GitService git;

  public ProjectService(WorkspaceRepository workspaces, ProjectRepository projects, ServiceDetector detector, GitService git) {
    this.workspaces = workspaces;
    this.projects = projects;
    this.detector = detector;
    this.git = git;
  }

  public List<Project> list() {
    return workspaces.findAll().stream().map(this::load).toList();
  }

  public Project get(String name) {
    return load(workspace(name));
  }

  /**
   * Registers a project. A data folder that already has config.yml (e.g. copied from another machine)
   * is registered as is; otherwise the folder skeleton is created from the given repos.
   */
  public synchronized Project create(String name, Path dataRoot, String color, List<RepoInput> repoInputs) {
    if (name == null || !NAME.matcher(name).matches() || RESERVED.contains(name)) {
      throw new IllegalArgumentException(
          "Tên dự án chỉ gồm chữ thường, số, dấu '-' (tối đa 40 ký tự) và không trùng tên dành riêng: " + name);
    }
    if (workspaces.findAll().stream().anyMatch(w -> w.name().equals(name))) {
      throw new IllegalArgumentException("Dự án đã tồn tại: " + name);
    }
    if (dataRoot == null || dataRoot.toString().isBlank()) {
      throw new IllegalArgumentException("Chưa chọn folder dữ liệu");
    }
    Path dir = dataRoot.toAbsolutePath().normalize().resolve(name);
    if (projects.exists(dir)) {
      if (!repoInputs.isEmpty()) {
        throw new IllegalArgumentException("Folder " + dir + " đã có config.yml; sửa repo trong Cấu hình dự án thay vì khai báo lại");
      }
      ProjectConfig existing = projects.load(dir);
      if (!name.equals(existing.project())) {
        throw new IllegalArgumentException("config.yml ở " + dir + " thuộc dự án '" + existing.project() + "'");
      }
    } else {
      List<RepoConfig> repos = repoInputs.stream().map(this::toRepo).toList();
      ProjectConfig config = ProjectConfig.create(name, color == null ? "#2563eb" : color, repos);
      requireValid(config);
      rememberPaths(name, repos);
      projects.save(dir, config);
    }
    List<Workspace> all = new ArrayList<>(workspaces.findAll());
    all.add(new Workspace(name, dir.toString().replace('\\', '/')));
    workspaces.saveAll(all);
    return get(name);
  }

  public synchronized Project update(String name, ProjectConfig config) {
    Workspace ws = workspace(name);
    ProjectConfig fixed = config.withProject(name);
    // the remote comes from the repo itself when it is here; else keep what config.yml already says (machine B, FR-51)
    Map<String, String> known = new HashMap<>();
    if (projects.exists(Path.of(ws.dataDir()))) {
      projects.load(Path.of(ws.dataDir())).repos().forEach(r -> known.put(r.name(), r.remote()));
    }
    List<RepoConfig> repos = fixed.repos().stream().map(r -> {
      String found = r.path() == null || r.path().isBlank() ? null : git.remoteUrl(Path.of(r.path()));
      return r.withRemote(found != null ? found : r.remote() != null ? r.remote() : known.get(r.name()));
    }).toList();
    fixed = new ProjectConfig(name, fixed.color(), fixed.ticketCode(), fixed.ticketPattern(), fixed.branchPatterns(), repos,
        fixed.environments(), fixed.scanFullProject(), fixed.secrets());
    requireValid(fixed);
    rememberPaths(name, repos);
    projects.save(Path.of(ws.dataDir()), fixed);
    return get(name);
  }

  /** FR-51: the PMO points a repo of the project at a folder that already exists on this machine. */
  public synchronized Project locateRepo(String project, String repoName, String path) {
    RepoConfig rc = repo(project, repoName);
    Path dir = Path.of(path).toAbsolutePath().normalize();
    if (!git.isRepository(dir)) {
      throw new IllegalArgumentException(dir + " không phải git repo");
    }
    String remote = git.remoteUrl(dir);
    if (rc.remote() != null && (remote == null || !RepoConfig.normalize(remote).equals(RepoConfig.normalize(rc.remote())))) {
      throw new IllegalArgumentException(dir + (remote == null ? " không có remote origin" : " có remote " + remote)
          + ", repo " + repoName + " cần remote " + rc.remote());
    }
    rememberPaths(project, List.of(rc.withPath(slash(dir))));
    return get(project);
  }

  /** FR-51: clones a repo of the project into parent/<repo name> and remembers the path. */
  public synchronized Project cloneRepo(String project, String repoName, String parent) {
    RepoConfig rc = repo(project, repoName);
    if (rc.remote() == null) {
      throw new IllegalArgumentException("Repo " + repoName + " không có remote để clone; chọn folder đã có");
    }
    Path target = Path.of(parent).toAbsolutePath().normalize().resolve(repoName);
    if (Files.exists(target)) {
      throw new IllegalArgumentException(target + " đã tồn tại; chọn folder đó thay vì clone");
    }
    git.cloneRepo(rc.remote(), target);
    rememberPaths(project, List.of(rc.withPath(slash(target))));
    return get(project);
  }

  /** Registers every project folder under dataRoot (config.yml inside) not registered yet, e.g. created on another machine. */
  public synchronized List<String> registerFound(Path dataRoot) {
    List<String> added = new ArrayList<>();
    Set<String> known = new HashSet<>(workspaces.findAll().stream().map(Workspace::name).toList());
    try (Stream<Path> children = Files.list(dataRoot)) {
      for (Path dir : children.filter(projects::exists).sorted().toList()) {
        String name = dir.getFileName().toString();
        if (!known.contains(name) && NAME.matcher(name).matches()) {
          try {
            create(name, dataRoot, null, List.of());
            added.add(name);
          } catch (RuntimeException e) {
            LOG.warn("Không đăng ký được dự án {}: {}", name, e.getMessage());
          }
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return added;
  }

  private RepoConfig repo(String project, String repoName) {
    Project p = get(project);
    if (p.config() == null) {
      throw new IllegalStateException(p.error());
    }
    return p.config().repos().stream().filter(r -> r.name().equals(repoName)).findFirst()
        .orElseThrow(() -> new NoSuchElementException("Dự án không có repo " + repoName));
  }

  private void rememberPaths(String project, List<RepoConfig> repos) {
    Map<String, String> paths = new HashMap<>();
    repos.stream().filter(r -> r.path() != null && !r.path().isBlank()).forEach(r -> paths.put(r.key(project), r.path()));
    if (!paths.isEmpty()) {
      workspaces.putRepoPaths(paths);
    }
  }

  /**
   * FR-52: a config.yml from before FR-51 still holds absolute repo paths. Moves them to workspaces.yml, records each repo's
   * origin as remote, keeps config.yml.bak. Runs once: afterwards config.yml has no path left.
   */
  private ProjectConfig migrate(Path dir, ProjectConfig config) {
    List<RepoConfig> repos = config.repos().stream().map(r -> r.path() == null ? r
        : r.withRemote(r.remote() != null ? r.remote() : git.remoteUrl(Path.of(r.path())))).toList();
    projects.backup(dir);
    rememberPaths(config.project(), repos);
    ProjectConfig migrated = new ProjectConfig(config.project(), config.color(), config.ticketCode(), config.ticketPattern(),
        config.branchPatterns(), repos, config.environments(), config.scanFullProject(), config.secrets());
    projects.save(dir, migrated);
    LOG.info("Đã migrate {}: {} đường dẫn repo chuyển sang workspaces.yml, bản cũ ở config.yml.bak", dir.resolve("config.yml"),
        repos.size());
    return migrated;
  }

  private ProjectConfig withLocalPaths(ProjectConfig c) {
    List<RepoConfig> repos = c.repos().stream().map(r -> r.withPath(workspaces.repoPath(r.key(c.project())))).toList();
    return new ProjectConfig(c.project(), c.color(), c.ticketCode(), c.ticketPattern(), c.branchPatterns(), repos,
        c.environments(), c.scanFullProject(), c.secrets());
  }

  /** Unregisters only; the data folder is never deleted. */
  public synchronized void remove(String name) {
    workspace(name);
    workspaces.saveAll(workspaces.findAll().stream().filter(w -> !w.name().equals(name)).toList());
  }

  /** A sub-folder; usedBy names the project whose data folder or repo is (inside) it, null when free. */
  public record Folder(String name, String path, String usedBy) {
  }

  /** Sub-folders of dir for the data-root scan. */
  public record Folders(String path, String parent, List<Folder> dirs) {
  }

  public Folders folders(String dir) {
    Path path = Path.of(dir).toAbsolutePath().normalize();
    if (!Files.isDirectory(path)) {
      throw new IllegalArgumentException("Không tìm thấy thư mục: " + dir);
    }
    Map<Path, String> used = usedPaths();
    try (Stream<Path> children = Files.list(path)) {
      List<Folder> dirs = children.filter(Files::isDirectory).filter(p -> !p.getFileName().toString().startsWith("."))
          .sorted().map(p -> new Folder(p.getFileName().toString(), slash(p), usedBy(p, used))).toList();
      return new Folders(slash(path), path.getParent() == null ? "" : slash(path.getParent()), dirs);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Data folders and repo paths of every registered project. */
  private Map<Path, String> usedPaths() {
    Map<Path, String> out = new HashMap<>();
    for (Project p : list()) {
      out.put(Path.of(p.dataDir()).toAbsolutePath().normalize(), p.name());
      if (p.config() != null) {
        p.config().repos().stream().filter(r -> r.path() != null)
            .forEach(r -> out.put(Path.of(r.path()).toAbsolutePath().normalize(), p.name()));
      }
    }
    return out;
  }

  private static String usedBy(Path dir, Map<Path, String> used) {
    return used.entrySet().stream().filter(e -> e.getKey().startsWith(dir)).map(Map.Entry::getValue).findFirst()
        .orElse(null);
  }

  private static String slash(Path p) {
    return p.toString().replace('\\', '/');
  }

  /**
   * Git repos of a project folder (the parent holding the services): the folder itself when it is a repo, else its
   * direct sub-folders that are. Base branch defaults to the checked-out one. Nothing is written.
   */
  public List<RepoConfig> scanRepos(String dir) {
    Path root = Path.of(dir).toAbsolutePath().normalize();
    if (!Files.isDirectory(root)) {
      throw new IllegalArgumentException("Không tìm thấy thư mục: " + dir);
    }
    if (git.isRepository(root)) {
      return List.of(found(root));
    }
    try (Stream<Path> children = Files.list(root)) {
      return children.filter(git::isRepository).filter(c -> !c.getFileName().toString().startsWith("."))
          .sorted().map(this::found).toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private RepoConfig found(Path repo) {
    String branch = git.currentBranch(repo);
    return toRepo(new RepoInput(repo.toString(), branch == null ? "main" : branch));
  }

  public List<ServiceInfo> detectServices(String repoPath) {
    Path repo = Path.of(repoPath);
    if (!Files.isDirectory(repo)) {
      throw new IllegalArgumentException("Không tìm thấy thư mục repo: " + repoPath);
    }
    return detector.detect(repo);
  }

  /** Every problem in the config (empty = valid); also used by `devmap doctor`. */
  public List<String> problems(ProjectConfig config) {
    List<String> out = new ArrayList<>();
    if (config.color() == null || !COLOR.matcher(config.color()).matches()) {
      out.add("Màu dự án phải có dạng #rrggbb");
    }
    if (!ProjectConfig.TICKET_CODE_MODES.contains(config.ticketCode())) {
      out.add("ticket_code phải là một trong " + ProjectConfig.TICKET_CODE_MODES);
    }
    if (config.ticketPattern() != null && !config.ticketPattern().isBlank()) {
      try {
        Pattern.compile(config.ticketPattern());
      } catch (PatternSyntaxException e) {
        out.add("Mẫu mã ticket không phải regex hợp lệ: " + e.getDescription());
      }
    }
    Stream.concat(config.secrets().patterns().stream(), config.secrets().allow().stream()).forEach(p -> {
      try {
        Pattern.compile(p);
      } catch (PatternSyntaxException e) {
        out.add("Mẫu trong secrets không phải regex hợp lệ: " + p);
      }
    });
    for (String type : ProjectConfig.DEFAULT_BRANCH_PATTERNS.keySet()) {
      String p = config.branchPatterns().get(type);
      if (p == null || !p.contains("{code}")) {
        out.add("Mẫu branch " + type + " phải chứa {code}");
      }
    }
    Set<String> repoNames = new HashSet<>();
    Set<String> serviceNames = new HashSet<>();
    for (RepoConfig repo : config.repos()) {
      if (!repoNames.add(repo.name())) {
        out.add("Trùng tên repo: " + repo.name());
      }
      Path path = repo.path() == null || repo.path().isBlank() ? null : Path.of(repo.path());
      if (path == null) {
        out.add("Repo " + repo.name() + ": chưa có đường dẫn trên máy này (chọn folder đã có hoặc clone)");
      } else if (!git.isRepository(path)) {
        out.add("Repo " + repo.name() + ": " + repo.path() + " không phải git repo");
      } else if (repo.baseBranch() == null || repo.baseBranch().isBlank()) {
        out.add("Repo " + repo.name() + ": chưa khai báo nhánh nền");
      } else if (!git.branchExists(path, repo.baseBranch())) {
        out.add("Repo " + repo.name() + ": không có nhánh nền '" + repo.baseBranch() + "' (local hoặc origin)");
      }
      for (ServiceInfo s : repo.services()) {
        if (s.name() == null || s.name().isBlank() || !serviceNames.add(s.name())) {
          out.add("Tên service trống hoặc trùng: " + s.name());
        }
      }
    }
    return out;
  }

  private void requireValid(ProjectConfig config) {
    List<String> problems = problems(config);
    if (!problems.isEmpty()) {
      throw new IllegalArgumentException(String.join("; ", problems));
    }
  }

  private RepoConfig toRepo(RepoInput in) {
    Path path = Path.of(in.path()).toAbsolutePath().normalize();
    List<ServiceInfo> services = Files.isDirectory(path) ? detector.detect(path) : List.of();
    return new RepoConfig(path.getFileName().toString(), path.toString().replace('\\', '/'), in.baseBranch(), services,
        git.remoteUrl(path));
  }

  private Workspace workspace(String name) {
    return workspaces.findAll().stream().filter(w -> w.name().equals(name)).findFirst()
        .orElseThrow(() -> new NoSuchElementException("Không có dự án: " + name));
  }

  private Project load(Workspace ws) {
    Path dir = Path.of(ws.dataDir());
    if (!projects.exists(dir)) {
      return new Project(ws.name(), ws.dataDir(), null, "Không tìm thấy config.yml trong " + ws.dataDir());
    }
    try {
      ProjectConfig config = projects.load(dir);
      if (config.repos().stream().anyMatch(r -> r.path() != null)) {
        config = migrate(dir, config);
      }
      return new Project(ws.name(), ws.dataDir(), withLocalPaths(config), null);
    } catch (RuntimeException e) {
      return new Project(ws.name(), ws.dataDir(), null, e.getMessage());
    }
  }
}
