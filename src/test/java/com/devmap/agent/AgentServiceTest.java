package com.devmap.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devmap.SampleRepos;
import com.devmap.agent.AgentProvider.Call;
import com.devmap.agent.AgentProvider.Result;
import com.devmap.agent.AgentProvider.Usage;
import com.devmap.audit.AuditLog;
import com.devmap.config.ProjectRepository;
import com.devmap.config.ProjectService;
import com.devmap.config.ProjectService.RepoInput;
import com.devmap.config.ServiceDetector;
import com.devmap.config.WorkspaceRepository;
import com.devmap.git.GitService;
import com.devmap.graph.GraphRepository;
import com.devmap.graph.GraphService;
import com.devmap.graph.SourceIndexer;
import com.devmap.rule.ConventionService;
import com.devmap.rule.QualityGate;
import com.devmap.rule.RuleRepository;
import com.devmap.rule.RuleService;
import com.devmap.rule.Rules;
import com.devmap.secret.SecretMasker;
import com.devmap.ticket.RequestCleaner;
import com.devmap.ticket.TaskChanged;
import com.devmap.ticket.TaskRepository;
import com.devmap.ticket.TaskService;
import com.devmap.ticket.TaskService.Actor;
import com.devmap.ticket.TaskStatus;
import com.devmap.ticket.TicketGitService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

/**
 * Phase 5 with a scripted agent instead of Claude Code: the script talks to DevMap through the real MCP JSON-RPC handler, so the
 * tools, gates, context and logs under test are exactly what a real agent gets. Runs synchronously.
 */
class AgentServiceTest {

  @TempDir
  Path tmp;

  static final ObjectMapper JSON = new ObjectMapper();
  static final String REQUEST = "Chào em, nhờ em thêm hoàn tiền một phần cho order-service nhé. "
      + "Token ghp_abcdefghijklmnopqrstuvwxyz0123456789. Không được đổi API cũ. Cảm ơn!";

  final GitService git = new GitService();
  final List<Object> events = new ArrayList<>();
  final FakeAgent fake = new FakeAgent();
  Path repos;
  Path home;
  TaskService tasks;
  TicketGitService dev;
  GraphService graphs;
  AgentService agents;
  McpServer mcp;

  /** Runs one queued script per session and records what each session received. */
  static final class FakeAgent implements AgentProvider {
    final List<Call> calls = new ArrayList<>();
    final Deque<Function<Call, String>> scripts = new ArrayDeque<>();

    @Override
    public Result run(Call call, java.util.function.Consumer<Event> sink) {
      calls.add(call);
      sink.accept(new Event("tool", "scripted"));
      sink.accept(new Event("quota", "{\"status\":\"allowed_warning\",\"resetsAt\":1761900000,\"rateLimitType\":\"five_hour\","
          + "\"utilization\":0.82}"));
      return new Result(true, scripts.isEmpty() ? "ok" : scripts.poll().apply(call), new Usage(100, 20, 300, 50, 0.01));
    }
  }

