package com.devmap.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devmap.SampleRepos;
import com.devmap.config.ProjectRepository;
import com.devmap.config.ProjectService;
import com.devmap.config.ProjectService.RepoInput;
import com.devmap.config.ServiceDetector;
import com.devmap.config.WorkspaceRepository;
import com.devmap.git.GitService;
import com.devmap.graph.Graph.Direction;
import com.devmap.graph.Graph.Edge;
import com.devmap.graph.Graph.Node;
import com.devmap.graph.GraphService.Report;
import com.devmap.secret.SecretMasker;
import com.devmap.ticket.RequestCleaner;
import com.devmap.ticket.TaskRepository;
import com.devmap.ticket.TaskService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GraphServiceTest {

  @TempDir
  Path tmp;

  Path repos;
  Path graphDir;
  GraphService graphs;
  TaskService tasks;

  @BeforeEach
  void setUp() throws Exception {
    repos = SampleRepos.create(tmp.resolve("repos"));
    ProjectService projects = new ProjectService(new WorkspaceRepository(tmp.resolve("home")), new ProjectRepository(),
        new ServiceDetector(), new GitService());
    projects.create("shop", tmp.resolve("data"), "#ff8800", List.of(
        new RepoInput(repos.resolve("order-service").toString(), "test"),
        new RepoInput(repos.resolve("payment-service").toString(), "main"),
        new RepoInput(repos.resolve("notification-service").toString(), "main")));
    tasks = new TaskService(projects, new TaskRepository(), new SecretMasker(), new RequestCleaner(), e -> { }, new GitService());
    graphs = new GraphService(projects, new GitService(), new GraphRepository(), new SourceIndexer(), new SecretMasker(), tasks, e -> { });
    graphDir = tmp.resolve("data/shop/graph");
  }

  private static boolean has(Graph g, String from, String to, String type) {
    return g.edges().stream().anyMatch(e -> e.from().equals(from) && e.to().equals(to) && e.type().equals(type));
  }

  /** AC-01 (graph part) / AIR-05: nothing is indexed until the PMO asks; an incremental update without an index does nothing. */
  @Test
  void noScanUntilPmoAsks() throws Exception {
    Report r = graphs.update("shop", false);
    assertThat(r.repos()).allSatisfy(rr -> assertThat(rr.note()).contains("chưa có chỉ mục"));
    try (Stream<Path> files = Files.list(graphDir)) {
      assertThat(files).isEmpty();
    }
    assertThat(graphs.graph("shop").serviceLevel().nodes()).extracting(Node::label)
        .containsExactlyInAnyOrder("order-service", "payment-service", "notification-service");
  }

  /** FR-19/20: one graph across 3 repos with services, APIs, signatures, tables, topics and calls with confidence. */
  @Test
  void fullScanBuildsCrossRepoGraph() throws Exception {
    Report r = graphs.update("shop", true);
    assertThat(r.repos()).allSatisfy(rr -> assertThat(rr.parsed()).isPositive());
    Graph g = graphs.graph("shop");

    String charge = "fn:order-service:PaymentClient.charge";
    String pay = "api:payment-service:POST /api/payments";
    assertThat(g.node(pay).signature()).contains("code").contains("openapi");
    assertThat(g.edges()).contains(new Edge(charge, pay, "http", "medium", "src/main/java/com/shop/order/client/PaymentClient.java"));
    assertThat(has(g, pay, "fn:payment-service:PaymentController.create", "handles")).isTrue();
    assertThat(has(g, "fn:order-service:OrderService.create", charge, "calls")).isTrue();
    assertThat(has(g, "fn:order-service:OrderService.create", "topic:order.created", "publishes")).isTrue();
    assertThat(has(g, "topic:order.created", "fn:notification-service:OrderCreatedListener.onOrderCreated", "consumes")).isTrue();
    assertThat(has(g, "cls:order-service:OrderRepository", "table:orders", "uses_table")).isTrue();
    assertThat(has(g, "cls:order-service:OrderReportController", "cls:order-service:OrderRepository", "depends"))
        .as("Controller → Repository layer violation is visible in the graph").isTrue();
    assertThat(g.edges()).anyMatch(e -> e.from().equals("svc:order-service") && e.to().equals("svc:payment-service")
        && e.type().equals("config") && e.confidence().equals("medium"));
    assertThat(g.node("fn:order-service:OrderService.create").signature()).contains("public Order create(String customerId");

    Graph.Subgraph services = g.serviceLevel();
    assertThat(services.edges()).anyMatch(e -> e.from().equals("svc:order-service") && e.to().equals("svc:payment-service")
        && e.type().equals("http"));
    assertThat(services.edges()).anyMatch(e -> e.from().equals("svc:order-service") && e.to().equals("topic:order.created"));
    Graph.Subgraph order = g.serviceDetail("svc:order-service");
    assertThat(order.nodes()).extracting(Node::id).contains("api:order-service:POST /api/orders", pay, "topic:order.created");
  }

  /** FR-36: the index lives in the data folder, so the fake IPs/tokens of the samples never reach it. */
  @Test
  void indexHoldsNoSecretsAndNeverReadsEnvFiles() throws Exception {
    graphs.update("shop", true);
    String all;
    try (Stream<Path> files = Files.list(graphDir)) {
      all = String.join("", files.map(f -> {
        try {
          return Files.readString(f);
        } catch (Exception e) {
          throw new IllegalStateException(e);
        }
      }).toList());
    }
    assertThat(all).doesNotContain("10.12.0.4", "203.0.113.10", "tok_fake_", "FakeOrderPass", ".env");
    assertThat(all).doesNotContain("\"body\"");
  }

  /** FR-21 / AIR-06: after new commits on the base branch only the changed files are parsed again. */
  @Test
  void incrementalUpdateParsesOnlyTheGitDiff() throws Exception {
    graphs.update("shop", true);
    Path order = repos.resolve("order-service");
    SampleRepos.commit(order, "test", Map.of(
        "src/main/java/com/shop/order/service/RefundService.java", """
            package com.shop.order.service;
            public class RefundService {
              public void refund(Long orderId, java.math.BigDecimal amount) { }
            }
            """,
        "src/main/java/com/shop/order/web/OrderReportController.java", "package com.shop.order.web;\nclass OrderReportController { }\n",
        "README.md", "# changed"));
    SampleRepos.commit(order, "main", Map.of("src/main/java/com/shop/order/Ignored.java", "class Ignored { }"));

    long t0 = System.currentTimeMillis();
    Report r = graphs.update("shop", false);
    assertThat(System.currentTimeMillis() - t0).as("NFR-11: incremental < 20 s").isLessThan(20_000);
    assertThat(r.repos()).filteredOn(rr -> rr.repo().equals("order-service")).singleElement()
        .satisfies(rr -> assertThat(rr.parsed()).isEqualTo(2));
    assertThat(r.repos()).filteredOn(rr -> !rr.repo().equals("order-service"))
        .allSatisfy(rr -> assertThat(rr.note()).isEqualTo("đã mới nhất"));

    Graph g = graphs.graph("shop");
    assertThat(g.node("fn:order-service:RefundService.refund")).isNotNull();
    assertThat(g.node("cls:order-service:Ignored")).as("main is not order-service's base branch").isNull();
    assertThat(has(g, "cls:order-service:OrderReportController", "cls:order-service:OrderRepository", "depends")).isFalse();

    SampleRepos.commit(order, "test", java.util.Collections.singletonMap(
        "src/main/java/com/shop/order/service/RefundService.java", null));
    assertThat(graphs.update("shop", false).repos().get(0).removed()).isEqualTo(1);
    assertThat(graphs.graph("shop").node("fn:order-service:RefundService.refund")).isNull();
  }

  /** AIR-09 + FR-23 blast radius: who depends on PaymentService.pay within 3 hops, across repos. */
  @Test
  void blastRadiusCrossesServices() {
    graphs.update("shop", true);
    Graph g = graphs.graph("shop");
    Graph.Subgraph radius = g.neighborhood(List.of("fn:payment-service:PaymentService.pay"), 3, Direction.IN, 200);
    assertThat(radius.nodes()).extracting(Node::id).contains("fn:payment-service:PaymentController.create",
        "api:payment-service:POST /api/payments", "fn:order-service:PaymentClient.charge");
    Graph.Subgraph deeper = g.neighborhood(List.of("fn:payment-service:PaymentService.pay"), 9, Direction.IN, 200);
    assertThat(deeper.nodes()).as("depth is capped at 3").hasSameSizeAs(radius.nodes());
    assertThat(g.search("paymentclient", 5)).extracting(Node::id).startsWith("cls:order-service:PaymentClient");
  }

  /** FR-22: manual edges survive a full re-index; bad ones are refused. */
  @Test
  void manualEdgesSurviveReindex() {
    graphs.update("shop", true);
    ManualEdge e = graphs.addManualEdge("shop", "svc:notification-service", "svc:payment-service", "http", "low", "gọi qua gateway");
    assertThatThrownBy(() -> graphs.addManualEdge("shop", "svc:nope", "svc:payment-service", "http", "low", ""))
        .isInstanceOf(IllegalArgumentException.class);
    graphs.update("shop", true);
    assertThat(graphs.graph("shop").edges())
        .contains(new Edge("svc:notification-service", "svc:payment-service", "http", "low", "manual"));
    graphs.removeManualEdge("shop", e.id());
    assertThat(graphs.manualEdges("shop")).isEmpty();
    assertThat(has(graphs.graph("shop"), "svc:notification-service", "svc:payment-service", "http")).isFalse();
  }

  @Test
  void ticketHighlightsItsServices() {
    String id = tasks.create("shop", "FEAT", null, "refund", "Hoàn tiền ở payment-service").task().id();
    assertThat(graphs.ticketServices("shop", id)).containsExactly("svc:payment-service");
  }
}
