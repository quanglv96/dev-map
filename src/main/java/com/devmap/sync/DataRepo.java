package com.devmap.sync;

import com.devmap.git.GitService;
import com.devmap.git.GitService.Out;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.Repository;

/**
 * A project's data folder as a git repo (FR-46 → FR-54, D-53). Local work (init, status, commit, reading conflict stages) goes through
 * JGit; fetch, rebase, push and clone go through the git CLI so the machine's credential manager supplies the token (never a file
 * or the remote URL).
 */
@org.springframework.stereotype.Repository
public class DataRepo {

  public static final String LOCK = ".devmap-lock.json";

  /** FR-50: per machine or rebuildable, never synced. Export (FR-57) skips the same. */
  public static final List<String> IGNORED = List.of("graph/", "logs/", "cache/", "sessions/", "*.tmp", "*.lock", "*.log", "*.bak");

  private static final String IGNORE_HEADER = "# DevMap: dữ liệu theo máy hoặc tạo lại được, không đồng bộ (FR-50)";
  private static final Set<String> IGNORED_DIRS = Set.of("graph", "logs", "cache", "sessions", ".git");
  private static final Pattern IGNORED_FILE = Pattern.compile(".*\\.(tmp|lock|log|bak)$");
  private static final Pattern CONFLICT = Pattern.compile("(.*)\\.conflict-[A-Za-z0-9_-]+(\\.[^./]+)?$");
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final int MAX_REBASE_STEPS = 50;

  /** One changed path of the working tree, relative to the root with '/'. */
  public record Change(String path, boolean added, boolean deleted) {
  }

  /** .devmap-lock.json: which machine has DevMap open; heartbeat refreshed every 60 s (FR-53). */
  public record Lock(String machine, String since, String heartbeat) {
  }

  private final GitService git;

  public DataRepo(GitService git) {
    this.git = git;
  }

  public boolean isRepo(Path root) {
    return git.isRepository(root);
  }

  /** git init (branch main) when needed, .gitignore, origin = remote. Nothing is committed here. */
  public void init(Path root, String remote) {
    if (!isRepo(root)) {
      try (Git g = Git.init().setDirectory(root.toFile()).setInitialBranch("main").call()) {
        g.getRepository().getConfig().save();
      } catch (GitAPIException | IOException e) {
        throw new IllegalStateException("Không khởi tạo được git ở " + root + ": " + e.getMessage(), e);
      }
    }
    git.run(root, "config", "core.autocrlf", "false"); // DevMap writes LF; the same bytes on every machine
    ensureIgnore(root);
    if (remote != null && !remote.isBlank()) {
      Out o = git.run(root, "remote", git.hasOrigin(root) ? "set-url" : "add", "origin", remote);
      if (!o.ok()) {
        throw new IllegalStateException("Không gắn được remote: " + o.output());
      }
    }
  }