  @BeforeEach
  void setUp() throws Exception {
    repos = SampleRepos.create(tmp.resolve("repos"));
    home = tmp.resolve("home");
    for (String svc : List.of("order-service", "payment-service")) {
      git.run(repos.resolve(svc), "config", "user.name", "devmap-test");
      git.run(repos.resolve(svc), "config", "user.email", "devmap@example.invalid");
    }
    ProjectService projects = new ProjectService(new WorkspaceRepository(home), new ProjectRepository(), new ServiceDetector(), git);
    projects.create("shop", tmp.resolve("data"), "#ff8800", List.of(
        new RepoInput(repos.resolve("order-service").toString(), "test"),
        new RepoInput(repos.resolve("payment-service").toString(), "main"),
        new RepoInput(repos.resolve("notification-service").toString(), "main")));
    SecretMasker masker = new SecretMasker();
    TaskRepository taskRepo = new TaskRepository();
    AuditLog audit = new AuditLog(masker);
    org.springframework.context.ApplicationEventPublisher publish = e -> {
      events.add(e);
      if (e instanceof AuditLog.Event a) {
        audit.on(a);
      }
      if (agents != null && e instanceof TaskChanged tc) {
        agents.onTaskChanged(tc);
      }
    };
    tasks = new TaskService(projects, taskRepo, masker, new RequestCleaner(), publish, git);
    RuleService rules = new RuleService(projects, new RuleRepository(), new QualityGate());
    Rules def = rules.projectDefault("shop");
    // cheap commands; layering is covered by RuleServiceTest (order-service has a known violation)
    rules.saveDefault("shop", new Rules("maven", def.git(), new Rules.Quality(false, true, List.of("git status"), true, "java -version",
        false, "", 2), def.scope()));
    dev = new TicketGitService(tasks, taskRepo, git, rules, publish);
    SourceIndexer indexer = new SourceIndexer();
    graphs = new GraphService(projects, git, new GraphRepository(), indexer, masker, tasks, e -> { });
    AgentTools tools = new AgentTools(tasks, dev, graphs, git, projects, rules, masker, new VerifyFacts(dev, graphs, git, indexer));
    ContextBuilder context = new ContextBuilder(tasks, graphs, new ConventionService(projects, new RuleRepository(), git, masker), rules,
        projects, new RequestCleaner(), masker);
    agents = new AgentService(tasks, dev, graphs, git, projects, context, tools, fake, new ChatRepository(), masker, publish,
        new MockEnvironment(), home, Runnable::run);
    mcp = new McpServer(agents, tools);
  }

  /** Calls a DevMap tool over MCP JSON-RPC like Claude Code does; "ERR " prefix when the tool refused. */
  String tool(Call c, String name, Map<String, ?> args) {
    JsonNode req = JSON.valueToTree(Map.of("jsonrpc", "2.0", "id", 7, "method", "tools/call",
        "params", Map.of("name", name, "arguments", args)));
    JsonNode res = mcp.handle(c.sessionToken(), req).path("result");
    return (res.path("isError").asBoolean() ? "ERR " : "") + res.path("content").get(0).path("text").asText();
  }

  String ticket(String code, String request) {
    return tasks.create("shop", "FEAT", code, "", request).task().id();
  }

  String inputLog(String id, int no) {
    return agents.inputLog("shop", id, no);
  }

  /** AC-07, AC-12, FR-37/38/40, AIR-11: three questions = three fresh sessions; no chat resent; summary.md carries the context. */
  /** New ticket with "analyse now": SA starts by itself; the quota line feeds the token/quota box, not the chat steps. */
  @Test
  void newTicketGoesToSaAndReportsTokensAndQuota() {
    String id = ticket("NYX-9", "Thêm hoàn tiền cho order-service");
    assertThat(agents.usage("shop").quota()).isEmpty();
    agents.analyzeNew("shop", id);
    assertThat(fake.calls).hasSize(1);
    assertThat(fake.calls.get(0).prompt()).contains(AgentService.AUTO_ANALYZE);
    assertThat(tasks.detail("shop", id).task().status()).isNotEqualTo(TaskStatus.NEW);
    assertThat(agents.chat("shop", id).messages()).last().satisfies(m -> {
      assertThat(m.from()).isEqualTo("SA");
      assertThat(m.steps()).noneMatch(st -> st.kind().equals("quota"));
    });
    AgentService.UsageView u = agents.usage("shop");
    assertThat(u.project().total()).isEqualTo(470);
    assertThat(u.today().total()).isEqualTo(470);
    assertThat(u.quota()).singleElement().satisfies(q -> {
      assertThat(q.type()).isEqualTo("five_hour");
      assertThat(q.status()).isEqualTo("allowed_warning");
      assertThat(q.resetsAt()).isEqualTo(1761900000L);
      assertThat(q.utilization()).isEqualTo(0.82);
    });
  }

