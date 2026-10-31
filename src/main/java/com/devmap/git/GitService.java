package com.devmap.git;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.springframework.stereotype.Service;

/**
 * Git access. Reads go through JGit object storage (commit trees), never the working tree, so indexing does not
 * depend on what the PMO has checked out. Network operations use the git CLI so the user's credential manager applies.
 */
@Service
public class GitService {

  /** One changed path between two commits; oldPath is set for deletes/renames. */
  public record Change(String path, String oldPath, boolean deleted) {
  }

  public boolean isRepository(Path dir) {
    return Files.exists(dir.resolve(".git"));
  }

  /** True when the branch exists locally or as origin/branch (fresh clones only have the remote one). */
  public boolean branchExists(Path repo, String branch) {
    return resolveBranch(repo, branch) != null;
  }

  /** Newest commit of the base branch: origin/branch when present (after fetch it is the shared truth), else local. */
  public String resolveBranch(Path repo, String branch) {
    try (Git git = Git.open(repo.toFile())) {
      Repository r = git.getRepository();
      Ref ref = r.exactRef("refs/remotes/origin/" + branch);
      if (ref == null) {
        ref = r.exactRef("refs/heads/" + branch);
      }
      return ref == null ? null : ref.getObjectId().name();
    } catch (IOException e) {
      return null;
    }
  }

  /** `git fetch origin` when the repo has an origin; returns an error message or null. Never touches the working tree. */
  public String fetch(Path repo) {
    try (Git git = Git.open(repo.toFile())) {
      if (!git.getRepository().getRemoteNames().contains("origin")) {
        return null;
      }
    } catch (IOException e) {
      return e.getMessage();
    }
    try {
      Process p = new ProcessBuilder("git", "-C", repo.toString(), "fetch", "--quiet", "origin").redirectErrorStream(true).start();
      String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
      if (!p.waitFor(120, TimeUnit.SECONDS)) {
        p.destroyForcibly();
        return "git fetch quá 120 giây";
      }
      return p.exitValue() == 0 ? null : "git fetch lỗi: " + out;
    } catch (IOException e) {
      return "Không chạy được git: " + e.getMessage();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return "git fetch bị ngắt";
    }
  }

  /** Result of a git CLI call. */
  public record Out(int code, String output) {
    public boolean ok() {
      return code == 0;
    }
  }

