package com.devmap.ticket;

import com.devmap.config.Project;
import com.devmap.config.RepoConfig;
import com.devmap.git.GitService;
import com.devmap.rule.QualityGate;
import com.devmap.rule.RuleService;
import com.devmap.rule.RuleService.ScopeReport;
import com.devmap.secret.SecretFiles;
import com.devmap.ticket.DevState.RepoWork;
import com.devmap.ticket.TaskRepository.Stored;
import com.devmap.ticket.TaskService.Actor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Dev side of a ticket (FR-24, FR-26, FR-27, FR-30 → FR-32): the ticket branch checked out in each service repo itself (PMO
 * decision, no worktree copies), base branch pulled first, commits carrying the ticket code within the allowed scope, the
 * quality gate, and the push only the PMO can confirm. Works the same whether the PMO clicks (Phase 4) or the Dev agent calls it
 * (Phase 5).
 */
@Service
public class TicketGitService {

  /** One repo of the ticket as shown before commit and push. */
  public record RepoView(String repo, String branch, String baseBranch, String baseCommit, String head, List<String> commits,
      List<String> files, List<String> uncommitted, boolean gatePassed, String pushedHead, String worktree) {
  }

  public record View(List<RepoView> repos, List<String> protectedBranches, boolean direct) {
  }

  /** Files of an uncommitted change that need the PMO (FR-32) or can never be committed (FR-34). */
  public record ScopeCheck(String repo, List<String> files, ScopeReport scope, List<String> secrets, List<String> needApproval) {
  }

  /** Published after base branches were pulled: the graph updates by git diff (FR-21, D-21). */
  public record BasePulled(String project) {
  }

  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

  private final TaskService tasks;
  private final TaskRepository repo;
  private final GitService git;
  private final RuleService rules;
  private final ApplicationEventPublisher events;

  public TicketGitService(TaskService tasks, TaskRepository repo, GitService git, RuleService rules, ApplicationEventPublisher events) {
    this.tasks = tasks;
    this.repo = repo;
    this.git = git;
    this.rules = rules;
    this.events = events;
  }

  /**
   * FR-24: fetch, check out the ticket branch in every service repo and merge the newest base branch into it, then
   * Ready → In dev. Refuses a protected ticket branch and a repo with uncommitted changes.
   */
  // ponytail: one lock for all git work; per-ticket locks when parallel agents (NFR-12) make it a bottleneck
  public synchronized View start(String project, String id, Actor actor) {
    Stored s = tasks.stored(project, id);
    Task t = s.task();
    if (t.status() != TaskStatus.READY && t.status() != TaskStatus.IN_DEV) {
      throw new IllegalArgumentException("Chỉ bắt đầu triển khai được khi ticket Sẵn sàng hoặc đang triển khai");
    }
    if (t.repos().isEmpty()) {
      throw new IllegalArgumentException("Ticket chưa có repo nào; chọn repo ở cột phải");
    }
    DevState dev = repo.readDevState(s.folder());
    if (!dev.direct() && rules.isProtected(project, t.branch())) {
      throw new IllegalArgumentException("Branch " + t.branch() + " là branch được bảo vệ, không làm trên đó được");
    }
    Project p = tasks.project(project);
    for (String name : t.repos()) {
      RepoConfig rc = repoConfig(p, name);
      Path main = Path.of(rc.path());
      String fetchError = git.fetch(main);
      String base = git.resolveBranch(main, rc.baseBranch());
      if (base == null) {
        throw new IllegalStateException(name + ": không có nhánh nền " + rc.baseBranch());
      }
      String target = dev.direct() ? rc.baseBranch() : t.branch();
      RepoWork w = dev.repo(name);
      String moved = dropOldWorktree(id, name, main, w);
      checkoutInRepo(project, id, name, main, target, base);
      git.merge(main, base); // no-op for a new branch; brings an existing ticket branch up to date
      String path = main.toString().replace('\\', '/');
      w = w == null ? new RepoWork(name, path, target, rc.baseBranch(), null, null, null, null)
          : new RepoWork(name, path, w.branch(), w.baseBranch(), w.baseCommit(), w.gateHead(), w.pushedHead(), w.pulledAt());
      dev = dev.with(w.withBase(base, now()));
      repo.writeDevState(s.folder(), dev); // per repo: a later repo failing must not leave this one pointing at a removed worktree
      if (moved != null) {
        git.applyStash(main, moved);
        tasks.history(s, actor, name, null, name + ": chuyển thay đổi chưa commit từ worktree cũ " + w.worktree()
            + " vào repo service (stash " + moved.substring(0, 8) + ")");
      }
      tasks.history(s, actor, name, "pull nhánh nền trước khi làm (AIR-04)",
          name + ": pull nhánh nền " + rc.baseBranch() + " @" + base.substring(0, 8)
          + (fetchError == null ? "" : " (fetch lỗi, dùng dữ liệu đã có: " + fetchError + ")"));
    }
    repo.writeDevState(s.folder(), dev);
    if (t.status() == TaskStatus.READY) {
      tasks.transition(project, id, TaskStatus.IN_DEV.code(), actor);
    }
    writeChanges(project, id);
    events.publishEvent(new BasePulled(project));
    tasks.changed(project, id);
    return view(project, id);
  }