  @Test
  void threeQuestionsAreThreeFreshSessions() throws Exception {
    String id = ticket("NYX-1", REQUEST);
    fake.scripts.add(c -> {
      JsonNode init = mcp.handle(c.sessionToken(), JSON.valueToTree(Map.of("jsonrpc", "2.0", "id", 1, "method", "initialize",
          "params", Map.of("protocolVersion", "2025-06-18"))));
      assertThat(init.path("result").path("capabilities").has("tools")).isTrue();
      assertThat(mcp.handle(c.sessionToken(), JSON.valueToTree(Map.of("jsonrpc", "2.0", "method", "notifications/initialized"))))
          .isNull();
      JsonNode list = mcp.handle(c.sessionToken(), JSON.valueToTree(Map.of("jsonrpc", "2.0", "id", 2, "method", "tools/list")));
      assertThat(list.findValuesAsText("name")).contains("get_spec", "search_graph", "get_snippet", "verify_facts", "write_ticket_doc")
          .doesNotContain("write_file", "edit_file", "delete_file", "commit", "run_quality_gate");
      assertThat(tool(c, "get_spec", Map.of())).contains("\"action_type\" : \"FEAT\"").contains("[MASKED_TOKEN]").doesNotContain("ghp_");
      assertThat(tool(c, "get_ticket_docs", Map.of("names", List.of("task.md")))).startsWith("ERR");
      assertThat(tool(c, "set_status", Map.of("to", "ready"))).startsWith("ERR").contains("PMO");
      assertThat(tool(c, "write_ticket_doc", Map.of("name", "summary.md", "content", "# Tóm tắt\n\nChốt: hoàn tiền tối đa 50%.\n")))
          .contains("Đã ghi");
      return "Phạm vi: chỉ order-service.";
    });
    agents.ask("shop", id, "", "Phạm vi hoàn tiền là gì?", false);
    fake.scripts.add(c -> "Tối đa 50%.");
    agents.ask("shop", id, "", "Tối đa bao nhiêu phần trăm?", false);
    fake.scripts.add(c -> "Đúng.");
    agents.ask("shop", id, "", "Xác nhận lại giúp mình", false);

    assertThat(fake.calls).hasSize(3);
    assertThat(fake.calls).extracting(Call::sessionToken).doesNotHaveDuplicates();
    assertThat(fake.calls).extracting(Call::systemPrompt).containsOnly(fake.calls.get(0).systemPrompt()); // static: prompt cache
    assertThat(fake.calls.get(0).systemPrompt())
        .contains("[SYSTEM REQUIREMENT & AI EXECUTION INSTRUCTIONS]", "Controller → Service → Repository");
    for (Call c : fake.calls) {
      assertThat(c.prompt()).contains("Phiên mới", "spec.json").doesNotContain("Chào em", "Cảm ơn", "ghp_", "nhé");
    }
    assertThat(fake.calls.get(1).prompt()).contains("hoàn tiền tối đa 50%").doesNotContain("Phạm vi hoàn tiền là gì", "chỉ order-service");
    // the second session did not write summary.md: DevMap kept its Q&A there for the third (FR-38)
    assertThat(fake.calls.get(2).prompt()).contains("Tối đa bao nhiêu phần trăm", "Tối đa 50%");
    assertThat(fake.calls.get(2).workDir()).doesNotExist(); // scratch folder removed after each session

    AgentService.ChatView chat = agents.chat("shop", id);
    assertThat(chat.messages()).extracting(ChatMessage::from).containsExactly("PMO", "SA", "PMO", "SA", "PMO", "SA");
    assertThat(chat.messages().get(1).context()).contains("spec.json");
    assertThat(chat.ticket().total()).isEqualTo(3 * 470);
    assertThat(chat.project().total()).isEqualTo(3 * 470);
    assertThat(tasks.detail("shop", id).task().status()).isEqualTo(TaskStatus.SA_ANALYSIS);
    String log = inputLog(id, 2);
    assertThat(log).contains("System prompt", "### get_spec", "### set_status").doesNotContain("ghp_", "Chào em");
    assertThat(Files.readString(tasks.folder("shop", id).resolve("history.md"))).contains("Hỏi SA", "Trả lời câu #2");
  }