  /**
   * Runs `git -C dir args...` (worktrees, merge, commit, push need the CLI: JGit has no worktree support and the CLI uses the
   * machine's credentials and hooks). Never used with --force; push refspecs are built by {@link #push} only.
   */
  public Out run(Path dir, String... args) {
    List<String> argv = new ArrayList<>(List.of("git", "-C", dir.toString()));
    argv.addAll(List.of(args));
    try {
      Process p = new ProcessBuilder(argv).redirectErrorStream(true).start();
      byte[] out = p.getInputStream().readAllBytes();
      if (!p.waitFor(10, TimeUnit.MINUTES)) {
        p.destroyForcibly();
        return new Out(-1, "git " + args[0] + " quá 10 phút");
      }
      return new Out(p.exitValue(), new String(out, StandardCharsets.UTF_8).stripTrailing()); // keep leading blanks: porcelain status
    } catch (IOException e) {
      return new Out(-1, "Không chạy được git: " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Out(-1, "git bị ngắt");
    }
  }

  private String must(Path dir, String... args) {
    Out o = run(dir, args);
    if (!o.ok()) {
      throw new IllegalStateException("git " + String.join(" ", args) + " lỗi ở " + dir + ": " + o.output());
    }
    return o.output();
  }

  /** Switches the repo itself to branch (created from startPoint when it does not exist locally). Never forced. */
  public void checkout(Path repo, String branch, String startPoint) {
    if (run(repo, "rev-parse", "--verify", "--quiet", "refs/heads/" + branch).ok()) {
      must(repo, "checkout", "-q", branch);
    } else {
      must(repo, "checkout", "-q", "-b", branch, startPoint);
    }
  }

  /** Drops a clean worktree left from before code moved into the service repo; the branch and its commits stay. */
  public void removeWorktree(Path repo, Path worktree) {
    must(repo, "worktree", "remove", worktree.toString());
  }

  /**
   * Stashes every uncommitted change (untracked too) of a worktree; returns the stash commit. The stash is shared by all worktrees
   * of the repo, so the main checkout can apply it after the worktree is gone.
   */
  public String stash(Path dir, String message) {
    must(dir, "stash", "push", "-u", "-m", message);
    return must(dir, "rev-parse", "refs/stash");
  }

  /** Applies a stash; drops it only once applied cleanly, so a conflict never loses the changes. */
  public void applyStash(Path repo, String stash) {
    Out o = run(repo, "stash", "apply", stash);
    if (!o.ok()) {
      throw new IllegalStateException("Không áp được thay đổi cũ (stash " + stash.substring(0, 8) + ", vẫn giữ trong git stash list): "
          + o.output());
    }
    if (run(repo, "rev-parse", "stash@{0}").output().strip().equals(stash)) {
      run(repo, "stash", "drop", "stash@{0}");
    }
  }

  /** Checked-out branch, or null for a detached HEAD or unreadable repo. */
  public String currentBranch(Path repo) {
    try (Git git = Git.open(repo.toFile())) {
      String branch = git.getRepository().getBranch();
      return branch == null || ObjectId.isId(branch) ? null : branch;
    } catch (IOException e) {
      return null;
    }
  }

  public String head(Path dir) {
    return must(dir, "rev-parse", "HEAD");
  }

  /** Newest commit of a local branch whether or not it is checked out (several tickets share one service repo), else HEAD. */
  public String tip(Path repo, String branch) {
    Out o = run(repo, "rev-parse", "--verify", "--quiet", "refs/heads/" + branch);
    return o.ok() ? o.output().strip() : head(repo);
  }

  /** Uncommitted paths (modified, added, deleted, untracked), relative to the worktree root. */
  public List<String> uncommitted(Path dir) {
    List<String> out = new ArrayList<>();
    for (String entry : must(dir, "-c", "core.quotepath=off", "status", "--porcelain", "--untracked-files=all").split("\n")) {
      if (entry.length() > 3) {
        String path = entry.substring(3);
        out.add(path.contains(" -> ") ? path.substring(path.indexOf(" -> ") + 4) : path);
      }
    }
    return out;
  }

  /** FR-24: brings the newest base branch into the ticket branch; a conflict is aborted and reported, never left half-merged. */
  public void merge(Path dir, String ref) {
    Out o = run(dir, "merge", "--no-edit", ref);
    if (!o.ok()) {
      run(dir, "merge", "--abort");
      throw new IllegalStateException("Không merge được " + ref + " (xung đột?), đã huỷ merge: " + o.output());
    }
  }

  public void commitAll(Path dir, String message) {
    must(dir, "add", "-A");
    must(dir, "commit", "-q", "-m", message);
  }

  /** FR-30: pushes exactly one verified commit to the ticket branch. No '+' in the refspec and no --force: never a force-push. */
  public void push(Path dir, String commit, String branch) {
    if (!commit.matches("[0-9a-f]{40}") || branch.startsWith("+") || branch.startsWith("-")) {
      throw new IllegalArgumentException("Commit hoặc branch không hợp lệ: " + commit + " " + branch);
    }
    must(dir, "push", "origin", commit + ":refs/heads/" + branch);
  }

  public boolean hasOrigin(Path dir) {
    return run(dir, "remote", "get-url", "origin").ok();
  }

  /** "abc1234 subject" lines of the commits in from..to. */
  public List<String> log(Path dir, String from, String to) {
    String out = must(dir, "log", "--format=%h %s", from + ".." + to);
    return out.isEmpty() ? List.of() : List.of(out.split("\n"));
  }

  public List<String> changedFiles(Path dir, String from, String to) {
    String out = must(dir, "-c", "core.quotepath=off", "diff", "--name-only", from + "..." + to);
    return out.isEmpty() ? List.of() : List.of(out.split("\n"));
  }

  /** Committed changes from...to plus, when to is what is checked out, what is not committed yet. */
  public String diffText(Path dir, String from, String to, boolean withWorkingTree) {
    return must(dir, "diff", from + "..." + to) + "\n" + (withWorkingTree ? must(dir, "diff", "HEAD") : "");
  }

  /** Content of path at commit (works in worktrees too), or null when the file does not exist there. */
  public String show(Path dir, String commit, String path) {
    Out o = run(dir, "show", commit + ":" + path);
    return o.ok() ? o.output() : null;
  }

  /** Every file path in the commit's tree. */
  public List<String> listFiles(Path repo, String commit) {
    try (Git git = Git.open(repo.toFile()); RevWalk walk = new RevWalk(git.getRepository());
        TreeWalk tw = new TreeWalk(git.getRepository())) {
      tw.addTree(walk.parseCommit(ObjectId.fromString(commit)).getTree());
      tw.setRecursive(true);
      List<String> out = new ArrayList<>();
      while (tw.next()) {
        out.add(tw.getPathString());
      }
      return out;
    } catch (IOException e) {
      throw new UncheckedIOException("Không đọc được cây commit " + commit + " ở " + repo, e);
    }
  }

  /** Files added, modified, renamed or deleted between two commits (AIR-06: incremental by git diff). */
  public List<Change> diff(Path repo, String from, String to) {
    try (Git git = Git.open(repo.toFile()); ObjectReader reader = git.getRepository().newObjectReader();
        RevWalk walk = new RevWalk(git.getRepository())) {
      CanonicalTreeParser a = new CanonicalTreeParser(null, reader, walk.parseCommit(ObjectId.fromString(from)).getTree());
      CanonicalTreeParser b = new CanonicalTreeParser(null, reader, walk.parseCommit(ObjectId.fromString(to)).getTree());
      List<Change> out = new ArrayList<>();
      for (DiffEntry e : git.diff().setOldTree(a).setNewTree(b).call()) {
        switch (e.getChangeType()) {
          case DELETE -> out.add(new Change(e.getOldPath(), e.getOldPath(), true));
          case RENAME -> {
            out.add(new Change(e.getOldPath(), e.getOldPath(), true));
            out.add(new Change(e.getNewPath(), e.getOldPath(), false));
          }
          default -> out.add(new Change(e.getNewPath(), null, false));
        }
      }
      return out;
    } catch (IOException | GitAPIException e) {
      throw new IllegalStateException("Không diff được " + from + ".." + to + " ở " + repo + ": " + e.getMessage(), e);
    }
  }

  /**
   * Streams the content of the files at a commit whose path passes {@code include}, opening the repo once.
   * The filter runs on the path before any byte is read, so excluded (secret) files are never opened.
   */
  public void readFiles(Path repo, String commit, Predicate<String> include, BiConsumer<String, byte[]> sink) {
    try (Git git = Git.open(repo.toFile()); RevWalk walk = new RevWalk(git.getRepository());
        TreeWalk tw = new TreeWalk(git.getRepository())) {
      RevCommit c = walk.parseCommit(ObjectId.fromString(commit));
      tw.addTree(c.getTree());
      tw.setRecursive(true);
      while (tw.next()) {
        String path = tw.getPathString();
        if (include.test(path)) {
          sink.accept(path, git.getRepository().open(tw.getObjectId(0)).getBytes());
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Không đọc được commit " + commit + " ở " + repo, e);
    }
  }
}
