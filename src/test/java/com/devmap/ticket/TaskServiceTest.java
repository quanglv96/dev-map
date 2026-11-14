package com.devmap.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devmap.SampleRepos;
import com.devmap.audit.AuditLog;
import com.devmap.config.ProjectConfig;
import com.devmap.config.ProjectRepository;
import com.devmap.config.ProjectService;
import com.devmap.config.ProjectService.RepoInput;
import com.devmap.config.ServiceDetector;
import com.devmap.config.WorkspaceRepository;
import com.devmap.git.GitService;
import com.devmap.secret.SecretMasker;
import com.devmap.ticket.TaskService.Actor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskServiceTest {

  @TempDir
  Path tmp;

  ProjectService projects;
  TaskService tasks;
  final List<Object> events = new ArrayList<>();
  Path data;

  @BeforeEach
  void setUp() throws Exception {
    Path repos = SampleRepos.create(tmp.resolve("repos"));
    projects = new ProjectService(new WorkspaceRepository(tmp.resolve("home")), new ProjectRepository(),
        new ServiceDetector(), new GitService());
    projects.create("shop", tmp.resolve("data"), "#ff8800", List.of(
        new RepoInput(repos.resolve("order-service").toString(), "test"),
        new RepoInput(repos.resolve("payment-service").toString(), "main"),
        new RepoInput(repos.resolve("notification-service").toString(), "main")));
    AuditLog audit = new AuditLog(new SecretMasker());
    tasks = new TaskService(projects, new TaskRepository(), new SecretMasker(), new RequestCleaner(), e -> {
      if (e instanceof AuditLog.Event a) {
        audit.on(a);
      } else {
        events.add(e);
      }
    }, new GitService());
    data = tmp.resolve("data/shop/tasks");
  }

  private Path folder(String id) throws Exception {
    try (var dirs = Files.list(data)) {
      return dirs.filter(d -> d.getFileName().toString().startsWith(id + "-")).findFirst().orElseThrow();
    }
  }

  private static String allText(Path dir) throws Exception {
    StringBuilder sb = new StringBuilder();
    try (var files = Files.walk(dir)) {
      for (Path f : files.filter(Files::isRegularFile).toList()) {
        sb.append(Files.readString(f));
      }
    }
    return sb.toString();
  }

  /** AC-12 (local part) + FR-05/06/36/44: filler and a token in the request become a compact, masked spec.json. */
  @Test
  void createsTicketWithMaskedSpec() throws Exception {
    TaskService.Created c = tasks.create("shop", "FEAT", "NYX-231", "", """
        Chào em, anh nhờ em thêm hoàn tiền một phần cho order-service nhé, gọi payment service để hoàn.
        Token test là ghp_abcdefghijklmnopqrstuvwxyz0123456789, server 10.12.0.45.
        Không được đổi API hiện có. Cảm ơn em!""");

    Task t = c.task();
    assertThat(t.id()).isEqualTo("NYX-231");
    assertThat(t.status()).isEqualTo(TaskStatus.NEW);
    assertThat(t.repos()).containsExactly("order-service", "payment-service");
    assertThat(t.title()).isEqualTo("Thêm hoàn tiền một phần cho order-service, gọi payment service để hoàn");
    assertThat(t.branch()).startsWith("feature/NYX-231-them-hoan-tien-mot-phan");
    assertThat(c.maskedCount()).isEqualTo(2);

    Path f = folder("NYX-231");
    String spec = Files.readString(f.resolve("spec.json"));
    assertThat(spec).contains("\"issue_id\" : \"NYX-231\"", "\"action_type\" : \"FEAT\"", "\"source\" : \"task.md\"",
        "\"target_modules\" : [ \"order-service\", \"payment-service\" ]", "Không được đổi API hiện có.");
    assertThat(spec).doesNotContainIgnoringCase("chào").doesNotContainIgnoringCase("cảm ơn").doesNotContain("nhé");
    assertThat(allText(f)).doesNotContain("ghp_abcdefghij", "10.12.0.45");
    assertThat(Files.readString(f.resolve("task.md"))).startsWith("---\nid: NYX-231\ntype: FEAT\n")
        .contains("## Yêu cầu gốc của PMO", "[MASKED_TOKEN]");
    assertThat(Files.readString(f.resolve("history.md"))).contains("PMO · Tạo ticket FEAT, đã che 2");
    assertThat(events).containsExactly(new TaskChanged("shop", "NYX-231"));
    // NFR-10: the same action is in the project audit log, masked
    String log = Files.readString(f.getParent().getParent().resolve("logs/audit.jsonl"));
    assertThat(log).contains("\"actor\":\"PMO\"", "\"ticket\":\"NYX-231\"", "Tạo ticket FEAT").doesNotContain("ghp_abcdefghij");
  }

  @Test
  void ticketCodes() {
    assertThat(tasks.create("shop", "BUG", null, "a", "x").task().id()).isEqualTo("BUG-001");
    assertThat(tasks.create("shop", "BUG", " ", "b", "x").task().id()).isEqualTo("BUG-002");
    assertThat(tasks.create("shop", "MAINT", "", "c", "x").task().id()).isEqualTo("MAINT-001");
    assertThatThrownBy(() -> tasks.create("shop", "BUG", "BUG-001", "d", "x")).hasMessageContaining("đã tồn tại");
    assertThatThrownBy(() -> tasks.create("shop", "BUG", "a/b", "d", "x")).hasMessageContaining("Mã ticket");
    assertThatThrownBy(() -> tasks.create("shop", "EPIC", null, "d", "x")).hasMessageContaining("Loại ticket");

    ProjectConfig c = projects.get("shop").config();
    projects.update("shop", new ProjectConfig("shop", c.color(), "manual", "SHOP-\\d+", c.branchPatterns(), c.repos(),
        c.environments(), false, null));
    assertThatThrownBy(() -> tasks.create("shop", "FEAT", "X-1", "d", "x")).hasMessageContaining("không khớp mẫu");
    assertThat(tasks.create("shop", "FEAT", "SHOP-7", "d", "x").task().branch()).isEqualTo("feature/SHOP-7-d");

    projects.update("shop", new ProjectConfig("shop", c.color(), "auto", null, c.branchPatterns(), c.repos(),
        c.environments(), false, null));
    assertThat(tasks.create("shop", "FEAT", "IGNORED-1", "e", "x").task().id()).isEqualTo("FEAT-001");
  }

  /** FR-08 / NFR-03: the flow, its two PMO gates enforced in the service, and the queue (FR-07). */
  @Test
  void flowGatesAndQueue() {
    String a = tasks.create("shop", "MAINT", null, "nâng spring", "x").task().id();
    String b = tasks.create("shop", "MAINT", null, "vá log4j", "x").task().id();
    for (String id : List.of(a, b)) {
      tasks.transition("shop", id, "analysis", Actor.SA);
      tasks.transition("shop", id, "wait_approval", Actor.SA);
    }
    assertThatThrownBy(() -> tasks.transition("shop", a, "verify", Actor.PMO)).hasMessageContaining("Không chuyển được");
    assertThatThrownBy(() -> tasks.transition("shop", a, "ready", Actor.PMO)).hasMessageContaining("plan.md");
    tasks.writeDoc("shop", a, "plan.md", "# Plan", Actor.PMO);
    tasks.writeDoc("shop", b, "plan.md", "# Plan", Actor.PMO);
    // FR-86: never implemented yet, so the approval queues it (READY) instead of going straight to IMPLEMENT
    assertThatThrownBy(() -> tasks.transition("shop", a, "implement", Actor.PMO)).hasMessageContaining("Chốt lần đầu");
    assertThatThrownBy(() -> tasks.transition("shop", a, "ready", Actor.SA)).isInstanceOf(PmoOnlyException.class);

    tasks.transition("shop", a, "ready", Actor.PMO);
    tasks.transition("shop", b, "ready", Actor.PMO);
    assertThat(tasks.queue("shop")).extracting(Task::id).containsExactly(a, b);
    assertThat(tasks.reorderQueue("shop", List.of(b, a))).extracting(Task::id).containsExactly(b, a);
    assertThatThrownBy(() -> tasks.reorderQueue("shop", List.of(b))).hasMessageContaining("Hàng đợi");
    assertThat(tasks.detail("shop", a).queuePosition()).isEqualTo(2);

    tasks.transition("shop", b, "implement", Actor.DEV);
    assertThat(tasks.queue("shop")).extracting(Task::id).containsExactly(a);
    tasks.transition("shop", b, "verify", Actor.DEV);
    tasks.transition("shop", b, "wait_confirm", Actor.SA);
    assertThat(tasks.overview()).singleElement().satisfies(o -> {
      assertThat(o.pending()).extracting(Task::id).containsExactly(b);
      assertThat(o.running()).extracting(Task::id).containsExactly(a);
    });
    assertThatThrownBy(() -> tasks.transition("shop", b, "done", Actor.DEV)).isInstanceOf(PmoOnlyException.class);
    assertThat(tasks.transition("shop", b, "done", Actor.PMO).status()).isEqualTo(TaskStatus.DONE);
    assertThat(tasks.detail("shop", b).actions()).isEmpty();
  }

  /** D-68: a new decision after approval goes back to SA through the PMO, from in_dev or awaiting_push; SA can then write plan.md again. */
  @Test
  void pmoSendsApprovedTicketBackToAnalysis() {
    String id = tasks.create("shop", "MAINT", null, "outbox", "x").task().id();
    tasks.transition("shop", id, "analysis", Actor.SA);
    tasks.transition("shop", id, "wait_approval", Actor.SA);
    tasks.writeDoc("shop", id, "plan.md", "# Plan", Actor.PMO);
    tasks.transition("shop", id, "ready", Actor.PMO);
    tasks.transition("shop", id, "implement", Actor.DEV);

    assertThat(tasks.detail("shop", id).actions()).contains(new TaskService.Action("analysis", true));
    assertThatThrownBy(() -> tasks.transition("shop", id, "analysis", Actor.DEV)).isInstanceOf(PmoOnlyException.class)
        .hasMessageContaining("phân tích lại");
    assertThat(tasks.transition("shop", id, "analysis", Actor.PMO).status()).isEqualTo(TaskStatus.ANALYSIS);
  }

  /** FR-09: every save of analysis.md / plan.md is a version with a diff. */
  @Test
  void versionsAndDiff() throws Exception {
    String id = tasks.create("shop", "FEAT", null, "refund", "x").task().id();
    tasks.writeDoc("shop", id, "analysis.md", "# Analysis\n\nAC-1: refund full\n", Actor.PMO);
    tasks.writeDoc("shop", id, "analysis.md", "# Analysis\n\nAC-1: refund full\n", Actor.PMO);
    TaskService.Doc d = tasks.writeDoc("shop", id, "analysis.md",
        "# Analysis\n\nAC-1: refund partial\nAC-2: db at 10.12.0.47\n", Actor.PMO);

    assertThat(d.versions()).isEqualTo(2);
    assertThat(d.maskedCount()).isEqualTo(1);
    assertThat(tasks.detail("shop", id).task().version()).isEqualTo(2);
    assertThat(tasks.diff("shop", id, "analysis.md", 1, 2))
        .contains("-AC-1: refund full", "+AC-1: refund partial", "+AC-2: db at [MASKED_IP]");
    assertThat(tasks.version("shop", id, "analysis.md", 1)).contains("refund full");
    assertThatThrownBy(() -> tasks.version("shop", id, "../../config.yml", 1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> tasks.writeDoc("shop", id, "verify.md", "x", Actor.PMO)).hasMessageContaining("PMO chỉ sửa");
    assertThatThrownBy(() -> tasks.readDoc("shop", id, "../config.yml")).isInstanceOf(IllegalArgumentException.class);
    assertThat(Files.readString(folder(id).resolve("history.md"))).contains("Sửa analysis.md → v1", "→ v2, đã che 1");
  }

  /** D-66: PMO cancels a ticket with a masked reason; it leaves the queue, and only the PMO brings it back to New. */
  @Test
  void pmoCancelsTicketWithReason() throws Exception {
    String id = tasks.create("shop", "FEAT", "SHOP-9", "refund", "hoàn tiền").task().id();
    tasks.transition("shop", id, "analysis", Actor.PMO);
    tasks.transition("shop", id, "wait_approval", Actor.PMO);
    tasks.writeDoc("shop", id, "plan.md", "p", Actor.PMO);
    tasks.transition("shop", id, "ready", Actor.PMO);
    assertThatThrownBy(() -> tasks.cancel("shop", id, "x", Actor.SA)).isInstanceOf(PmoOnlyException.class);
    assertThatThrownBy(() -> tasks.cancel("shop", id, " ", Actor.PMO)).hasMessageContaining("lý do");

    Task t = tasks.cancel("shop", id, "Khách bỏ yêu cầu, server 10.0.0.12", Actor.PMO);
    assertThat(t.status()).isEqualTo(TaskStatus.CANCELLED);
    assertThat(tasks.queue("shop")).isEmpty();
    assertThat(tasks.detail("shop", id).actions()).containsExactly(new TaskService.Action("new", true));
    String log = Files.readString(folder(id).resolve("history.md"));
    assertThat(log).contains("Huỷ ticket từ ready", "Khách bỏ yêu cầu").doesNotContain("10.0.0.12");
    assertThatThrownBy(() -> tasks.cancel("shop", id, "x", Actor.PMO)).hasMessageContaining("đã cancelled");
    assertThatThrownBy(() -> tasks.reopen("shop", id, "logic", "làm lại", Actor.PMO)).hasMessageContaining("đã Done");
    assertThatThrownBy(() -> tasks.transition("shop", id, "done", Actor.PMO)).hasMessageContaining("Không chuyển được");
    assertThatThrownBy(() -> tasks.transition("shop", id, "new", Actor.SA)).isInstanceOf(PmoOnlyException.class);
    assertThat(tasks.transition("shop", id, "new", Actor.PMO).status()).isEqualTo(TaskStatus.NEW);
    assertThat(Files.readString(folder(id).resolve("history.md"))).contains("cancelled → new");
  }

  /** D-72: a stuck ticket is moved by hand to any status, skipping next() and the gate/push checks; PMO only, with a reason. */
  @Test
  void pmoForcesStatusWithReason() throws Exception {
    String id = tasks.create("shop", "FEAT", "SHOP-8", "refund", "hoàn tiền").task().id();
    assertThatThrownBy(() -> tasks.forceStatus("shop", id, "implement", "x", Actor.SA)).isInstanceOf(PmoOnlyException.class);
    assertThatThrownBy(() -> tasks.forceStatus("shop", id, "implement", " ", Actor.PMO)).hasMessageContaining("lý do");
    assertThatThrownBy(() -> tasks.forceStatus("shop", id, "new", "x", Actor.PMO)).hasMessageContaining("đã ở new");

    assertThat(tasks.forceStatus("shop", id, "ready", "kẹt, token 10.0.0.12", Actor.PMO).status()).isEqualTo(TaskStatus.READY);
    assertThat(tasks.queue("shop")).extracting(Task::id).containsExactly(id);
    assertThat(tasks.forceStatus("shop", id, "done", "đã push tay", Actor.PMO).status()).isEqualTo(TaskStatus.DONE);
    assertThat(tasks.queue("shop")).isEmpty();
    String log = Files.readString(folder(id).resolve("history.md"));
    assertThat(log).contains("Đổi trạng thái bằng tay new → ready", "ready → done, lý do: đã push tay").doesNotContain("10.0.0.12");
  }

  /** FR-10 / AC-03 (manual part): reopening keeps the code, logs the bug in bugs/, rebuilds spec.json. */
  @Test
  void reopenKeepsCode() throws Exception {
    String id = tasks.create("shop", "FEAT", "SHOP-1", "refund", "hoàn tiền ở order-service").task().id();
    assertThatThrownBy(() -> tasks.reopen("shop", id, "bug", "lỗi", Actor.PMO)).hasMessageContaining("đã Done");
    tasks.transition("shop", id, "analysis", Actor.PMO);
    tasks.transition("shop", id, "wait_approval", Actor.PMO);
    tasks.writeDoc("shop", id, "plan.md", "p", Actor.PMO);
    tasks.setRepos("shop", id, List.of(), Actor.PMO); // no code in this manual flow: no worktree, gate or push to check
    for (String s : List.of("ready", "implement", "verify", "wait_confirm", "done")) {
      tasks.transition("shop", id, s, Actor.PMO);
    }
    assertThatThrownBy(() -> tasks.reopen("shop", id, "bug", "x", Actor.SA)).isInstanceOf(PmoOnlyException.class);

    Task t = tasks.reopen("shop", id, "bug", "Hoàn tiền lần 2 bị trùng ở payment-service", Actor.PMO);
    assertThat(t.id()).isEqualTo("SHOP-1");
    assertThat(t.status()).isEqualTo(TaskStatus.ANALYSIS);
    assertThat(t.reopened()).isEqualTo(1);
    Path f = folder(id);
    assertThat(Files.readString(f.resolve("bugs/BUG-01.md"))).contains("trùng");
    assertThat(tasks.list("shop")).hasSize(1);
    Spec spec = new TaskRepository().readSpec(f);
    assertThat(spec.actionType()).isEqualTo("REOPEN_BUG");
    assertThat(spec.targetModules()).containsExactly("payment-service");
    assertThat(spec.source()).isEqualTo("bugs/BUG-01.md");

    for (String s : List.of("wait_approval", "ready", "implement", "verify", "wait_confirm", "done")) {
      tasks.transition("shop", id, s, Actor.PMO);
    }
    tasks.reopen("shop", id, "logic", "Thêm giới hạn số lần hoàn", Actor.PMO);
    assertThat(tasks.detail("shop", id).docs()).contains("bugs/BUG-01.md", "bugs/LOGIC-01.md");
    assertThat(new TaskRepository().readSpec(f).actionType()).isEqualTo("ADD_LOGIC");
    assertThat(new TaskRepository().readSpec(f).targetModules()).containsExactly("payment-service");
  }

  /** AC-40 / FR-86: every pair of states outside the table is refused by the server, whoever asks. */
  @Test
  void everyPairOutsideTheTableIsRefused() {
    String id = tasks.create("shop", "FEAT", null, "bảng chuyển", "x").task().id();
    java.util.Map<TaskStatus, java.util.Set<TaskStatus>> table = java.util.Map.of(
        TaskStatus.NEW, java.util.Set.of(TaskStatus.ANALYSIS),
        TaskStatus.ANALYSIS, java.util.Set.of(TaskStatus.WAIT_APPROVAL),
        TaskStatus.WAIT_APPROVAL, java.util.Set.of(TaskStatus.READY, TaskStatus.IMPLEMENT, TaskStatus.ANALYSIS),
        TaskStatus.READY, java.util.Set.of(TaskStatus.IMPLEMENT),
        TaskStatus.IMPLEMENT, java.util.Set.of(TaskStatus.VERIFY, TaskStatus.ANALYSIS),
        TaskStatus.VERIFY, java.util.Set.of(TaskStatus.IMPLEMENT, TaskStatus.ANALYSIS, TaskStatus.WAIT_CONFIRM),
        TaskStatus.WAIT_CONFIRM, java.util.Set.of(TaskStatus.IMPLEMENT, TaskStatus.DONE),
        TaskStatus.DONE, java.util.Set.of(TaskStatus.ANALYSIS),
        TaskStatus.CANCELLED, java.util.Set.of(TaskStatus.NEW));
    for (TaskStatus from : TaskStatus.values()) {
      assertThat(from.next()).as(from.code()).isEqualTo(table.get(from));
      tasks.forceStatus("shop", id, from == TaskStatus.NEW ? "analysis" : from.code(), "test", Actor.PMO);
      if (from == TaskStatus.NEW) {
        tasks.forceStatus("shop", id, "new", "test", Actor.PMO);
      }
      for (TaskStatus to : TaskStatus.values()) {
        if (!table.get(from).contains(to)) {
          for (Actor actor : Actor.values()) {
            assertThatThrownBy(() -> tasks.transition("shop", id, to.code(), actor)).as(from + "→" + to)
                .hasMessageContaining("ngoài bảng FR-86");
          }
        }
      }
    }
    // DONE → ANALYSIS only through "Báo bug / Bổ sung logic"; IMPLEMENT/VERIFY → ANALYSIS never by an agent
    tasks.forceStatus("shop", id, "done", "test", Actor.PMO);
    assertThatThrownBy(() -> tasks.transition("shop", id, "analysis", Actor.PMO)).hasMessageContaining("Báo bug");
    tasks.forceStatus("shop", id, "implement", "test", Actor.PMO);
    assertThatThrownBy(() -> tasks.transition("shop", id, "analysis", Actor.SA)).isInstanceOf(PmoOnlyException.class);
    assertThat(tasks.transition("shop", id, "analysis", Actor.DEVMAP).status()).isEqualTo(TaskStatus.ANALYSIS);
  }

  /** AC-41 / FR-85: old tickets keep every byte of their documents; "Mở lại" becomes ANALYSIS with the reopened flag. */
  @Test
  void oldStatusesMigrate() throws Exception {
    String a = tasks.create("shop", "FEAT", null, "cũ mở lại", "yêu cầu A").task().id();
    String b = tasks.create("shop", "BUG", null, "cũ đang dev", "yêu cầu B").task().id();
    tasks.writeDoc("shop", a, "analysis.md", "- AC-1: giữ nguyên", Actor.SA);
    Path fa = folder(a).resolve("task.md");
    Path fb = folder(b).resolve("task.md");
    Files.writeString(fa, Files.readString(fa).replace("status: new", "status: reopened"));
    Files.writeString(fb, Files.readString(fb).replace("status: new", "status: in_dev"));
    String bodyA = new TaskRepository().readTaskBody(folder(a));

    Task ta = tasks.detail("shop", a).task();
    assertThat(ta.status()).isEqualTo(TaskStatus.ANALYSIS);
    assertThat(ta.reopened()).isEqualTo(1);
    assertThat(tasks.detail("shop", b).task().status()).isEqualTo(TaskStatus.IMPLEMENT);

    assertThat(tasks.migrate()).isEqualTo(2);
    assertThat(Files.readString(fa)).contains("status: analysis", "reopened: 1").doesNotContain("status: reopened");
    assertThat(Files.readString(fb)).contains("status: implement");
    assertThat(new TaskRepository().readTaskBody(folder(a))).isEqualTo(bodyA);
    assertThat(tasks.readDoc("shop", a, "analysis.md").content()).isEqualTo("- AC-1: giữ nguyên");
    assertThat(Files.readString(folder(a).resolve("history.md"))).contains("Migration mô hình trạng thái mới");
    assertThat(tasks.migrate()).isZero();
    assertThat(TaskStatus.parse("sa_verify")).isEqualTo(TaskStatus.VERIFY);
    assertThat(TaskStatus.parse("awaiting_push")).isEqualTo(TaskStatus.WAIT_CONFIRM);
  }
}