  /** AC-06, AC-09, AC-10, FR-34/35: secrets refused by path and masked in content; snippets only; full scan once per question. */
  @Test
  void toolsNeverServeSecretsOrWholeFiles() throws Exception {
    String id = ticket("NYX-2", "Sửa lời gọi thanh toán ở order-service");
    fake.scripts.add(c -> {
      assertThat(tool(c, "get_snippet", Map.of("repo", "order-service", "file", ".env"))).startsWith("ERR").contains("bí mật");
      assertThat(tool(c, "get_snippet", Map.of("repo", "order-service", "file", "../payment-service/.env"))).startsWith("ERR");
      String yml = "src/main/resources/application.yml";
      assertThat(tool(c, "get_snippet", Map.of("repo", "order-service", "file", yml, "from", 1, "to", 200)))
          .startsWith("ERR").contains("80");
      assertThat(tool(c, "get_snippet", Map.of("node", "cls:order-service:OrderService"))).startsWith("ERR");
      String search = tool(c, "search_graph", Map.of("query", "PaymentClient.charge", "depth", 2, "direction", "in"));
      assertThat(search).contains("fn:order-service:PaymentClient.charge");
      String code = tool(c, "get_snippet", Map.of("node", "fn:order-service:PaymentClient.charge"));
      assertThat(code).contains("charge").doesNotContain("10.12.0.");
      assertThat(code.split("\n").length).isLessThanOrEqualTo(AgentTools.SNIPPET_LINES + 1);
      assertThat(tool(c, "get_snippet", Map.of("repo", "order-service", "file", yml, "from", 1, "to", 30)))
          .contains("[MASKED_").doesNotContain("10.12.0.50");
      assertThat(tool(c, "get_signatures", Map.of("node", "cls:order-service:OrderService"))).contains("create(");
      return "ok";
    });
    agents.ask("shop", id, "sa", "Lời gọi thanh toán đi qua đâu?", true);
    fake.scripts.add(c -> "ok");
    agents.ask("shop", id, "sa", "Còn gì nữa?", false);

    List<ChatMessage> chat = agents.chat("shop", id).messages();
    assertThat(chat.get(1).steps()).anyMatch(s -> s.text().contains("quét toàn bộ"));
    // the follow-up only checks the ticket's own repo against the base branch, and says so in one line
    assertThat(chat.get(3).steps()).noneMatch(s -> s.text().contains("quét toàn bộ"))
        .anyMatch(s -> s.text().equals("Chỉ mục 1 repo của ticket đã mới nhất theo nhánh nền"));
    assertThat(fake.calls.get(1).prompt()).contains("svc:order-service", "svc:payment-service"); // 2-hop graph context

    String log = inputLog(id, 2) + inputLog(id, 4);
    for (String secret : envValues()) {
      assertThat(log).doesNotContain(secret);
    }
    assertThat(log).doesNotContain("10.12.0.").doesNotContain(Files.readString(repos.resolve(
        "order-service/src/main/java/com/shop/order/client/PaymentClient.java")).strip());
    assertThatThrownBy(() -> agents.ask("shop", id, "dev", "code đi", false)).hasMessageContaining("chốt");
  }