  public void cloneTo(String remote, Path target) {
    Path dest = target.toAbsolutePath();
    try {
      Files.createDirectories(dest.getParent());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    Out o = git.run(dest.getParent(), "clone", "-q", "-c", "core.autocrlf=false", "--", remote, dest.toString());
    if (!o.ok()) {
      throw new IllegalStateException("Không clone được repo dữ liệu: " + o.output());
    }
    ensureIgnore(target);
  }

  public String remote(Path root) {
    return git.remoteUrl(root);
  }

  /** Writes .gitignore, or appends the lines it is missing; the PMO's own lines stay. */
  public void ensureIgnore(Path root) {
    Path f = root.resolve(".gitignore");
    try {
      String text = Files.exists(f) ? Files.readString(f) : "";
      Set<String> have = new LinkedHashSet<>(text.lines().map(String::strip).toList());
      List<String> missing = IGNORED.stream().filter(l -> !have.contains(l)).toList();
      if (missing.isEmpty()) {
        return;
      }
      StringBuilder sb = new StringBuilder(text);
      if (!text.isEmpty() && !text.endsWith("\n")) {
        sb.append('\n');
      }
      if (!have.contains(IGNORE_HEADER)) {
        sb.append(IGNORE_HEADER).append('\n');
      }
      missing.forEach(l -> sb.append(l).append('\n'));
      Files.writeString(f, sb.toString());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Uncommitted changes, .gitignore applied. */
  public List<Change> changes(Path root) {
    try (Git g = Git.open(root.toFile())) {
      Status s = g.status().call();
      Set<String> added = new TreeSet<>(s.getUntracked());
      added.addAll(s.getAdded());
      Set<String> deleted = new TreeSet<>(s.getMissing());
      deleted.addAll(s.getRemoved());
      Set<String> all = new TreeSet<>(added);
      all.addAll(deleted);
      all.addAll(s.getModified());
      all.addAll(s.getChanged());
      all.addAll(s.getConflicting());
      return all.stream().map(p -> new Change(p, added.contains(p), deleted.contains(p))).toList();
    } catch (IOException | GitAPIException e) {
      throw new IllegalStateException("Không đọc được trạng thái git của " + root + ": " + e.getMessage(), e);
    }
  }

  /** Path, size and modification time of each change: equal on two ticks = nobody is writing any more. */
  public String fingerprint(Path root, List<Change> changes) {
    StringBuilder sb = new StringBuilder();
    for (Change c : changes) {
      Path f = root.resolve(c.path());
      sb.append(c.path()).append(':');
      try {
        sb.append(Files.exists(f) ? Files.size(f) + ":" + Files.getLastModifiedTime(f).toMillis() : "-");
      } catch (IOException e) {
        sb.append('?');
      }
      sb.append('\n');
    }
    return sb.toString();
  }

  /** Stages every change (deletions too) and commits with the machine's git identity. */
  public void commit(Path root, String message) {
    try (Git g = Git.open(root.toFile())) {
      g.add().addFilepattern(".").call();
      g.add().setUpdate(true).addFilepattern(".").call();
      g.commit().setMessage(message).call();
    } catch (IOException | GitAPIException e) {
      throw new IllegalStateException("Không commit được dữ liệu: " + e.getMessage(), e);
    }
  }

  public String branch(Path root) {
    String b = git.currentBranch(root);
    return b == null ? "main" : b;
  }

  /** Error message, or null when origin was fetched (or there is no origin). */
  public String fetch(Path root) {
    return git.fetch(root);
  }

  public boolean hasUpstream(Path root) {
    return git.run(root, "rev-parse", "--verify", "--quiet", "refs/remotes/origin/" + branch(root)).ok();
  }

  public boolean hasCommits(Path root) {
    return git.run(root, "rev-parse", "--verify", "--quiet", "HEAD").ok();
  }

  /** Commits not on origin yet. */
  public int ahead(Path root) {
    if (!hasCommits(root)) {
      return 0;
    }
    Out o = hasUpstream(root) ? git.run(root, "rev-list", "--count", "origin/" + branch(root) + "..HEAD")
        : git.run(root, "rev-list", "--count", "HEAD");
    return o.ok() ? Integer.parseInt(o.output().strip()) : 0;
  }

  public String head(Path root) {
    return hasCommits(root) ? git.head(root).strip() : "";
  }

  /** Never forced: a rejected push (someone pushed first) is solved by the next pull. */
  public void push(Path root) {
    Out o = git.run(root, "push", "--quiet", "origin", "HEAD:refs/heads/" + branch(root));
    if (!o.ok()) {
      throw new IllegalStateException("Push dữ liệu lỗi: " + o.output());
    }
  }

  /**
   * FR-54: rebases local commits onto origin. On a conflict the version already on origin stays in the file and this machine's
   * version is saved next to it as {@code <name>.conflict-<machine>.<ext>}; nothing is overwritten. The lock and .gitignore take
   * origin's version. Returns the conflict files written.
   */
  public List<String> rebase(Path root, String machine) {
    String upstream = "origin/" + branch(root);
    Out o = git.run(root, "-c", "core.editor=true", "rebase", "--autostash", upstream);
    List<String> created = new ArrayList<>();
    for (int step = 0; !o.ok() && rebasing(root); step++) {
      if (step == MAX_REBASE_STEPS) {
        git.run(root, "rebase", "--abort");
        throw new IllegalStateException("Không rebase được dữ liệu sau " + MAX_REBASE_STEPS + " bước, đã huỷ (không mất gì): "
            + o.output());
      }
      for (String path : unmerged(root)) {
        keepBoth(root, path, machine, created);
      }
      o = git.run(root, "-c", "core.editor=true", "rebase", "--continue");
      if (!o.ok() && rebasing(root) && unmerged(root).isEmpty() && git.run(root, "diff", "--cached", "--quiet").ok()) {
        o = git.run(root, "rebase", "--skip"); // the local commit became empty: origin already has the same content
      }
    }
    if (!o.ok() && rebasing(root)) {
      git.run(root, "rebase", "--abort");
      throw new IllegalStateException("Rebase dữ liệu lỗi, đã huỷ: " + o.output());
    }
    if (!o.ok()) {
      throw new IllegalStateException("Rebase dữ liệu lỗi: " + o.output());
    }
    return created;
  }

  private boolean rebasing(Path root) {
    return Files.exists(root.resolve(".git/rebase-merge")) || Files.exists(root.resolve(".git/rebase-apply"));
  }

  private List<String> unmerged(Path root) {
    Out o = git.run(root, "-c", "core.quotepath=off", "diff", "--name-only", "--diff-filter=U");
    return o.output().isBlank() ? List.of() : Arrays.stream(o.output().split("\n")).map(String::strip).distinct().toList();
  }

  /** Stage 2 = origin (the base being rebased onto), stage 3 = this machine's commit being replayed. */
  private void keepBoth(Path root, String path, String machine, List<String> created) {
    Map<Integer, byte[]> stages = new HashMap<>();
    try (Repository r = Git.open(root.toFile()).getRepository()) {
      DirCache dc = r.readDirCache();
      for (int i = 0; i < dc.getEntryCount(); i++) {
        DirCacheEntry e = dc.getEntry(i);
        if (e.getPathString().equals(path) && e.getStage() > 0) {
          stages.put(e.getStage(), r.open(e.getObjectId()).getBytes());
        }
      }
      byte[] origin = stages.get(2);
      byte[] mine = stages.get(3);
      Path file = root.resolve(path);
      if (origin == null && mine == null) {
        git.run(root, "rm", "-q", "--cached", "--", path);
        return;
      }
      Files.createDirectories(file.getParent());
      Files.write(file, origin != null ? origin : mine);
      boolean shared = path.equals(LOCK) || path.equals(".gitignore");
      if (!shared && origin != null && mine != null && !Arrays.equals(origin, mine)) {
        Path copy = conflictCopy(file, machine);
        Files.write(copy, mine);
        String rel = root.relativize(copy).toString().replace('\\', '/');
        created.add(rel);
        git.run(root, "add", "--", rel);
      }
      git.run(root, "add", "--", path);
    } catch (IOException e) {
      throw new UncheckedIOException("Không giữ được hai bản của " + path + ": " + e.getMessage(), e);
    }
  }

  private static Path conflictCopy(Path file, String machine) {
    String name = file.getFileName().toString();
    int dot = name.lastIndexOf('.');
    String base = dot > 0 ? name.substring(0, dot) : name;
    String ext = dot > 0 ? name.substring(dot) : "";
    String tag = machine.replaceAll("[^A-Za-z0-9_-]", "-");
    Path out = file.resolveSibling(base + ".conflict-" + tag + ext);
    for (int n = 2; Files.exists(out); n++) {
      out = file.resolveSibling(base + ".conflict-" + tag + "-" + n + ext);
    }
    return out;
  }

  /** Every {@code *.conflict-*} file under root, relative with '/'. */
  public List<String> conflictFiles(Path root) {
    try (Stream<Path> files = Files.walk(root)) {
      return files.filter(Files::isRegularFile).map(f -> root.relativize(f).toString().replace('\\', '/'))
          .filter(p -> !p.startsWith(".git/") && CONFLICT.matcher(p).matches()).sorted().toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** shop/tasks/X/analysis.conflict-may-a.md → shop/tasks/X/analysis.md; null when it is not a conflict file. */
  public static String original(String conflictFile) {
    var m = CONFLICT.matcher(conflictFile);
    return m.matches() ? m.group(1) + (m.group(2) == null ? "" : m.group(2)) : null;
  }

  /** The machine tag in a conflict file name. */
  public static String conflictMachine(String conflictFile) {
    String name = conflictFile.substring(conflictFile.lastIndexOf('/') + 1);
    String rest = name.substring(name.indexOf(".conflict-") + ".conflict-".length());
    int dot = rest.lastIndexOf('.');
    return dot > 0 ? rest.substring(0, dot) : rest;
  }

  public Lock readLock(Path root) {
    Path f = root.resolve(LOCK);
    try {
      return Files.exists(f) ? JSON.readValue(Files.readAllBytes(f), Lock.class) : null;
    } catch (IOException e) {
      return null; // a broken lock file locks nobody out
    }
  }

  public void writeLock(Path root, Lock lock) {
    try {
      Files.writeString(root.resolve(LOCK), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(lock) + "\n");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** FR-50 for export: the same exclusions as .gitignore. */
  public static boolean ignored(String relPath) {
    String[] parts = relPath.replace('\\', '/').split("/");
    for (int i = 0; i < parts.length - 1; i++) {
      if (IGNORED_DIRS.contains(parts[i])) {
        return true;
      }
    }
    return IGNORED_FILE.matcher(parts[parts.length - 1]).matches() || parts[parts.length - 1].equals(LOCK);
  }

  /** FR-57: the project folder as a zip, entries under {@code <project>/}. */
  public void exportZip(Path projectDir, OutputStream out) {
    String project = projectDir.getFileName().toString();
    try (ZipOutputStream zip = new ZipOutputStream(out); Stream<Path> files = Files.walk(projectDir)) {
      for (Path f : files.filter(Files::isRegularFile).sorted().toList()) {
        String rel = projectDir.relativize(f).toString().replace('\\', '/');
        if (!ignored(rel)) {
          zip.putNextEntry(new ZipEntry(project + "/" + rel));
          Files.copy(f, zip);
          zip.closeEntry();
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Không xuất được " + projectDir + ": " + e.getMessage(), e);
    }
  }

  /** FR-57: unpacks into dataRoot/<project>; refuses a project folder that already exists. Returns the project name. */
  public String importZip(InputStream in, Path dataRoot) {
    Path tmp = null;
    try {
      Files.createDirectories(dataRoot);
      tmp = Files.createTempDirectory(dataRoot, ".import-");
      String project = null;
      try (ZipInputStream zip = new ZipInputStream(in)) {
        for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
          String name = e.getName().replace('\\', '/');
          String first = name.contains("/") ? name.substring(0, name.indexOf('/')) : name;
          if (project == null) {
            project = first;
          }
          Path dest = tmp.resolve(name).normalize();
          if (!first.equals(project) || !dest.startsWith(tmp.resolve(project)) || ignored(name)) {
            throw new IllegalArgumentException("File zip không hợp lệ (mỗi file phải nằm trong folder " + project + "/): " + name);
          }
          if (!e.isDirectory()) {
            Files.createDirectories(dest.getParent());
            Files.copy(zip, dest);
          }
        }
      }
      if (project == null || !Files.exists(tmp.resolve(project).resolve("config.yml"))) {
        throw new IllegalArgumentException("File zip không có <dự án>/config.yml");
      }
      Path target = dataRoot.resolve(project);
      if (Files.exists(target)) {
        throw new IllegalArgumentException("Đã có folder " + target + "; không ghi đè. Xoá hoặc đổi tên folder đó rồi nhập lại");
      }
      Files.move(tmp.resolve(project), target);
      return project;
    } catch (IOException e) {
      throw new UncheckedIOException("Không nhập được file zip: " + e.getMessage(), e);
    } finally {
      deleteTree(tmp);
    }
  }

  /** Best effort; git marks its object files read-only on Windows, so those are made writable first. */
  static void deleteTree(Path dir) {
    if (dir == null || !Files.exists(dir)) {
      return;
    }
    try (Stream<Path> files = Files.walk(dir)) {
      for (Path f : files.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
        f.toFile().setWritable(true);
        Files.deleteIfExists(f);
      }
    } catch (IOException e) {
      // leftover temp folder starts with '.', harmless
    }
  }
}
