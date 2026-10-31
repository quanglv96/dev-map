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
      tasks.transition("shop", id, "sa_analysis", Actor.SA);
      tasks.transition("shop", id, "awaiting_approval", Actor.SA);
    }
    assertThatThrownBy(() -> tasks.transition("shop", a, "in_dev", Actor.PMO)).hasMessageContaining("Không chuyển được");
    assertThatThrownBy(() -> tasks.transition("shop", a, "ready", Actor.PMO)).hasMessageContaining("plan.md");
    tasks.writeDoc("shop", a, "plan.md", "# Plan", Actor.PMO);
    tasks.writeDoc("shop", b, "plan.md", "# Plan", Actor.PMO);
    assertThatThrownBy(() -> tasks.transition("shop", a, "ready", Actor.SA)).isInstanceOf(PmoOnlyException.class);

    tasks.transition("shop", a, "ready", Actor.PMO);
    tasks.transition("shop", b, "ready", Actor.PMO);
    assertThat(tasks.queue("shop")).extracting(Task::id).containsExactly(a, b);
    assertThat(tasks.reorderQueue("shop", List.of(b, a))).extracting(Task::id).containsExactly(b, a);
    assertThatThrownBy(() -> tasks.reorderQueue("shop", List.of(b))).hasMessageContaining("Hàng đợi");
    assertThat(tasks.detail("shop", a).queuePosition()).isEqualTo(2);

    tasks.transition("shop", b, "in_dev", Actor.DEV);
    assertThat(tasks.queue("shop")).extracting(Task::id).containsExactly(a);
    tasks.transition("shop", b, "sa_verify", Actor.DEV);
    tasks.transition("shop", b, "awaiting_push", Actor.SA);
    assertThat(tasks.overview()).singleElement().satisfies(o -> {
      assertThat(o.pending()).extracting(Task::id).containsExactly(b);
      assertThat(o.running()).extracting(Task::id).containsExactly(a);
    });
    assertThatThrownBy(() -> tasks.transition("shop", b, "done", Actor.DEV)).isInstanceOf(PmoOnlyException.class);
    assertThat(tasks.transition("shop", b, "done", Actor.PMO).status()).isEqualTo(TaskStatus.DONE);
    assertThat(tasks.detail("shop", b).actions()).isEmpty();
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

  /** FR-10 / AC-03 (manual part): reopening keeps the code, logs the bug in bugs/, rebuilds spec.json. */
  @Test
  void reopenKeepsCode() throws Exception {
    String id = tasks.create("shop", "FEAT", "SHOP-1", "refund", "hoàn tiền ở order-service").task().id();
    assertThatThrownBy(() -> tasks.reopen("shop", id, "bug", "lỗi", Actor.PMO)).hasMessageContaining("đã Done");
    tasks.transition("shop", id, "sa_analysis", Actor.PMO);
    tasks.transition("shop", id, "awaiting_approval", Actor.PMO);
    tasks.writeDoc("shop", id, "plan.md", "p", Actor.PMO);
    tasks.setRepos("shop", id, List.of(), Actor.PMO); // no code in this manual flow: no worktree, gate or push to check
    for (String s : List.of("ready", "in_dev", "sa_verify", "awaiting_push", "done")) {
      tasks.transition("shop", id, s, Actor.PMO);
    }
    assertThatThrownBy(() -> tasks.reopen("shop", id, "bug", "x", Actor.SA)).isInstanceOf(PmoOnlyException.class);

    Task t = tasks.reopen("shop", id, "bug", "Hoàn tiền lần 2 bị trùng ở payment-service", Actor.PMO);
    assertThat(t.id()).isEqualTo("SHOP-1");
    assertThat(t.status()).isEqualTo(TaskStatus.SA_ANALYSIS);
    assertThat(t.reopened()).isEqualTo(1);
    Path f = folder(id);
    assertThat(Files.readString(f.resolve("bugs/BUG-01.md"))).contains("trùng");
    assertThat(tasks.list("shop")).hasSize(1);
    Spec spec = new TaskRepository().readSpec(f);
    assertThat(spec.actionType()).isEqualTo("REOPEN_BUG");
    assertThat(spec.targetModules()).containsExactly("payment-service");
    assertThat(spec.source()).isEqualTo("bugs/BUG-01.md");

    for (String s : List.of("awaiting_approval", "ready", "in_dev", "sa_verify", "awaiting_push", "done")) {
      tasks.transition("shop", id, s, Actor.PMO);
    }
    tasks.reopen("shop", id, "logic", "Thêm giới hạn số lần hoàn", Actor.PMO);
    assertThat(tasks.detail("shop", id).docs()).contains("bugs/BUG-01.md", "bugs/LOGIC-01.md");
    assertThat(new TaskRepository().readSpec(f).actionType()).isEqualTo("ADD_LOGIC");
    assertThat(new TaskRepository().readSpec(f).targetModules()).containsExactly("payment-service");
  }
}