  /** AC-11, FR-43, AIR-03/13/14, FR-25, FR-28: Dev widens scope and breaks a caller; SA verification catches both and returns it. */
  @Test
  void saVerificationCatchesScopeCreepAndChangedSignature() throws Exception {
    graphs.update("shop", true);
    String id = ticket("NYX-3", "Thêm ghi chú khi tạo đơn ở order-service");
    fake.scripts.add(c -> {
      tool(c, "write_ticket_doc", Map.of("name", "analysis.md",
          "content", "# Ghi chú\n\n- AC-1: Given đơn mới When tạo Then lưu ghi chú\n"));
      tool(c, "write_ticket_doc", Map.of("name", "plan.md", "content", "# Plan\n\n- order-service: sửa OrderService.java, thêm test\n"));
      tool(c, "write_ticket_doc", Map.of("name", "summary.md", "content", "Chờ PMO chốt"));
      assertThat(tool(c, "set_repos", Map.of("repos", List.of("nope-service")))).startsWith("ERR");
      assertThat(tool(c, "set_repos", Map.of("repos", List.of("order-service")))).contains("order-service"); // FR-14
      assertThat(tool(c, "set_status", Map.of("to", "awaiting_approval"))).contains("awaiting_approval");
      return "Đã gửi PMO chốt";
    });
    agents.ask("shop", id, "", "Phân tích giúp", false);
    assertThat(tasks.detail("shop", id).task().repos()).containsExactly("order-service");
    tasks.transition("shop", id, "ready", Actor.PMO);

    String old = "public Order create(String customerId, BigDecimal amount) {";
    fake.scripts.add(c -> {
      assertThat(c.prompt()).contains("Pull nhánh nền");
      assertThat(tool(c, "write_file", Map.of("repo", "order-service", "path", ".env", "content", "X=1"))).startsWith("ERR");
      assertThat(tool(c, "write_file", Map.of("repo", "order-service", "path", "../../escape.txt", "content", "x"))).startsWith("ERR");
      assertThat(tool(c, "edit_file", Map.of("repo", "order-service", "path", "src/main/java/com/shop/order/service/OrderService.java",
          "old", old, "new", "public Order create(String customerId, BigDecimal amount, String note) {"))).contains("Đã sửa");
      tool(c, "write_file", Map.of("repo", "order-service", "path", "src/main/java/com/shop/order/Extra.java",
          "content", "package com.shop.order;\n\nclass Extra {\n}\n"));
      assertThat(tool(c, "set_status", Map.of("to", "sa_verify"))).startsWith("ERR").contains("gate"); // AIR-13
      assertThat(tool(c, "commit", Map.of("repo", "order-service", "message", "add note"))).contains("NYX-3: add note");
      assertThat(tool(c, "run_quality_gate", Map.of())).contains("ĐẠT");
      assertThat(tool(c, "set_status", Map.of("to", "sa_verify"))).contains("sa_verify");
      return "Xong, chuyển SA";
    });
    fake.scripts.add(c -> { // started by DevMap itself after Dev handed over
      assertThat(c.prompt()).contains("KIỂM CHỨNG");
      String facts = tool(c, "verify_facts", Map.of());
      assertThat(facts).contains("order-service:src/main/java/com/shop/order/Extra.java", "OrderService.create",
          "order-service:OrderController.create");
      assertThat(tool(c, "git_diff", Map.of("repo", "order-service"))).contains("String note");
      assertThat(tool(c, "set_status", Map.of("to", "awaiting_push"))).startsWith("ERR").contains("verify.md");
      // the approved plan cannot be rewritten to make the deviation look planned
      assertThat(tool(c, "write_ticket_doc", Map.of("name", "plan.md", "content", "- order-service: Extra.java\n")))
          .startsWith("ERR").contains("đã được PMO chốt");
      tool(c, "write_ticket_doc", Map.of("name", "verify.md", "content", "AC-1 đạt.\n\nKết luận: ĐẠT"));
      assertThat(tool(c, "set_status", Map.of("to", "awaiting_push"))).startsWith("ERR").contains("Extra.java", "OrderService.create");
      tool(c, "write_ticket_doc", Map.of("name", "verify.md", "content", "Lệch phạm vi: Extra.java không thuộc AC nào. Hồi quy: "
          + "OrderService.create đổi chữ ký, OrderController.create chưa cập nhật.\n\nKết luận: KHÔNG ĐẠT"));
      assertThat(tool(c, "set_status", Map.of("to", "in_dev"))).contains("in_dev");
      return "Trả về Dev";
    });
    agents.ask("shop", id, "", "Triển khai", false);

    assertThat(fake.calls).hasSize(3);
    assertThat(tasks.detail("shop", id).task().status()).isEqualTo(TaskStatus.IN_DEV);
    assertThat(tasks.readDoc("shop", id, "verify.md").content()).contains("KHÔNG ĐẠT");
    assertThat(Files.readString(tasks.folder("shop", id).resolve("history.md"))).doesNotContain("→ awaiting_push");
    assertThat(agents.chat("shop", id).messages()).extracting(ChatMessage::mode).containsSubsequence("ask", "ask", "verify");

    // FR-28: the PMO commits by hand in the worktree; the next Dev session is told before it edits anything
    Path wt = Path.of(dev.view("shop", id).repos().get(0).worktree());
    Files.writeString(wt.resolve("PMO.md"), "sửa tay\n");
    git.run(wt, "add", "-A");
    git.run(wt, "commit", "-q", "-m", "NYX-3: PMO sửa tay");
    fake.scripts.add(c -> {
      assertThat(c.prompt()).contains("Commit PMO tự làm ở order-service", "PMO sửa tay", "KHÔNG ĐẠT");
      assertThat(tool(c, "delete_file", Map.of("repo", "order-service", "path", "src/main/java/com/shop/order/Extra.java")))
          .contains("Đã xoá");
      assertThat(tool(c, "delete_file", Map.of("repo", "order-service", "path", ".env"))).startsWith("ERR").contains("bí mật");
      return "ok";
    });
    agents.ask("shop", id, "", "Sửa theo verify.md", false);
    assertThat(tasks.readDoc("shop", id, "changes.md").content()).contains("PMO sửa tay");
    assertThat(Path.of(dev.view("shop", id).repos().get(0).worktree()).resolve("src/main/java/com/shop/order/Extra.java")).doesNotExist();
  }