  public View view(String project, String id) {
    Stored s = tasks.stored(project, id);
    List<RepoView> out = new ArrayList<>();
    for (RepoWork w : repo.readDevState(s.folder()).repos()) {
      Path wt = Path.of(w.worktree());
      if (!Files.exists(wt)) {
        out.add(new RepoView(w.repo(), w.branch(), w.baseBranch(), w.baseCommit(), null, List.of(), List.of(), List.of(), false,
            w.pushedHead(), w.worktree()));
        continue;
      }
      // the ticket's own branch, not HEAD: once it waits for push another ticket may have the repo checked out
      String head = git.tip(wt, w.branch());
      List<String> dirty = checkedOut(w) ? git.uncommitted(wt) : List.of();
      out.add(new RepoView(w.repo(), w.branch(), w.baseBranch(), w.baseCommit(), head, git.log(wt, w.baseCommit(), head),
          git.changedFiles(wt, w.baseCommit(), head), dirty, head.equals(w.gateHead()), w.pushedHead(), w.worktree()));
    }
    return new View(out, rules.protectedBranches(project), repo.readDevState(s.folder()).direct());
  }

  /**
   * PMO option: code on the base branch itself and push straight to it, no ticket branch. Only the PMO, only before the first
   * worktree exists; the push still waits for the gate, SA verification and the PMO's confirmation, and is never forced.
   */
  public synchronized View setDirect(String project, String id, boolean direct, Actor actor) {
    if (actor != Actor.PMO) {
      throw new PmoOnlyException("Chỉ PMO được chọn đẩy thẳng vào nhánh nền");
    }
    Stored s = tasks.stored(project, id);
    DevState dev = repo.readDevState(s.folder());
    if (!dev.repos().isEmpty()) {
      throw new IllegalArgumentException("Đã tạo worktree, không đổi cách làm branch được nữa");
    }
    repo.writeDevState(s.folder(), dev.withDirect(direct));
    tasks.history(s, actor, null, direct ? "PMO cho phép bỏ qua git.protected_branches cho nhánh nền" : null,
        direct ? "Code và push thẳng vào nhánh nền, không tạo branch ticket" : "Dùng branch ticket " + s.task().branch());
    tasks.changed(project, id);
    return view(project, id);
  }

  /** Committed changes since the base plus uncommitted ones, for the PMO to read before commit and push. */
  public String diff(String project, String id, String repoName) {
    RepoWork w = work(tasks.stored(project, id), repoName);
    Path wt = Path.of(w.worktree());
    return git.diffText(wt, w.baseCommit(), git.tip(wt, w.branch()), checkedOut(w));
  }

  public ScopeCheck checkScope(String project, String id, String repoName) {
    Stored s = tasks.stored(project, id);
    RepoWork w = work(s, repoName);
    List<String> files = git.uncommitted(Path.of(w.worktree()));
    ScopeReport scope = rules.scope(project, repoName, files);
    List<String> approved = repo.readDevState(s.folder()).approved();
    Set<String> need = new LinkedHashSet<>();
    for (List<String> l : List.of(scope.forbidden(), scope.schema(), scope.dependency())) {
      l.stream().filter(f -> !approved.contains(repoName + ":" + f)).forEach(need::add);
    }
    return new ScopeCheck(repoName, files, scope, files.stream().filter(SecretFiles::isSecret).toList(), List.copyOf(need));
  }

