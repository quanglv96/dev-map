package com.devmap.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devmap.SampleRepos;
import com.devmap.config.ProjectRepository;
import com.devmap.config.ProjectService;
import com.devmap.config.ProjectService.RepoInput;
import com.devmap.config.ServiceDetector;
import com.devmap.config.WorkspaceRepository;
import com.devmap.git.GitService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuleServiceTest {

  @TempDir
  Path tmp;

  RuleService rules;
  Path repos;
  Path data;

  @BeforeEach
  void setUp() throws Exception {
    repos = SampleRepos.create(tmp.resolve("repos"));
    ProjectService projects = new ProjectService(new WorkspaceRepository(tmp.resolve("home")), new ProjectRepository(),
        new ServiceDetector(), new GitService());
    projects.create("shop", tmp.resolve("data"), "#ff8800", List.of(
        new RepoInput(repos.resolve("order-service").toString(), "test"),
        new RepoInput(repos.resolve("payment-service").toString(), "main")), false);
    rules = new RuleService(projects, new RuleRepository(), new QualityGate());
    data = tmp.resolve("data/shop");
  }

  /** FR-29: preset by service kind ← project default ← service file; files keep only the differences. */
  @Test
  void serviceRulesInheritProjectDefault() throws Exception {
    Rules order = rules.effective("shop", "order-service");
    assertThat(order.preset()).isEqualTo("maven");
    assertThat(order.quality().unitTestCommand()).isEqualTo("mvn -q test");
    assertThat(order.quality().layering()).isTrue();

    Rules def = rules.projectDefault("shop");
    rules.saveDefault("shop", new Rules(null, new Rules.Git("[{code}] {message}", List.of("main", "prod")),
        def.quality(), new Rules.Scope(List.of("legacy/"), def.scope().schemaPaths(), def.scope().dependencyFiles())));
    assertThat(Files.readString(data.resolve("rules/_default.devrules.yml"))).contains("legacy/", "prod").doesNotContain("schema_paths");

    order = rules.effective("shop", "order-service");
    Rules.Quality q = order.quality();
    rules.saveService("shop", "order-service", new Rules("maven", order.git(), new Rules.Quality(q.layering(), q.staticAnalysis(),
        q.staticCommands(), true, "mvn -q -Pfast test", q.integrationTest(), q.integrationTestCommand(), 5), order.scope()));
    String stored = Files.readString(data.resolve("rules/order-service.devrules.yml"));
    assertThat(stored).contains("mvn -q -Pfast test", "timeout_minutes: 5").doesNotContain("layering", "preset");

    order = rules.effective("shop", "order-service");
    assertThat(order.quality().unitTestCommand()).isEqualTo("mvn -q -Pfast test");
    assertThat(order.scope().forbiddenPaths()).containsExactly("legacy/");
    assertThat(rules.effective("shop", "payment-service").quality().unitTestCommand()).isEqualTo("mvn -q test");
    assertThat(rules.view("shop").overridden()).containsEntry("order-service", true).containsEntry("payment-service", false);

    rules.resetService("shop", "order-service");
    assertThat(rules.effective("shop", "order-service").quality().unitTestCommand()).isEqualTo("mvn -q test");
    assertThat(rules.commitMessage("shop", "NYX-1", "add refund")).isEqualTo("[NYX-1] add refund");
  }

  /** FR-30: base branches are always protected, on top of the configured list. */
  @Test
  void protectedBranchesAlwaysIncludeBaseBranches() {
    Rules def = rules.projectDefault("shop");
    rules.saveDefault("shop", new Rules(null, new Rules.Git("{code}: {message}", List.of("release/*")), def.quality(), def.scope()));
    assertThat(rules.protectedBranches("shop")).containsExactly("release/*", "test", "main");
    assertThat(rules.isProtected("shop", "test")).isTrue();
    assertThat(rules.isProtected("shop", "release/1.2")).isTrue();
    assertThat(rules.isProtected("shop", "feature/NYX-1-refund")).isFalse();
    assertThatThrownBy(() -> rules.saveDefault("shop", new Rules(null, new Rules.Git("{message}", List.of()), def.quality(), def.scope())))
        .hasMessageContaining("{code}");
  }

  /** FR-32: forbidden folders, DB schema and dependency changes are told apart per service. */
  @Test
  void scopeSortsChangedFiles() {
    Rules def = rules.projectDefault("shop");
    rules.saveDefault("shop", new Rules(null, def.git(), def.quality(),
        new Rules.Scope(List.of("src/main/java/com/shop/order/legacy/"), def.scope().schemaPaths(), def.scope().dependencyFiles())));
    RuleService.ScopeReport r = rules.scope("shop", "order-service", List.of("src/main/java/com/shop/order/Order.java",
        "src/main/java/com/shop/order/legacy/Old.java", "src/main/resources/db/migration/V2__refund.sql", "pom.xml"));
    assertThat(r.forbidden()).containsExactly("src/main/java/com/shop/order/legacy/Old.java");
    assertThat(r.schema()).containsExactly("src/main/resources/db/migration/V2__refund.sql");
    assertThat(r.dependency()).containsExactly("pom.xml");
    assertThat(rules.scope("shop", "order-service", List.of("README.md")).clean()).isTrue();
  }

  @Test
  void globs() {
    assertThat(Glob.matches("pom.xml", "a/b/pom.xml")).isTrue();
    assertThat(Glob.matches("**/*.sql", "V1.sql")).isTrue();
    assertThat(Glob.matches("**/db/migration/**", "src/main/resources/db/migration/V1.sql")).isTrue();
    assertThat(Glob.matches("legacy/", "legacy/x/y.java")).isTrue();
    assertThat(Glob.matches("legacy/", "src/legacy/y.java")).isFalse();
    assertThat(Glob.matches("src/*.java", "src/a/b.java")).isFalse();
  }

  /** AC-10 / AIR-12: OrderReportController calls OrderRepository directly; payment-service is clean. */
  @Test
  void layerCheckFindsControllerCallingRepository() {
    assertThat(LayerChecker.check(repos.resolve("order-service"))).singleElement().satisfies(v -> {
      assertThat(v.controller()).isEqualTo("OrderReportController");
      assertThat(v.repository()).isEqualTo("OrderRepository");
      assertThat(v.file()).endsWith("web/OrderReportController.java");
    });
    assertThat(LayerChecker.check(repos.resolve("payment-service"))).isEmpty();
  }

  /** FR-31: configured commands run in the service folder; a missing command for an enabled step fails the gate. */
  @Test
  void qualityGateRunsConfiguredCommands() {
    QualityGate gate = new QualityGate();
    QualityGate.Result ok = gate.run("payment-service", repos.resolve("payment-service"),
        new Rules.Quality(true, true, List.of("git status"), true, "java -version", false, "", 1));
    assertThat(ok.passed()).isTrue();
    assertThat(ok.steps()).extracting(QualityGate.Step::name).containsExactly("layering", "static", "unit_test");

    QualityGate.Result bad = gate.run("order-service", repos.resolve("order-service"),
        new Rules.Quality(true, false, List.of(), true, "java -no-such-flag", true, "", 1));
    assertThat(bad.passed()).isFalse();
    assertThat(bad.steps()).extracting(QualityGate.Step::passed).containsExactly(false, false, false);
    assertThat(bad.steps().get(0).output()).contains("OrderReportController");
    assertThat(bad.steps().get(2).output()).contains("chưa cấu hình");
  }
}