  /** AC-04, FR-07, NFR-10: "Chạy hàng đợi" – Dev works Ready MAINT tickets one by one in the PMO's order; every step is logged. */
  @Test
  void queueRunsMaintTicketsInPmoOrder() throws Exception {
    List<String> ids = new ArrayList<>();
    for (String n : List.of("1", "2")) {
      String id = tasks.create("shop", "MAINT", "", "", "Dọn log ở order-service " + n).task().id();
      tasks.transition("shop", id, "sa_analysis", Actor.SA);
      tasks.writeDoc("shop", id, "analysis.md", "- AC-1: Given log When dọn Then gọn\n", Actor.SA);
      tasks.writeDoc("shop", id, "plan.md", "- order-service: Maint" + n + ".java\n", Actor.SA);
      tasks.transition("shop", id, "awaiting_approval", Actor.SA);
      tasks.transition("shop", id, "ready", Actor.PMO);
      ids.add(id);
    }
    tasks.reorderQueue("shop", List.of(ids.get(1), ids.get(0))); // the PMO puts the second one first
    List<String> started = new ArrayList<>();
    for (String n : List.of("2", "1")) {
      fake.scripts.add(c -> { // Dev
        started.add(c.prompt().contains(ids.get(1)) ? ids.get(1) : ids.get(0));
        if (n.equals("1")) { // the first ticket of the line already left development when this one starts
          assertThat(tasks.detail("shop", ids.get(1)).task().status()).isEqualTo(TaskStatus.AWAITING_PUSH);
        }
        tool(c, "write_file", Map.of("repo", "order-service", "path", "src/main/java/com/shop/order/Maint" + n + ".java",
            "content", "package com.shop.order;\n\nclass Maint" + n + " {\n}\n"));
        tool(c, "commit", Map.of("repo", "order-service", "message", "maint " + n));
        assertThat(tool(c, "run_quality_gate", Map.of())).contains("ĐẠT"); // regression: unit tests in the gate
        assertThat(tool(c, "set_status", Map.of("to", "sa_verify"))).contains("sa_verify");
        return "ok";
      });
      fake.scripts.add(c -> { // SA verification, started by DevMap
        tool(c, "write_ticket_doc", Map.of("name", "verify.md", "content", "Maint" + n + ".java theo plan. Kết luận: ĐẠT"));
        assertThat(tool(c, "set_status", Map.of("to", "awaiting_push"))).contains("awaiting_push");
        return "ok";
      });
    }
    agents.runQueue("shop", true); // the test runner is synchronous: the whole line has run when this returns

    assertThat(started).containsExactly(ids.get(1), ids.get(0));
    assertThat(ids).allSatisfy(id -> assertThat(tasks.detail("shop", id).task().status()).isEqualTo(TaskStatus.AWAITING_PUSH));
    // both wait for push from the one service repo: each keeps its own verified branch commit, whatever is checked out
    assertThat(ids).allSatisfy(id -> assertThat(dev.view("shop", id).repos().get(0).gatePassed()).isTrue());
    assertThat(dev.view("shop", ids.get(0)).repos().get(0).head()).isNotEqualTo(dev.view("shop", ids.get(1)).repos().get(0).head());
    AgentService.Activity a = agents.activity("shop");
    assertThat(a.queueRunning()).isFalse();
    assertThat(a.running()).isEmpty();
    assertThat(a.failed()).isEmpty();
    assertThatThrownBy(() -> agents.runQueue("shop", true)).hasMessageContaining("trống");

    String log = Files.readString(tmp.resolve("data/shop/logs/audit.jsonl"));
    assertThat(log).contains("Chạy hàng đợi", "\"actor\":\"DEV\"", "Công cụ write_file", "\"service\":\"order-service\"",
        "scope + chặn file bí mật", "git.commit_template", "cổng duyệt PMO", "\"actor\":\"SA\"").doesNotContain("class Maint");
  }