  /** FR-32 / NFR-03: only the PMO approves editing a forbidden folder, a DB schema or a dependency file; for one commit. */
  public synchronized ScopeCheck approve(String project, String id, String repoName, List<String> files, Actor actor) {
    if (actor != Actor.PMO) {
      throw new PmoOnlyException("Chỉ PMO được duyệt đổi DB schema, dependency hoặc thư mục cấm");
    }
    Stored s = tasks.stored(project, id);
    work(s, repoName);
    DevState dev = repo.readDevState(s.folder());
    Set<String> all = new LinkedHashSet<>(dev.approved());
    files.forEach(f -> all.add(repoName + ":" + f));
    repo.writeDevState(s.folder(), dev.withApproved(List.copyOf(all)));
    tasks.history(s, actor, repoName, "scope: thư mục cấm, DB schema, dependency", repoName + ": PMO duyệt sửa " + files);
    return checkScope(project, id, repoName);
  }

  /** FR-26: commit everything in the worktree with the ticket code; refuses secret files and unapproved scope. */
  public synchronized View commit(String project, String id, String repoName, String message, Actor actor) {
    Stored s = tasks.stored(project, id);
    if (s.task().status() != TaskStatus.IN_DEV) {
      throw new IllegalArgumentException("Chỉ commit khi ticket đang triển khai");
    }
    if (message == null || message.isBlank()) {
      throw new IllegalArgumentException("Chưa nhập nội dung commit");
    }
    ScopeCheck check = checkScope(project, id, repoName);
    if (check.files().isEmpty()) {
      throw new IllegalArgumentException(repoName + ": không có thay đổi để commit");
    }
    if (!check.secrets().isEmpty()) {
      throw new IllegalArgumentException("Không commit file bí mật: " + check.secrets());
    }
    if (!check.needApproval().isEmpty()) {
      throw new PmoOnlyException("Cần PMO duyệt trước khi commit: " + check.needApproval());
    }
    RepoWork w = work(s, repoName);
    requireCheckedOut(w);
    String text = rules.commitMessage(project, s.task().id(), message);
    git.commitAll(Path.of(w.worktree()), text);
    DevState dev = repo.readDevState(s.folder());
    repo.writeDevState(s.folder(), dev.withApproved(dev.approved().stream().filter(a -> !a.startsWith(repoName + ":")).toList()));
    tasks.history(s, actor, repoName, "git.commit_template, scope",
        repoName + ": commit \"" + text + "\" (" + check.files().size() + " file)");
    writeChanges(project, id);
    tasks.changed(project, id);
    return view(project, id);
  }

