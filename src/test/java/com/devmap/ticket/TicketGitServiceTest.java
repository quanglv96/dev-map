package com.devmap.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devmap.SampleRepos;
import com.devmap.config.ProjectRepository;
import com.devmap.config.ProjectService;
import com.devmap.config.ProjectService.RepoInput;
import com.devmap.config.ServiceDetector;
import com.devmap.config.WorkspaceRepository;
import com.devmap.git.GitService;
import com.devmap.rule.QualityGate;
import com.devmap.rule.RuleRepository;
import com.devmap.rule.RuleService;
import com.devmap.rule.Rules;
import com.devmap.secret.SecretMasker;
import com.devmap.ticket.TaskService.Actor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TicketGitServiceTest {

  @TempDir
  Path tmp;

  final GitService git = new GitService();
  final List<Object> events = new ArrayList<>();
  Path repos;
  TaskService tasks;
  RuleService rules;
  TicketGitService dev;

  @BeforeEach
  void setUp() throws Exception {
    repos = SampleRepos.create(tmp.resolve("repos"));
    for (String svc : List.of("order-service", "payment-service")) {
      Path r = repos.resolve(svc);
      git.run(r, "config", "user.name", "devmap-test");
      git.run(r, "config", "user.email", "devmap@example.invalid");
      Path bare = tmp.resolve("origin").resolve(svc + ".git");
      assertThat(git.run(tmp, "clone", "-q", "--bare", r.toString(), bare.toString()).ok()).isTrue();
      git.run(r, "remote", "add", "origin", bare.toString());
      git.run(r, "fetch", "-q", "origin");
    }
    ProjectService projects = new ProjectService(new WorkspaceRepository(tmp.resolve("home")), new ProjectRepository(),
        new ServiceDetector(), git);
    projects.create("shop", tmp.resolve("data"), "#ff8800", List.of(
        new RepoInput(repos.resolve("order-service").toString(), "test"),
        new RepoInput(repos.resolve("payment-service").toString(), "main")), false);
    TaskRepository taskRepo = new TaskRepository();
    tasks = new TaskService(projects, taskRepo, new SecretMasker(), new RequestCleaner(), events::add, git);
    rules = new RuleService(projects, new RuleRepository(), new QualityGate());
    Rules def = rules.projectDefault("shop");
    // cheap commands instead of mvn: the gate mechanics are what is under test here
    rules.saveDefault("shop", new Rules("maven", def.git(), new Rules.Quality(true, true, List.of("git status"), true, "java -version",
        false, "", 2), def.scope()));
    dev = new TicketGitService(tasks, taskRepo, git, rules, events::add, tmp.resolve("home"));
  }

  private String readyTicket() {
    String id = tasks.create("shop", "FEAT", "NYX-7", "Hoàn tiền", "Thêm hoàn tiền ở order-service, gọi payment-service").task().id();
    tasks.transition("shop", id, "sa_analysis", Actor.SA);
    tasks.transition("shop", id, "awaiting_approval", Actor.SA);
    tasks.writeDoc("shop", id, "plan.md", "# Plan", Actor.PMO);
    tasks.transition("shop", id, "ready", Actor.PMO);
    return id;
  }

  /**
   * FR-24/26/27, NFR-07, AC-05, AC-10: worktree per repo from the newest base branch, commits carry the code, scope needs the PMO,
   * the layering violation fails the gate, SA only after a passing gate, push only by the PMO and only of the verified commit.
   */
  @Test
  void ticketFromWorktreeToConfirmedPush() throws Exception {
    // someone pushed to the base branch after DevMap last fetched: start must pull it
    SampleRepos.commit(repos.resolve("order-service"), "test", Map.of("NEWS.md", "new on test\n"));
    git.run(repos.resolve("order-service"), "push", "-q", "origin", "test");
    String id = readyTicket();

    TicketGitService.View v = dev.start("shop", id, Actor.DEV);
    assertThat(tasks.detail("shop", id).task().status()).isEqualTo(TaskStatus.IN_DEV);
    assertThat(v.repos()).extracting(TicketGitService.RepoView::repo).containsExactlyInAnyOrder("order-service", "payment-service");
    Path order = Path.of(v.repos().stream().filter(r -> r.repo().equals("order-service")).findFirst().orElseThrow().worktree());
    assertThat(order).startsWith(tmp.resolve("home/worktrees/shop/NYX-7"));
    assertThat(order.resolve("NEWS.md")).exists();
    assertThat(git.run(order, "branch", "--show-current").output()).isEqualTo("feature/NYX-7-hoan-tien");
    assertThat(git.run(repos.resolve("order-service"), "branch", "--show-current").output()).isEqualTo("main"); // PMO's checkout untouched
    assertThat(events).contains(new TicketGitService.BasePulled("shop"));

    // FR-32: a dependency file needs the PMO; FR-34: a secret file is never committed
    Files.writeString(order.resolve("src/main/java/com/shop/order/service/RefundService.java"),
        "package com.shop.order.service;\n\npublic class RefundService {\n}\n");
    Path pom = order.resolve("pom.xml");
    Files.writeString(pom, Files.readString(pom).replace("</project>", "<!-- dep -->\n</project>"));
    TicketGitService.ScopeCheck sc = dev.checkScope("shop", id, "order-service");
    assertThat(sc.needApproval()).containsExactly("pom.xml");
    assertThatThrownBy(() -> dev.commit("shop", id, "order-service", "add refund", Actor.DEV)).isInstanceOf(PmoOnlyException.class);
    assertThatThrownBy(() -> dev.approve("shop", id, "order-service", List.of("pom.xml"), Actor.DEV)).isInstanceOf(PmoOnlyException.class);
    Files.writeString(order.resolve(".env"), "X=1\n");
    dev.approve("shop", id, "order-service", List.of("pom.xml"), Actor.PMO);
    assertThatThrownBy(() -> dev.commit("shop", id, "order-service", "add refund", Actor.DEV)).hasMessageContaining(".env");
    git.run(order, "checkout", "--", ".env");
    dev.commit("shop", id, "order-service", "add refund", Actor.DEV);
    assertThat(git.run(order, "log", "-1", "--format=%s").output()).isEqualTo("NYX-7: add refund");

    // AIR-13 / AC-10: SA only after a passing gate; the Controller → Repository shortcut fails it
    assertThatThrownBy(() -> tasks.transition("shop", id, "sa_verify", Actor.DEV)).hasMessageContaining("quality gate");
    List<QualityGate.Result> results = dev.gate("shop", id, Actor.DEV);
    assertThat(results).filteredOn(r -> r.service().equals("order-service")).singleElement()
        .satisfies(r -> assertThat(r.passed()).isFalse());
    assertThat(tasks.readDoc("shop", id, "tests.md").content()).contains("OrderReportController", "KHÔNG ĐẠT");
    Files.delete(order.resolve("src/main/java/com/shop/order/web/OrderReportController.java"));
    dev.commit("shop", id, "order-service", "route report through service", Actor.DEV);
    assertThat(dev.gate("shop", id, Actor.DEV)).allMatch(QualityGate.Result::passed);
    tasks.transition("shop", id, "sa_verify", Actor.DEV);
    tasks.transition("shop", id, "awaiting_push", Actor.SA);

    String changes = tasks.readDoc("shop", id, "changes.md").content();
    assertThat(changes).contains("feature/NYX-7-hoan-tien", "NYX-7: add refund", "RefundService.java", "Push: chưa");

    // AC-05: no push without the PMO, Done only through the confirmed push
    Path origin = tmp.resolve("origin/order-service.git");
    assertThatThrownBy(() -> tasks.transition("shop", id, "done", Actor.PMO)).hasMessageContaining("Chưa push");
    assertThatThrownBy(() -> dev.confirmPush("shop", id, Actor.DEV)).isInstanceOf(PmoOnlyException.class);
    assertThat(git.run(origin, "rev-parse", "--verify", "--quiet", "refs/heads/feature/NYX-7-hoan-tien").ok()).isFalse();
    dev.confirmPush("shop", id, Actor.PMO);
    assertThat(git.run(origin, "rev-parse", "refs/heads/feature/NYX-7-hoan-tien").output()).isEqualTo(git.head(order));
    assertThat(git.run(tmp.resolve("origin/payment-service.git"), "branch", "--list", "feature/*").output()).isEmpty();
    assertThat(tasks.detail("shop", id).task().status()).isEqualTo(TaskStatus.DONE);
    assertThat(tasks.readDoc("shop", id, "history.md").content()).contains("PMO xác nhận push");
  }

  /** FR-30: a ticket branch matching a protected pattern can neither be worked on nor pushed; there is no force flag. */
  @Test
  void protectedBranchesAreRefused() {
    Rules def = rules.projectDefault("shop");
    rules.saveDefault("shop", new Rules("maven", new Rules.Git("{code}: {message}", List.of("feature/*")), def.quality(), def.scope()));
    String id = readyTicket();
    assertThatThrownBy(() -> dev.start("shop", id, Actor.PMO)).hasMessageContaining("bảo vệ");
    assertThatThrownBy(() -> git.push(repos.resolve("order-service"), "0".repeat(40), "+main")).hasMessageContaining("không hợp lệ");
  }

  /** Commits made after the gate passed (e.g. by hand in the worktree) are not pushed until they pass again. */
  @Test
  void pushOnlyTheVerifiedCommit() throws Exception {
    String id = readyTicket();
    tasks.setRepos("shop", id, List.of("payment-service"), Actor.PMO);
    Path wt = Path.of(dev.start("shop", id, Actor.PMO).repos().get(0).worktree());
    Files.writeString(wt.resolve("CHANGELOG.md"), "refund\n");
    dev.commit("shop", id, "payment-service", "changelog", Actor.PMO);
    dev.gate("shop", id, Actor.PMO);
    tasks.transition("shop", id, "sa_verify", Actor.DEV);
    tasks.transition("shop", id, "awaiting_push", Actor.SA);
    Files.writeString(wt.resolve("CHANGELOG.md"), "refund v2\n");
    git.commitAll(wt, "NYX-7: sneaky");
    assertThatThrownBy(() -> dev.confirmPush("shop", id, Actor.PMO)).hasMessageContaining("chưa qua quality gate");
  }
}