  /**
   * PMO approved: one action and Dev implements from the documents in the service repo itself, after being told which base
   * commits landed since SA analysed; the tab shows who works; SA verifies by itself; open questions reach the PMO.
   */
  @Test
  void approvedTicketImplementsItselfAfterReviewingNewBaseCommits() throws Exception {
    graphs.update("shop", true); // the index SA analysed against
    String id = ticket("NYX-5", "Dọn log ở order-service");
    tasks.setRepos("shop", id, List.of("order-service"), Actor.PMO);
    tasks.writeDoc("shop", id, "summary.md", "# Tóm tắt\n\n## Câu hỏi chờ PMO\n\n1. Giữ log lỗi?\n   (cả log cảnh báo)\n"
        + "2. Bao nhiêu ngày?\n\n## Việc tiếp theo\n- chờ PMO\n", Actor.SA);
    assertThat(agents.activity("shop").questions()).containsEntry(id, 2);
    assertThatThrownBy(() -> agents.implement("shop", id)).hasMessageContaining("PMO đã chốt");
    tasks.transition("shop", id, "sa_analysis", Actor.SA);
    tasks.writeDoc("shop", id, "summary.md", "# Tóm tắt\n\n## Câu hỏi chờ PMO\r\n- (không còn)\r\n", Actor.SA);
    tasks.writeDoc("shop", id, "plan.md", "- order-service: Cleanup.java\n", Actor.SA);
    tasks.transition("shop", id, "awaiting_approval", Actor.SA);
    tasks.transition("shop", id, "ready", Actor.PMO);
    assertThat(agents.activity("shop").questions()).doesNotContainKey(id);
    SampleRepos.commit(repos.resolve("order-service"), "test", Map.of("NEWS.md", "new on test\n")); // after SA's analysis

    fake.scripts.add(c -> { // Dev
      assertThat(c.prompt()).contains(AgentService.IMPLEMENT, "Pull nhánh nền mới nhất vào repo",
          "Nhánh nền có commit mới ở order-service", "NEWS.md", "Rà soát nhánh nền");
      assertThat(agents.activity("shop").working()).containsEntry(id, "DEV");
      assertThat(git.currentBranch(repos.resolve("order-service"))).isEqualTo(tasks.detail("shop", id).task().branch());
      tool(c, "write_file", Map.of("repo", "order-service", "path", "src/main/java/com/shop/order/Cleanup.java",
          "content", "package com.shop.order;\n\nclass Cleanup {\n}\n"));
      tool(c, "commit", Map.of("repo", "order-service", "message", "cleanup"));
      tool(c, "run_quality_gate", Map.of());
      assertThat(tool(c, "set_status", Map.of("to", "sa_verify"))).contains("sa_verify");
      return "ok";
    });
    fake.scripts.add(c -> { // SA verification, started by DevMap
      assertThat(agents.activity("shop").working()).containsEntry(id, "VERIFY");
      tool(c, "write_ticket_doc", Map.of("name", "verify.md", "content", "Cleanup.java theo plan. Kết luận: ĐẠT"));
      tool(c, "set_status", Map.of("to", "awaiting_push"));
      return "ok";
    });
    agents.implement("shop", id);

    assertThat(tasks.detail("shop", id).task().status()).isEqualTo(TaskStatus.AWAITING_PUSH);
    assertThat(repos.resolve("order-service/src/main/java/com/shop/order/Cleanup.java")).exists(); // in the service, not a copy
    assertThat(agents.activity("shop").working()).isEmpty();
  }

