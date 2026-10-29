package com.devmap.config;

import com.devmap.git.GitService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;
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
  public synchronized Project create(String name, Path dataRoot, String color, List<RepoInput> repoInputs, boolean initGit) {
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
      projects.save(dir, config);
    }
    if (initGit && !git.isRepository(dir)) {
      git.init(dir);
    }
    List<Workspace> all = new ArrayList<>(workspaces.findAll());
    all.add(new Workspace(name, dir.toString().replace('\\', '/')));
    workspaces.saveAll(all);
    return get(name);
  }

  public synchronized Project update(String name, ProjectConfig config) {
    Workspace ws = workspace(name);
    ProjectConfig fixed = config.withProject(name);
    requireValid(fixed);
    projects.save(Path.of(ws.dataDir()), fixed);
    return get(name);
  }

  /** Unregisters only; the data folder is never deleted. */
  public synchronized void remove(String name) {
    workspace(name);
    workspaces.saveAll(workspaces.findAll().stream().filter(w -> !w.name().equals(name)).toList());
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
      Path path = repo.path() == null ? null : Path.of(repo.path());
      if (path == null || !git.isRepository(path)) {
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
    return new RepoConfig(path.getFileName().toString(), path.toString().replace('\\', '/'), in.baseBranch(), services);
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
      return new Project(ws.name(), ws.dataDir(), projects.load(dir), null);
    } catch (RuntimeException e) {
      return new Project(ws.name(), ws.dataDir(), null, e.getMessage());
    }
  }
}