  /** FR-31 / AIR-13: runs the quality gate of every repo of the ticket, writes tests.md, remembers which commit passed. */
  public synchronized List<QualityGate.Result> gate(String project, String id, Actor actor) {
    Stored s = tasks.stored(project, id);
    DevState dev = repo.readDevState(s.folder());
    if (dev.repos().isEmpty()) {
      throw new IllegalArgumentException("Chưa có worktree; bấm Bắt đầu triển khai trước");
    }
    List<QualityGate.Result> all = new ArrayList<>();
    StringBuilder md = new StringBuilder("# Test và quality gate – " + id + "\n\n_Chạy lúc " + now() + "_\n");
    for (RepoWork w : dev.repos()) {
      requireCheckedOut(w);
      Path wt = Path.of(w.worktree());
      Set<String> touched = new LinkedHashSet<>(git.changedFiles(wt, w.baseCommit(), "HEAD"));
      touched.addAll(git.uncommitted(wt));
      List<QualityGate.Result> results = rules.runGate(project, w.repo(), wt, List.copyOf(touched));
      List<String> dirty = git.uncommitted(wt);
      boolean passed = results.stream().allMatch(QualityGate.Result::passed) && dirty.isEmpty();
      dev = dev.with(w.withGate(passed ? git.head(wt) : null));
      all.addAll(results);
      md.append("\n## ").append(w.repo()).append(" – ").append(passed ? "ĐẠT" : "KHÔNG ĐẠT").append("\n\n");
      if (!dirty.isEmpty()) {
        md.append("Còn ").append(dirty.size()).append(" file chưa commit: gate chỉ tính khi đã commit hết.\n\n");
      }
      for (QualityGate.Result r : results) {
        md.append("### ").append(r.service()).append("\n\n| Bước | Lệnh | Kết quả | Thời gian |\n| --- | --- | --- | --- |\n");
        r.steps().forEach(st -> md.append("| ").append(st.name()).append(" | `").append(st.command()).append("` | ")
            .append(st.passed() ? "đạt" : "không đạt (" + st.exitCode() + ")").append(" | ").append(st.millis()).append(" ms |\n"));
        r.steps().stream().filter(st -> (!st.passed() || st.name().equals("layering")) && !st.output().isBlank())
            .forEach(st -> md.append("\n<details><summary>").append(st.name()).append("</summary>\n\n```\n").append(st.output())
                .append("\n```\n\n</details>\n"));
        md.append('\n');
      }
    }
    repo.writeDevState(s.folder(), dev);
    tasks.writeDoc(project, id, "tests.md", md.toString(), Actor.DEVMAP);
    tasks.history(s, actor, null, "quality: tách lớp, phân tích tĩnh, unit test", "Chạy quality gate: "
        + (dev.repos().stream().allMatch(w -> w.gateHead() != null) ? "đạt" : "không đạt"));
    tasks.changed(project, id);
    return all;
  }

  /**
   * FR-27 / FR-30 / AC-05: the PMO's confirmation is this call. Pushes the commit that passed the gate and SA verification to the
   * ticket branch of every changed repo (never a protected branch, never forced), then marks the ticket Done.
   */
  public synchronized View confirmPush(String project, String id, Actor actor) {
    if (actor != Actor.PMO) {
      throw new PmoOnlyException("Chỉ PMO được xác nhận push");
    }
    Stored s = tasks.stored(project, id);
    if (s.task().status() != TaskStatus.AWAITING_PUSH) {
      throw new IllegalArgumentException("Chỉ push khi ticket đang chờ PMO xác nhận");
    }
    DevState dev = repo.readDevState(s.folder());
    for (RepoWork w : dev.repos()) {
      Path wt = Path.of(w.worktree());
      String head = git.tip(wt, w.branch());
      if (head.equals(w.baseCommit()) || head.equals(w.pushedHead())) {
        continue; // nothing to push, or already pushed
      }
      if (!head.equals(w.gateHead())) {
        throw new IllegalArgumentException(w.repo() + ": commit hiện tại chưa qua quality gate và SA kiểm chứng; trả về Dev");
      }
      if (!dev.direct() && rules.isProtected(project, w.branch())) {
        throw new IllegalArgumentException(w.repo() + ": không push vào branch được bảo vệ " + w.branch());
      }
      if (!git.hasOrigin(wt)) {
        throw new IllegalStateException(w.repo() + ": repo không có remote origin");
      }
      git.push(wt, head, w.branch());
      dev = dev.with(w.withPushed(head));
      repo.writeDevState(s.folder(), dev);
      tasks.history(s, actor, w.repo(), "git.protected_branches, PMO xác nhận push",
          w.repo() + ": PMO xác nhận push " + head.substring(0, 8) + " → origin/" + w.branch());
    }
    writeChanges(project, id);
    tasks.transition(project, id, TaskStatus.DONE.code(), actor);
    return view(project, id);
  }

  /** FR-26: changes.md = branch, commits and files per repo, regenerated from git after every step. */
  public void writeChanges(String project, String id) {
    StringBuilder md = new StringBuilder("# Thay đổi – " + id + "\n");
    for (RepoView r : view(project, id).repos()) {
      md.append("\n## ").append(r.repo()).append("\n\n- Branch: `").append(r.branch()).append("` (nhánh nền `").append(r.baseBranch())
          .append("` @ ").append(r.baseCommit() == null ? "-" : r.baseCommit().substring(0, 8)).append(")\n");
      md.append("- Push: ").append(r.pushedHead() == null ? "chưa" : "đã push " + r.pushedHead().substring(0, 8)).append('\n');
      md.append("- Commit:\n");
      r.commits().forEach(c -> md.append("  - ").append(c).append('\n'));
      md.append("- File sửa:\n");
      r.files().forEach(f -> md.append("  - ").append(f).append('\n'));
      if (!r.uncommitted().isEmpty()) {
        md.append("- Chưa commit: ").append(String.join(", ", r.uncommitted())).append('\n');
      }
    }
    tasks.writeDoc(project, id, "changes.md", md.toString(), Actor.DEVMAP);
  }