  /** FR-45, AC-12, FR-11, AC-03: Done drops the session context; a reopened ticket is answered from documents and must fix analysis. */
  @Test
  void doneDropsSessionContextAndReopenRebuildsFromDocs() throws Exception {
    String id = ticket("NYX-4", "Hoàn tiền một phần ở payment-service");
    tasks.setRepos("shop", id, List.of(), Actor.PMO); // documents only: no worktree in this flow
    fake.scripts.add(c -> {
      tool(c, "write_ticket_doc", Map.of("name", "analysis.md", "content", "- AC-1: hoàn tối đa số đã trả\n"));
      tool(c, "write_ticket_doc", Map.of("name", "plan.md", "content", "- payment-service: PaymentService.java\n"));
      tool(c, "write_ticket_doc", Map.of("name", "summary.md", "content", "Logic: hoàn tối đa số đã trả."));
      tool(c, "set_status", Map.of("to", "awaiting_approval"));
      return "ok";
    });
    agents.ask("shop", id, "", "Phân tích", false);
    Path sessionDir = home.resolve("sessions/shop").resolve(id);
    assertThat(sessionDir.resolve("2.log")).exists();
    for (String s : List.of("ready", "in_dev", "sa_verify", "awaiting_push", "done")) {
      tasks.transition("shop", id, s, s.equals("done") || s.equals("ready") ? Actor.PMO : Actor.SA);
    }
    assertThat(sessionDir).doesNotExist();
    assertThatThrownBy(() -> inputLog(id, 2)).hasMessageContaining("đã đóng");
    assertThat(tasks.folder("shop", id).resolve("analysis.md")).exists();

    tasks.reopen("shop", id, "bug", "Hoàn tiền lần 2 bị trùng", Actor.PMO);
    fake.scripts.add(c -> {
      assertThat(c.prompt()).contains("REOPEN_BUG", "Logic: hoàn tối đa số đã trả.", "mở lại 1 lần");
      tool(c, "write_ticket_doc", Map.of("name", "plan.md", "content", "- payment-service: PaymentService.java chặn hoàn trùng\n"));
      tool(c, "set_status", Map.of("to", "awaiting_approval"));
      return "ok";
    });
    agents.ask("shop", id, "", "Vì sao hoàn trùng?", false);
    for (String s : List.of("ready", "in_dev", "sa_verify")) {
      tasks.transition("shop", id, s, s.equals("ready") ? Actor.PMO : Actor.DEV);
    }
    fake.scripts.add(c -> {
      tool(c, "write_ticket_doc", Map.of("name", "verify.md", "content", "Kết luận: ĐẠT"));
      assertThat(tool(c, "set_status", Map.of("to", "awaiting_push"))).startsWith("ERR").contains("BUG-01");
      tool(c, "write_ticket_doc", Map.of("name", "analysis.md",
          "content", "- AC-1: hoàn tối đa số đã trả\n- AC-2 (BUG-01): không hoàn trùng\n"));
      assertThat(tool(c, "set_status", Map.of("to", "awaiting_push"))).contains("awaiting_push");
      return "ok";
    });
    agents.ask("shop", id, "sa", "Kiểm chứng", false);
    assertThat(tasks.detail("shop", id).task().status()).isEqualTo(TaskStatus.AWAITING_PUSH);
  }

  @Test
  void unknownSessionTokenIsRejected() {
    JsonNode req = JSON.valueToTree(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list"));
    assertThatThrownBy(() -> mcp.handle("nope", req)).isInstanceOf(java.util.NoSuchElementException.class);
    assertThatThrownBy(() -> mcp.handle(null, req)).isInstanceOf(java.util.NoSuchElementException.class);
  }

  /** The fake values in the sample repos' .env files. */
  private List<String> envValues() throws Exception {
    List<String> out = new ArrayList<>();
    try (Stream<Path> files = Files.walk(Path.of("samples/shop"))) {
      for (Path f : files.filter(p -> p.getFileName().toString().equals(".env")).toList()) {
        Files.readAllLines(f).stream().filter(l -> l.contains("=") && !l.startsWith("#"))
            .map(l -> l.substring(l.indexOf('=') + 1).strip()).filter(v -> v.length() > 6).forEach(out::add);
      }
    }
    return out;
  }
}