  /**
   * PMO: code is edited in the service repo itself, not a copy. Switches the repo to the ticket branch only when nothing would be
   * lost: refuses uncommitted changes and a branch another ticket is still developing.
   */
  // ponytail: one ticket per repo at a time (one checkout); back to worktrees if parallel tickets on one repo matter (NFR-07)
  private void checkoutInRepo(String project, String id, String name, Path main, String target, String base) {
    String current = git.currentBranch(main);
    List<String> dirty = git.uncommitted(main);
    if (target.equals(current)) {
      if (!dirty.isEmpty()) {
        throw new IllegalStateException(name + ": còn thay đổi chưa commit, commit trước khi pull nhánh nền");
      }
      return;
    }
    if (!dirty.isEmpty()) {
      throw new IllegalStateException(name + ": repo đang ở nhánh " + current + " với " + dirty.size()
          + " file chưa commit; commit hoặc stash trước khi DevMap chuyển sang " + target);
    }
    tasks.list(project).stream()
        // waiting for push needs only its branch ref, not the checkout
        .filter(x -> !x.id().equals(id) && x.repos().contains(name) && x.branch().equals(current)
            && (x.status() == TaskStatus.IN_DEV || x.status() == TaskStatus.SA_VERIFY))
        .findFirst().ifPresent(x -> {
          throw new IllegalStateException(name + ": repo đang dùng cho " + x.id() + " (nhánh " + current + "); xong ticket đó trước");
        });
    git.checkout(main, target, base);
  }

  private boolean checkedOut(RepoWork w) {
    return w.branch().equals(git.currentBranch(Path.of(w.worktree())));
  }

  /** Commit and gate work on the files on disk: they must be this ticket's branch, never another ticket's checkout. */
  private void requireCheckedOut(RepoWork w) {
    if (!checkedOut(w)) {
      throw new IllegalStateException(w.repo() + ": repo đang ở nhánh " + git.currentBranch(Path.of(w.worktree())) + ", không phải "
          + w.branch() + "; bấm \"Pull nhánh nền\" để chuyển về ticket này");
    }
  }

  /**
   * Tickets started before code moved into the service repo: the branch cannot be checked out while its old worktree holds it.
   * Uncommitted work there is stashed (returned) and applied to the service repo after checkout, never dropped.
   */
  private String dropOldWorktree(String id, String name, Path main, RepoWork w) {
    if (w == null || Path.of(w.worktree()).toAbsolutePath().normalize().equals(main.toAbsolutePath().normalize())
        || !Files.exists(Path.of(w.worktree()))) {
      return null;
    }
    Path old = Path.of(w.worktree());
    String stash = null;
    if (!git.uncommitted(old).isEmpty()) {
      List<String> dirty = git.uncommitted(main);
      if (!dirty.isEmpty()) {
        throw new IllegalStateException(name + ": worktree cũ " + w.worktree() + " và repo service đều còn thay đổi chưa commit ("
            + dirty.size() + " file ở repo service); commit hoặc stash repo service trước");
      }
      stash = git.stash(old, "devmap " + id + " " + name + ": worktree cũ");
    }
    git.removeWorktree(main, old);
    return stash;
  }

  private RepoWork work(Stored s, String repoName) {
    RepoWork w = repo.readDevState(s.folder()).repo(repoName);
    if (w == null) {
      throw new NoSuchElementException(repoName + ": chưa có worktree");
    }
    return w;
  }

  private static RepoConfig repoConfig(Project p, String name) {
    return p.config().repos().stream().filter(r -> r.name().equals(name)).findFirst()
        .orElseThrow(() -> new NoSuchElementException("Dự án không có repo " + name));
  }

  private static String now() {
    return LocalDateTime.now().format(TIME);
  }
}
