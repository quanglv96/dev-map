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
import com.devmap.config.RoomConfig;
import com.devmap.config.ServiceDetector;
import com.devmap.config.WorkspaceRepository;
import com.devmap.git.GitService;
import com.devmap.graph.GraphRepository;
import com.devmap.graph.GraphService;
import com.devmap.graph.SourceIndexer;
import com.devmap.orchestration.EventRepository;
import com.devmap.orchestration.Orchestrator;
import com.devmap.orchestration.SyncEvent;
import com.devmap.orchestration.TriggerRules.Kind;
import com.devmap.proposal.Proposal;
import com.devmap.proposal.ProposalRepository;
import com.devmap.proposal.ProposalService;
import com.devmap.room.BugCaseGuesser;
import com.devmap.room.RoomRouter;
import com.devmap.room.RoomService;
import com.devmap.rule.ConventionService;
import com.devmap.rule.QualityGate;
import com.devmap.rule.RuleRepository;
import com.devmap.rule.RuleService;
import com.devmap.rule.Rules;
import com.devmap.secret.SecretMasker;
import com.devmap.ticket.RequestCleaner;
import com.devmap.ticket.Task;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

/**
 * PROMPT_ROOM AC-31 → AC-50 with a scripted agent (no LLM): the room, proposals, the trigger table and the hard rule run for real,
 * over the real MCP handler, git repos of samples/shop and the data folder. Everything runs in the test thread, in the order the
 * orchestration picks, so each script below is the next session DevMap starts.
 */
class RoomFlowTest {

  @TempDir
  Path tmp;

  static final ObjectMapper JSON = new ObjectMapper();
  static final String ANALYSIS = """
      # Phân tích

      ## Business rule
      - BR-1: chỉ hoàn tiền đơn đã thanh toán

      ## AC
      - AC-1: Given đơn đã trả When huỷ Then hoàn đủ tiền
      - AC-2: Given đơn chưa trả When huỷ Then không hoàn

      ## Ca kiểm thử
      | mã | AC | Given/When/Then | loại |
      | TC-1 | AC-1 | đơn 100k huỷ → hoàn 100k | đúng |
      """;
  static final String PLAN = """
      # Plan

      ## Việc
      - Bước 1: thêm RefundService ở order-service
      - Bước 2: gọi PaymentClient.refund
      - Bước 3: lưu trạng thái REFUNDED
      - Bước 4: unit test
      - Bước 5: log request id
      - Bước 6: cập nhật README
      - Bước 7: cấu hình timeout
      - Bước 8: dọn code
      - Bước 9: ZZZ-UNRELATED-STEP
      """;
  static final String CODE = "src/main/java/com/shop/order/service/";

  final GitService git = new GitService();
  final FakeAgent fake = new FakeAgent();
  Path repos;
  Path home;
  ProjectService projects;
  TaskService tasks;
  TicketGitService dev;
  AgentService agents;
  McpServer mcp;
  ProposalService proposals;
  Orchestrator sync;
  RoomService room;
  BugCaseGuesser guesser;
  EventRepository eventRepo;
  AgentTools tools;
  ContextBuilder context;
  GraphService graphs;
  SecretMasker masker;
  org.springframework.context.ApplicationEventPublisher publish;

  /** One queued script per session, in the order DevMap starts them. */
  static final class FakeAgent implements AgentProvider {
    final List<Call> calls = new ArrayList<>();
    final Deque<Function<Call, String>> scripts = new ArrayDeque<>();

    @Override
    public Result run(Call call, java.util.function.Consumer<Event> sink) {
      calls.add(call);
      return new Result(true, scripts.isEmpty() ? "ok" : scripts.poll().apply(call), new Usage(100, 20, 300, 50, 0.01));
    }
  }

  @BeforeEach
  void setUp() throws Exception {
    repos = SampleRepos.create(tmp.resolve("repos"));
    home = tmp.resolve("home");
    for (String svc : SampleRepos.SERVICES) {
      git.run(repos.resolve(svc), "config", "user.name", "devmap-test");
      git.run(repos.resolve(svc), "config", "user.email", "devmap@example.invalid");
    }
    projects = new ProjectService(new WorkspaceRepository(home), new ProjectRepository(), new ServiceDetector(), git);
    projects.create("shop", tmp.resolve("data"), "#ff8800", List.of(
        new RepoInput(repos.resolve("order-service").toString(), "test"),
        new RepoInput(repos.resolve("payment-service").toString(), "main"),
        new RepoInput(repos.resolve("notification-service").toString(), "main")));
    masker = new SecretMasker();
    TaskRepository taskRepo = new TaskRepository();
    AuditLog audit = new AuditLog(masker);
    publish = e -> {
      if (e instanceof AuditLog.Event a) {
        audit.on(a);
      }
      if (agents != null && e instanceof TaskChanged tc) {
        agents.onTaskChanged(tc);
      }
      if (sync != null && e instanceof AgentService.SessionEnded se) {
        sync.onSessionEnded(se);
      }
      if (sync != null && e instanceof ProposalService.Created pc) {
        sync.onProposal(pc);
      }
    };
    tasks = new TaskService(projects, taskRepo, masker, new RequestCleaner(), publish, git);
    RuleService rules = new RuleService(projects, new RuleRepository(), new QualityGate());
    Rules def = rules.projectDefault("shop");
    rules.saveDefault("shop", new Rules("maven", def.git(), new Rules.Quality(false, true, List.of("git status"), true, "java -version",
        false, "", 2), def.scope()));
    dev = new TicketGitService(tasks, taskRepo, git, rules, publish);
    SourceIndexer indexer = new SourceIndexer();
    graphs = new GraphService(projects, git, new GraphRepository(), indexer, masker, tasks, e -> { });
    proposals = new ProposalService(tasks, new ProposalRepository(), masker, projects, publish);
    tools = new AgentTools(tasks, dev, graphs, git, projects, rules, masker, new VerifyFacts(dev, graphs, git, indexer),
        proposals);
    context = new ContextBuilder(tasks, graphs, new ConventionService(projects, new RuleRepository(), git, masker), rules,
        projects, new RequestCleaner(), masker, dev);
    agents = new AgentService(tasks, dev, graphs, git, projects, context, tools, fake, new ChatRepository(), masker, publish,
        new MockEnvironment(), home, Runnable::run);
    mcp = new McpServer(agents, tools);
    eventRepo = new EventRepository();
    sync = new Orchestrator(tasks, proposals, agents, dev, projects, eventRepo, Runnable::run);
    guesser = new BugCaseGuesser(tasks, dev);
    room = new RoomService(tasks, agents, sync, proposals, new RoomRouter(), guesser, projects, dev);
  }

  String tool(Call c, String name, Map<String, ?> args) {
    JsonNode req = JSON.valueToTree(Map.of("jsonrpc", "2.0", "id", 7, "method", "tools/call",
        "params", Map.of("name", name, "arguments", args)));
    JsonNode res = mcp.handle(c.sessionToken(), req).path("result");
    return (res.path("isError").asBoolean() ? "ERR " : "") + res.path("content").get(0).path("text").asText();
  }

  /** An approved ticket on order-service, checked out and in IMPLEMENT (plan.md v1, analysis.md v1), no agent session yet. */
  String implementing(String code) {
    String id = tasks.create("shop", "FEAT", code, "Hoàn tiền", "Hoàn tiền khi huỷ đơn ở order-service").task().id();
    tasks.setRepos("shop", id, List.of("order-service"), Actor.PMO);
    tasks.transition("shop", id, "analysis", Actor.SA);
    tasks.writeDoc("shop", id, "analysis.md", ANALYSIS, Actor.SA);
    tasks.writeDoc("shop", id, "plan.md", PLAN, Actor.SA);
    tasks.transition("shop", id, "wait_approval", Actor.SA);
    tasks.transition("shop", id, "ready", Actor.PMO);
    dev.start("shop", id, Actor.DEV);
    assertThat(status(id)).isEqualTo(TaskStatus.IMPLEMENT);
    return id;
  }

  /** Dev's work so far: files written in the service repo and committed on the ticket branch. */
  void devWrote(String id, Map<String, String> files, boolean commit) throws Exception {
    for (var e : files.entrySet()) {
      Path f = repos.resolve("order-service").resolve(e.getKey());
      Files.createDirectories(f.getParent());
      Files.writeString(f, e.getValue());
    }
    if (commit) {
      dev.commit("shop", id, "order-service", "dev work", Actor.DEV);
    }
  }

  TaskStatus status(String id) {
    return tasks.detail("shop", id).task().status();
  }

  Task.Flags flags(String id) {
    return tasks.detail("shop", id).task().flags();
  }

  List<ChatMessage.Card> cards(String id) {
    return agents.chat("shop", id).messages().stream().map(ChatMessage::card).filter(c -> c != null).toList();
  }

  List<Kind> done(String id) {
    return sync.list("shop", id).stream().filter(e -> e.status().equals("DONE")).map(SyncEvent::kind).toList();
  }

  String history(String id) throws Exception {
    return Files.readString(tasks.folder("shop", id).resolve("history.md"));
  }

  void config(RoomConfig r) {
    projects.update("shop", projects.get("shop").config().withRoom(r));
  }

  /** AC-31: the router is local; a vague message asks with two buttons and starts nothing. */
  @Test
  void vagueMessageAsksWithoutAnyLlmCall() {
    String id = implementing("R-31");
    RoomService.Sent sent = room.send("shop", id, "xem giúp cái này với", "", null);
    assertThat(sent.ask()).isEqualTo("role");
    assertThat(sent.options()).containsExactly("sa", "dev");
    assertThat(fake.calls).isEmpty();
    assertThat(agents.chat("shop", id).messages()).isEmpty();

    fake.scripts.add(c -> {
      assertThat(c.prompt()).contains("· DEV ·", "Chạy lại test");
      return "Đã chạy test";
    });
    room.send("shop", id, "@Dev chạy lại test", "", null);
    List<ChatMessage> chat = agents.chat("shop", id).messages();
    assertThat(chat).extracting(ChatMessage::from).containsExactly("PMO", "DEV");
    assertThat(chat.get(0).text()).isEqualTo("@Dev chạy lại test"); // shown once, as typed
  }

  /** AC-33 + AC-35: Dev's technical finding → SA updates plan.md v2 by itself → Dev gets only the changed step. */
  @Test
  void devTechnicalFindingUpdatesPlanAndDevContinues() {
    String id = implementing("R-33");
    fake.scripts.add(c -> { // Dev, asked by the PMO
      assertThat(tool(c, "write_proposal", Map.of("type", "BUG_FOUND", "summary", "PaymentClient.refund nhận cả số âm",
          "proposal", "Chặn số âm trước khi gọi", "affects", "TECHNICAL_ONLY", "repo", "order-service",
          "file", "src/main/java/com/shop/order/client/PaymentClient.java", "refs", List.of("plan.md#Bước 2"))))
          .contains("Đã ghi đề xuất");
      return "Đã báo SA";
    });
    fake.scripts.add(c -> { // SA, started by the proposal
      assertThat(c.prompt()).contains("Phiên tự kích hoạt", "DEV_PROPOSAL", "PaymentClient.refund nhận cả số âm",
          "Bước 2: gọi PaymentClient.refund").doesNotContain("Bước 3").doesNotContain("Given đơn đã trả");
      String pid = proposals.list("shop", id).get(0).id();
      tool(c, "write_ticket_doc", Map.of("name", "plan.md", "content", PLAN.replace("- Bước 2: gọi PaymentClient.refund",
          "- Bước 2: gọi PaymentClient.refund, chặn số âm trước")));
      assertThat(tool(c, "resolve_proposal", Map.of("id", pid, "status", "APPLIED", "affects", "TECHNICAL_ONLY",
          "note", "Thêm bước chặn số âm"))).contains("APPLIED");
      return "Đã cập nhật plan";
    });
    fake.scripts.add(c -> { // Dev, started by the plan change: the diff only (AC-35)
      assertThat(c.prompt()).contains("DOC_CHANGED", "Diff plan.md v1 → v2", "+- Bước 2: gọi PaymentClient.refund, chặn số âm trước")
          .doesNotContain("ZZZ-UNRELATED-STEP").doesNotContain("Given đơn đã trả").doesNotContain("Lịch sử chat:");
      return "Đã chỉnh code";
    });
    agents.ask("shop", id, "dev", "Làm tiếp", false);

    assertThat(fake.calls).hasSize(3);
    assertThat(status(id)).isEqualTo(TaskStatus.IMPLEMENT);
    assertThat(flags(id).needsPmo()).isNull();
    assertThat(tasks.readDoc("shop", id, "plan.md").versions()).isEqualTo(2);
    assertThat(proposals.list("shop", id).get(0).status()).isEqualTo("APPLIED");
    assertThat(cards(id)).anySatisfy(c -> {
      assertThat(c.type()).isEqualTo("doc");
      assertThat(c.text()).contains("SA đã cập nhật plan.md (v2) theo đề xuất của Dev", "Dev tiếp tục");
      assertThat(c.from()).isEqualTo(1);
      assertThat(c.to()).isEqualTo(2);
    });
    assertThat(done(id)).containsExactly(Kind.DEV_PROPOSAL, Kind.DOC_CHANGED);
    assertThat(tasks.readDoc("shop", id, "summary.md").content()).contains("## Đồng bộ tự động", "DEV_PROPOSAL → SA", "DOC_CHANGED → DEV");
    AgentService.UsageView u = agents.usage("shop");
    assertThat(u.syncSessions()).isEqualTo(2);
    assertThat(u.sync().total()).isEqualTo(2 * 470);
  }

  /** AC-34: a change of an AC goes back to the PMO even when the agent called it technical; Dev's work is kept as a WIP commit. */
  @Test
  void acChangeFromDevProposalNeedsThePmoWhateverTheAgentSays() throws Exception {
    String id = implementing("R-34");
    devWrote(id, Map.of(CODE + "RefundNote.java", "package com.shop.order.service;\n\nclass RefundNote {\n}\n"), false);
    fake.scripts.add(c -> { // SA evaluates Dev's proposal and edits AC-1, calling it technical
      tool(c, "write_ticket_doc", Map.of("name", "analysis.md", "content", ANALYSIS.replace("hoàn đủ tiền", "hoàn đủ hoặc một phần")));
      tool(c, "resolve_proposal", Map.of("id", proposals.list("shop", id).get(0).id(), "status", "APPLIED", "affects", "TECHNICAL_ONLY",
          "note", "chỉ là chi tiết"));
      return "ok";
    });
    fake.scripts.add(c -> { // SA prepares the re-approval
      assertThat(c.prompt()).contains("REANALYZE", "Commit WIP", "Ảnh hưởng tới code đang làm");
      assertThat(status(id)).isEqualTo(TaskStatus.ANALYSIS);
      tool(c, "write_ticket_doc", Map.of("name", "plan.md", "content", PLAN + "\n## Ảnh hưởng tới code đang làm\n"
          + "| file | quyết định | vì sao |\n| RefundNote.java | phải sửa | thêm hoàn một phần |\n"));
      assertThat(tool(c, "set_status", Map.of("to", "wait_approval"))).contains("wait_approval");
      return "ok";
    });
    proposals.create("shop", id, new ProposalService.Input("DEV", "BUG_FOUND", "AC-1 thiếu hoàn một phần", null, "Đổi AC-1",
        "TECHNICAL_ONLY", List.of("analysis.md#AC-1"), null));

    assertThat(status(id)).isEqualTo(TaskStatus.WAIT_APPROVAL);
    TaskService.Detail d = tasks.detail("shop", id);
    assertThat(d.reapproval()).isEqualTo(2); // "chốt lại · v2" (FR-87)
    assertThat(d.actions()).extracting(TaskService.Action::to).contains("implement").doesNotContain("ready");
    assertThat(flags(id).needsPmo()).contains("cần PMO chốt lại");
    assertThat(flags(id).midChange()).isTrue();
    Proposal p = proposals.list("shop", id).get(0);
    assertThat(p.status()).isEqualTo("NEEDS_PMO");
    assertThat(p.affects()).isEqualTo("AC");
    assertThat(p.agentAffects()).isEqualTo("TECHNICAL_ONLY");
    assertThat(cards(id)).anySatisfy(c -> assertThat(c.level()).isEqualTo("important"));
    assertThat(git.run(repos.resolve("order-service"), "log", "-1", "--format=%s").output()).contains("WIP(R-34): tạm dừng do");
    assertThat(history(id)).contains("case A2", "implement → analysis");

    // FR-86: approved again → straight back to IMPLEMENT, never the queue; Dev gets the change and the keep/fix/drop list
    assertThatThrownBy(() -> tasks.transition("shop", id, "ready", Actor.PMO)).hasMessageContaining("đã từng triển khai");
    fake.scripts.add(c -> {
      assertThat(c.prompt()).contains("REAPPROVED", "Diff plan.md v1 → v2", "RefundNote.java | phải sửa");
      return "ok";
    });
    assertThat(sync.transition("shop", id, "implement").status()).isEqualTo(TaskStatus.IMPLEMENT);
    assertThat(flags(id).midChange()).isFalse();
    assertThat(flags(id).needsPmo()).isNull();
    assertThat(done(id)).endsWith(Kind.REAPPROVED);
  }

  /** AC-36 / AC-40: SA and Dev disagree → stopped after 2 rounds for the PMO; the status never moved, only the activity did. */
  @Test
  void loopStopsAfterTwoRoundsAndHourlyCapPauses() {
    String id = implementing("R-36");
    for (int round = 1; round <= 2; round++) {
      fake.scripts.add(c -> { // SA rejects
        Proposal last = proposals.list("shop", id).get(proposals.list("shop", id).size() - 1);
        tool(c, "resolve_proposal", Map.of("id", last.id(), "status", "REJECTED", "affects", "TECHNICAL_ONLY",
            "note", "Giữ plan: đã có retry"));
        return "Giữ nguyên";
      });
      fake.scripts.add(c -> { // Dev insists
        assertThat(c.prompt()).contains("PROPOSAL_RESOLVED", "Giữ plan: đã có retry");
        String origin = proposals.list("shop", id).get(0).id();
        tool(c, "write_proposal", Map.of("type", "SOLUTION_CHANGE", "summary", "Vẫn nên thêm circuit breaker", "proposal", "Thêm breaker",
            "affects", "TECHNICAL_ONLY", "reply_to", origin));
        return "Không đồng ý";
      });
    }
    proposals.create("shop", id, new ProposalService.Input("DEV", "SOLUTION_CHANGE", "Nên thêm circuit breaker", null, "Thêm breaker",
        "TECHNICAL_ONLY", List.of(), null));

    assertThat(fake.calls).hasSize(4);
    List<Proposal> all = proposals.list("shop", id);
    assertThat(all).hasSize(3);
    Proposal third = all.get(2);
    assertThat(third.round()).isEqualTo(3);
    assertThat(third.status()).isEqualTo("NEEDS_PMO");
    assertThat(third.views()).hasSize(2).anySatisfy(v -> assertThat(v).startsWith("SA: Giữ plan"));
    assertThat(flags(id).needsPmo()).contains("chưa thống nhất sau 2 vòng");
    assertThat(status(id)).isEqualTo(TaskStatus.IMPLEMENT);
    assertThat(sync.list("shop", id)).noneMatch(e -> e.status().equals("PENDING"));

    // hourly cap: 2 automatic sessions per hour, the third event waits and the ticket pauses for the PMO
    String other = tasks.create("shop", "MAINT", "R-36B", "trần", "x").task().id();
    tasks.forceStatus("shop", other, "implement", "test", Actor.PMO);
    config(new RoomConfig(null, null, null, 2, null, null, null, null, null));
    int before = fake.calls.size();
    for (int i = 0; i < 3; i++) {
      sync.enqueue("shop", other, Kind.PMO_CODE_CHANGE, Map.of("text", "commit " + i), null, 0);
    }
    assertThat(fake.calls).hasSize(before + 2);
    assertThat(flags(other).syncPaused()).isTrue();
    assertThat(flags(other).needsPmo()).startsWith("Vượt trần 2");
    assertThat(sync.list("shop", other)).extracting(SyncEvent::status).containsExactly("DONE", "DONE", "PENDING");
  }

  /** AC-37 / FR-82 / FR-83: one ticket's events run one by one and survive a restart; pausing records them for later. */
  @Test
  void eventsRunInOrderAndSurviveARestart() {
    String id = implementing("R-37");
    sync.pause("shop", id, true);
    sync.enqueue("shop", id, Kind.PMO_CODE_CHANGE, Map.of("text", "commit A"), null, 0);
    SyncEvent second = sync.enqueue("shop", id, Kind.PMO_CODE_CHANGE, Map.of("text", "commit B"), null, 0);
    assertThat(fake.calls).isEmpty(); // paused: recorded only
    // DevMap stopped while the second event was running
    eventRepo.update(tasks.folder("shop", id), second.id(), e -> new SyncEvent(e.id(), e.kind(), "RUNNING", e.created(), e.created(),
        null, e.payload(), e.origin(), e.round(), null));

    Orchestrator restarted = new Orchestrator(tasks, proposals, agents, dev, projects, new EventRepository(), Runnable::run);
    sync = restarted;
    assertThat(restarted.recover()).isEqualTo(1);
    fake.scripts.add(c -> {
      assertThat(c.prompt()).contains("commit A");
      tool(c, "write_ticket_doc", Map.of("name", "summary.md", "content", "# Tóm tắt\n\nA đã ghi nhận\n"));
      return "A";
    });
    fake.scripts.add(c -> {
      assertThat(c.prompt()).contains("commit B", "A đã ghi nhận"); // B starts after A, from A's documents
      return "B";
    });
    restarted.pause("shop", id, false);
    assertThat(fake.calls).hasSize(2);
    assertThat(restarted.list("shop", id)).extracting(SyncEvent::status).containsExactly("DONE", "DONE");
  }

  /** AC-38 / FR-80: undo puts the document back and Dev is told. */
  @Test
  void undoRestoresThePreviousVersionAndTellsDev() {
    String id = implementing("R-38");
    fake.scripts.add(c -> { // SA classifies the PMO's technical clarification
      tool(c, "write_ticket_doc", Map.of("name", "plan.md", "content", PLAN.replace("- Bước 5: log request id",
          "- Bước 5: log request id, dùng lại MoneyFormatter")));
      tool(c, "resolve_proposal", Map.of("id", proposals.list("shop", id).get(0).id(), "status", "APPLIED", "affects",
          "TECHNICAL_ONLY", "note", "A1"));
      return "ok";
    });
    fake.scripts.add(c -> "Dev đã đọc v2");
    room.send("shop", id, "dùng lại hàm format tiền có sẵn", "", null);
    assertThat(tasks.readDoc("shop", id, "plan.md").versions()).isEqualTo(2);

    fake.scripts.add(c -> {
      assertThat(c.prompt()).contains("UNDONE", "-- Bước 5: log request id, dùng lại MoneyFormatter");
      return "Dev bỏ phần MoneyFormatter";
    });
    sync.undo("shop", id, "plan.md", 1, 2);
    assertThat(tasks.readDoc("shop", id, "plan.md").content()).isEqualTo(PLAN);
    assertThat(tasks.readDoc("shop", id, "plan.md").versions()).isEqualTo(3);
    assertThat(cards(id)).anySatisfy(c -> {
      assertThat(c.type()).isEqualTo("doc");
      assertThat(c.undone()).isTrue();
    });
    assertThat(done(id)).endsWith(Kind.UNDONE);
  }

  /** AC-42 / FR-90: a technical clarification while implementing keeps IMPLEMENT and reaches Dev as the plan's new lines only. */
  @Test
  void caseA1KeepsImplementing() throws Exception {
    String id = implementing("R-42");
    fake.scripts.add(c -> {
      assertThat(c.prompt()).contains("PMO_CHANGE", "dùng lại hàm format tiền có sẵn");
      tool(c, "write_ticket_doc", Map.of("name", "plan.md", "content", PLAN.replace("- Bước 3: lưu trạng thái REFUNDED",
          "- Bước 3: lưu trạng thái REFUNDED, format tiền bằng MoneyFormatter có sẵn")));
      tool(c, "resolve_proposal", Map.of("id", proposals.list("shop", id).get(0).id(), "status", "APPLIED", "affects",
          "TECHNICAL_ONLY", "note", "A1"));
      return "ok";
    });
    fake.scripts.add(c -> {
      assertThat(c.prompt()).contains("+- Bước 3: lưu trạng thái REFUNDED, format tiền bằng MoneyFormatter có sẵn")
          .doesNotContain("ZZZ-UNRELATED-STEP").doesNotContain("Given đơn chưa trả");
      return "ok";
    });
    room.send("shop", id, "dùng lại hàm format tiền có sẵn", "", null);
    assertThat(status(id)).isEqualTo(TaskStatus.IMPLEMENT);
    assertThat(cards(id)).anySatisfy(c -> assertThat(c.text()).contains("SA đã bổ sung plan.md (v2) theo yêu cầu của PMO, Dev tiếp tục"));
    assertThat(proposals.list("shop", id).get(0).author()).isEqualTo("PMO");
    assertThat(history(id)).contains("nguồn PMO · case A1 · plan.md v1 → v2");
  }

  /** AC-43 / FR-91 / FR-94: business change after Dev touched 3 files → WIP commit (not pushed), re-approval, back to IMPLEMENT. */
  @Test
  void caseA2StopsDevCommitsWipAndReapproves() throws Exception {
    String id = implementing("R-43");
    devWrote(id, Map.of(CODE + "RefundService.java", "package com.shop.order.service;\n\nclass RefundService {\n}\n",
        CODE + "RefundPolicy.java", "package com.shop.order.service;\n\nclass RefundPolicy {\n}\n",
        CODE + "RefundLog.java", "package com.shop.order.service;\n\nclass RefundLog {\n}\n"), false);
    fake.scripts.add(c -> { // SA: this changes AC-1
      tool(c, "write_ticket_doc", Map.of("name", "analysis.md", "content", ANALYSIS.replace("hoàn đủ tiền", "hoàn tối đa 50%")));
      tool(c, "resolve_proposal", Map.of("id", proposals.list("shop", id).get(0).id(), "status", "APPLIED", "affects", "AC",
          "note", "A2"));
      return "ok";
    });
    fake.scripts.add(c -> { // SA: keep / fix / drop
      assertThat(c.prompt()).contains("Commit WIP", "hoàn tối đa 50%");
      tool(c, "write_ticket_doc", Map.of("name", "plan.md", "content", PLAN + "\n## Ảnh hưởng tới code đang làm\n"
          + "| RefundService.java | phải sửa | trần 50% |\n| RefundPolicy.java | giữ nguyên | |\n"
          + "| RefundLog.java | phải bỏ | không cần |\n"));
      tool(c, "set_status", Map.of("to", "wait_approval"));
      return "ok";
    });
    int mark = history(id).length();
    room.send("shop", id, "hoàn tiền tối đa 50%", "", null);

    assertThat(status(id)).isEqualTo(TaskStatus.WAIT_APPROVAL);
    assertThat(tasks.detail("shop", id).reapproval()).isEqualTo(2);
    Path repo = repos.resolve("order-service");
    String wip = git.run(repo, "log", "-1", "--format=%s").output();
    assertThat(wip).contains("WIP(R-43): tạm dừng do");
    assertThat(git.run(repo, "show", "--stat", "--format=", "HEAD").output()).contains("RefundService.java", "RefundPolicy.java",
        "RefundLog.java");
    assertThat(git.uncommitted(repo)).isEmpty();
    assertThat(tasks.readDoc("shop", id, "changes.md").content()).contains("WIP(R-43)");
    assertThat(dev.view("shop", id).repos().get(0).pushedHead()).isNull(); // never pushed
    String plan = tasks.readDoc("shop", id, "plan.md").content();
    assertThat(plan).contains("RefundService.java | phải sửa", "RefundPolicy.java | giữ nguyên", "RefundLog.java | phải bỏ");
    assertThat(history(id).substring(mark)).contains("implement → analysis", "analysis → wait_approval").doesNotContain("→ ready");

    String head = git.head(repo);
    fake.scripts.add(c -> {
      assertThat(git.head(repo)).isEqualTo(head); // Dev starts from the WIP commit
      assertThat(c.prompt()).contains("REAPPROVED", "RefundLog.java | phải bỏ");
      return "ok";
    });
    sync.transition("shop", id, "implement");
    assertThat(status(id)).isEqualTo(TaskStatus.IMPLEMENT);
  }

  /** AC-44 / FR-92 B1: a bug in the code just written stays in IMPLEMENT, BUG_FOUND by the PMO, a new test case, Dev fixes. */
  @Test
  void caseB1BugInTheWorkInProgress() throws Exception {
    String id = implementing("R-44");
    devWrote(id, Map.of(CODE + "RefundCalculator.java", "package com.shop.order.service;\n\nclass RefundCalculator {\n"
        + "  long calculateRefund(long paid) {\n    return paid * 2;\n  }\n}\n"), true);
    fake.scripts.add(c -> { // Dev first: the cause names its own code
      assertThat(c.prompt()).contains("PMO_BUG_DEV", "calculateRefund");
      return "Đã sửa calculateRefund";
    });
    fake.scripts.add(c -> { // SA adds the missed case to the AC as a test case
      assertThat(c.prompt()).contains("PMO_BUG");
      tool(c, "write_ticket_doc", Map.of("name", "analysis.md", "content", ANALYSIS + "| TC-2 | AC-1 | đơn 100k → hoàn đúng 100k, "
          + "không nhân đôi | lỗi |\n"));
      tool(c, "resolve_proposal", Map.of("id", proposals.list("shop", id).get(0).id(), "status", "APPLIED", "affects",
          "TECHNICAL_ONLY", "note", "case sót của AC-1"));
      return "ok";
    });
    fake.scripts.add(c -> { // Dev gets the new test case
      assertThat(c.prompt()).contains("DOC_CHANGED", "TC-2");
      return "Đã thêm test TC-2";
    });
    RoomService.Sent sent = room.send("shop", id, "số tiền hoàn ở calculateRefund bị sai", "", null);

    assertThat(sent.guess()).isEqualTo("B1");
    assertThat(status(id)).isEqualTo(TaskStatus.IMPLEMENT);
    Proposal p = proposals.list("shop", id).get(0);
    assertThat(p.type()).isEqualTo("BUG_FOUND");
    assertThat(p.author()).isEqualTo("PMO");
    assertThat(tasks.readDoc("shop", id, "analysis.md").content()).contains("TC-2");
    assertThat(done(id)).containsExactly(Kind.PMO_BUG_DEV, Kind.PMO_BUG, Kind.DOC_CHANGED);
  }

  /** AC-45 / FR-92 B2: fix an old bug in this task = wider scope → like A2, plan gets the fix. */
  @Test
  void caseB2FixOldBugHereGoesLikeA2() throws Exception {
    String id = implementing("R-45");
    fake.scripts.add(c -> {
      assertThat(c.prompt()).contains("REANALYZE", "B2", "OrderReportController");
      tool(c, "write_ticket_doc", Map.of("name", "plan.md", "content", PLAN + "- Bước 10: sửa bug OrderReportController gọi thẳng "
          + "repository\n"));
      tool(c, "set_status", Map.of("to", "wait_approval"));
      return "ok";
    });
    RoomService.Sent sent = room.send("shop", id, "bug ở OrderReportController cũ, sửa luôn trong task này", "", null);
    assertThat(sent.guess()).isEqualTo("B2");
    assertThat(status(id)).isEqualTo(TaskStatus.WAIT_APPROVAL);
    assertThat(tasks.readDoc("shop", id, "plan.md").content()).contains("Bước 10: sửa bug OrderReportController");
    assertThat(history(id)).contains("case B2");
    assertThat(proposals.list("shop", id).get(0).type()).isEqualTo("SCOPE_CHANGE");
  }

  /** AC-46 / FR-92 B3: the bug is a Done ticket's, same file → that ticket reopens, both flagged, the PMO picks the order. */
  @Test
  void caseB3ReopensTheOtherTicketAndFlagsOverlap() throws Exception {
    String old = tasks.create("shop", "FEAT", "R-46A", "Thông báo đơn", "x").task().id();
    tasks.forceStatus("shop", old, "done", "đã push trước đây", Actor.PMO);
    tasks.writeDoc("shop", old, "changes.md", "# Thay đổi – R-46A\n\n## order-service\n\n- Commit:\n  - abc123 notify\n- File sửa:\n"
        + "  - src/main/java/com/shop/order/service/OrderService.java\n", Actor.DEVMAP);
    String id = implementing("R-46");
    devWrote(id, Map.of(CODE + "OrderService.java", Files.readString(repos.resolve("order-service").resolve(CODE + "OrderService.java"))
        + "// refund hook\n"), true);

    RoomService.Sent unsure = room.send("shop", id, "OrderService.java gửi thông báo sai", "", null);
    assertThat(unsure.ask()).isEqualTo("case"); // matches both tickets: DevMap asks
    assertThat(unsure.options()).containsExactly("B1", "B2", "B3", "B4");
    assertThat(fake.calls).isEmpty();

    fake.scripts.add(c -> {
      assertThat(c.prompt()).contains("BUG_AFTER_DEPLOY", "OrderService.java gửi thông báo sai");
      return "SA đang tìm";
    });
    room.send("shop", id, "OrderService.java gửi thông báo sai", "", "B3");
    assertThat(status(old)).isEqualTo(TaskStatus.ANALYSIS);
    assertThat(tasks.detail("shop", old).task().reopened()).isEqualTo(1);
    assertThat(status(id)).isEqualTo(TaskStatus.IMPLEMENT);
    assertThat(flags(id).fileOverlap()).containsExactly(old);
    assertThat(flags(old).fileOverlap()).containsExactly(id);
    assertThat(cards(id)).anySatisfy(c -> assertThat(c.options()).containsExactly("priority:" + id, "priority:" + old));

    room.priority("shop", id, old);
    assertThat(flags(id).syncPaused()).isTrue();
    assertThat(flags(old).syncPaused()).isFalse();
  }

  /** AC-47 / FR-92 B4: no ticket matches → a new BUG ticket; the current one does not change. */
  @Test
  void caseB4CreatesANewBugTicket() {
    String id = implementing("R-47");
    fake.scripts.add(c -> "SA phân tích BUG mới");
    RoomService.Sent sent = room.send("shop", id, "Email gửi trùng ở NotificationSender", "", null);
    assertThat(sent.guess()).isEqualTo("B4");
    List<Task> bugs = tasks.list("shop").stream().filter(t -> t.type().equals("BUG")).toList();
    assertThat(bugs).hasSize(1);
    assertThat(bugs.get(0).status()).isEqualTo(TaskStatus.ANALYSIS);
    assertThat(status(id)).isEqualTo(TaskStatus.IMPLEMENT);
    assertThat(proposals.list("shop", id)).isEmpty();
  }

  /** AC-48 / FR-93: four sample reports guessed locally (or asked), no LLM call. */
  @Test
  void bugCasesAreGuessedLocally() throws Exception {
    String old = tasks.create("shop", "FEAT", "R-48A", "Báo cáo", "x").task().id();
    tasks.forceStatus("shop", old, "done", "đã push", Actor.PMO);
    tasks.writeDoc("shop", old, "changes.md", "- File sửa:\n  - src/main/java/com/shop/order/web/OrderReportController.java\n",
        Actor.DEVMAP);
    String id = implementing("R-48");
    devWrote(id, Map.of(CODE + "RefundCalculator.java", "package com.shop.order.service;\n\nclass RefundCalculator {\n"
        + "  long calculateRefund(long paid) {\n    return paid;\n  }\n}\n"), true);

    assertThat(guesser.guess("shop", id, "calculateRefund trả sai số tiền").kase()).isEqualTo("B1");
    assertThat(guesser.guess("shop", id, "lỗi cũ ở OrderController, sửa luôn trong task này").kase()).isEqualTo("B2");
    BugCaseGuesser.Guess b3 = guesser.guess("shop", id, "OrderReportController.java tính tổng sai");
    assertThat(b3.kase()).isEqualTo("B3");
    assertThat(b3.other()).isEqualTo(old);
    assertThat(b3.sure()).isTrue();
    assertThat(guesser.guess("shop", id, "NotificationSender gửi email trùng").kase()).isEqualTo("B4");
    assertThat(guesser.guess("shop", id, "màn hình bị lỗi").sure()).isFalse(); // nothing to match: ask
    assertThat(fake.calls).isEmpty();
  }

  /** AC-50 / FR-96: skip re-approval for the PMO's own change; a new repo still needs the PMO. */
  @Test
  void skipReapprovalUnlessANewRepoIsAdded() throws Exception {
    config(RoomConfig.DEFAULT.withSkipReapproval(true));
    String id = implementing("R-50");
    fake.scripts.add(c -> {
      tool(c, "write_ticket_doc", Map.of("name", "analysis.md", "content", ANALYSIS.replace("hoàn đủ tiền", "hoàn tối đa 50%")));
      tool(c, "resolve_proposal", Map.of("id", proposals.list("shop", id).get(0).id(), "status", "APPLIED", "affects", "AC", "note", "A2"));
      return "ok";
    });
    fake.scripts.add(c -> {
      assertThat(status(id)).isEqualTo(TaskStatus.IMPLEMENT);
      tool(c, "write_ticket_doc", Map.of("name", "plan.md", "content", PLAN + "\n## Ảnh hưởng tới code đang làm\n| RefundService | phải "
          + "sửa | 50% |\n"));
      return "ok";
    });
    fake.scripts.add(c -> {
      assertThat(c.prompt()).contains("REAPPROVED", "RefundService | phải sửa");
      return "ok";
    });
    int mark = history(id).length();
    room.send("shop", id, "hoàn tiền tối đa 50%", "", null);
    assertThat(status(id)).isEqualTo(TaskStatus.IMPLEMENT);
    assertThat(history(id).substring(mark)).doesNotContain("→ wait_approval").doesNotContain("→ analysis");
    assertThat(cards(id)).anySatisfy(c -> {
      assertThat(c.text()).contains("Đã áp dụng thay đổi của PMO, không chốt lại");
      assertThat(c.type()).isEqualTo("doc"); // with diff and undo
    });
    assertThat(flags(id).midChange()).isFalse();

    fake.scripts.add(c -> { // second change adds payment-service: re-approval is required again
      tool(c, "write_ticket_doc", Map.of("name", "plan.md", "content", tasks.readDoc("shop", id, "plan.md").content()
          + "- Bước 11: payment-service thêm API hoàn một phần\n"));
      tool(c, "resolve_proposal", Map.of("id", proposals.list("shop", id).get(1).id(), "status", "APPLIED", "affects", "TECHNICAL_ONLY",
          "note", "cần payment-service"));
      return "ok";
    });
    fake.scripts.add(c -> {
      tool(c, "set_status", Map.of("to", "wait_approval"));
      return "ok";
    });
    room.send("shop", id, "thêm hoàn tiền một phần qua cổng thanh toán", "", null);
    assertThat(status(id)).isEqualTo(TaskStatus.WAIT_APPROVAL);
    assertThat(history(id)).contains("plan.md thêm repo/service [payment-service]");
  }

  /** FR-77: PMO edits code by hand → SA records it once; PMO reports a bug after deploy → SA fixes analysis.md before Dev. */
  @Test
  void pmoHandEditsAndBugsAfterDeployReachSa() throws Exception {
    String id = implementing("R-77");
    fake.scripts.add(c -> "Dev xong một phần");
    agents.ask("shop", id, "dev", "Làm tiếp", false); // records the worktree heads of the last Dev session
    Path repo = repos.resolve("order-service");
    Files.writeString(repo.resolve("PMO.md"), "pmo edit\n");
    git.run(repo, "add", "PMO.md");
    git.run(repo, "commit", "-m", "PMO tweak");
    fake.scripts.add(c -> {
      assertThat(c.prompt()).contains("PMO_CODE_CHANGE", "PMO tweak");
      return "Đã ghi nhận";
    });
    assertThat(sync.scanPmoChanges()).isEqualTo(1);
    assertThat(sync.scanPmoChanges()).isZero(); // once per head
    assertThat(done(id)).containsExactly(Kind.PMO_CODE_CHANGE);

    String other = tasks.create("shop", "FEAT", "R-77B", "Hoàn tiền cũ", "x").task().id();
    tasks.forceStatus("shop", other, "done", "đã push", Actor.PMO);
    fake.scripts.add(c -> {
      assertThat(c.prompt()).contains("BUG_AFTER_DEPLOY", "REOPEN_BUG", "Hoàn tiền lần 2 bị trùng");
      tool(c, "write_ticket_doc", Map.of("name", "analysis.md", "content", "- AC-1 …\n- AC-2: BUG-01 không hoàn hai lần\n"));
      tool(c, "write_ticket_doc", Map.of("name", "plan.md", "content", "- sửa idempotency\n"));
      tool(c, "set_status", Map.of("to", "wait_approval"));
      return "ok";
    });
    assertThatThrownBy(() -> sync.reportAfterDeploy("shop", other, "bug", " ")).hasMessageContaining("Chưa mô tả");
    sync.reportAfterDeploy("shop", other, "bug", "Hoàn tiền lần 2 bị trùng");
    assertThat(status(other)).isEqualTo(TaskStatus.WAIT_APPROVAL);
    assertThat(tasks.detail("shop", other).task().reopened()).isEqualTo(1);
  }

  /** A second agent runner with real threads (2 slots) over the same project, for the tests that need sessions side by side. */
  AgentService threaded(AgentProvider provider) {
    java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
    return new AgentService(tasks, dev, graphs, git, projects, context, tools, provider, new ChatRepository(), masker, e -> { },
        new MockEnvironment(), home, pool);
  }

  /** AC-39 / FR-74: "@SA rà nghiệp vụ, @Dev dò log" opens two sessions that run at the same time; both answers land in the room. */
  @Test
  void twoJobsInOneMessageRunInParallel() throws Exception {
    String id = tasks.create("shop", "FEAT", "R-39", "Hoàn tiền", "x").task().id();
    tasks.transition("shop", id, "analysis", Actor.SA);
    java.util.concurrent.CountDownLatch both = new java.util.concurrent.CountDownLatch(2);
    java.util.concurrent.atomic.AtomicInteger together = new java.util.concurrent.atomic.AtomicInteger();
    AgentService parallel = threaded((call, sink) -> {
      both.countDown();
      try {
        if (both.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
          together.incrementAndGet(); // this session saw the other one running
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return new Result(true, call.prompt().contains("· SA ·") ? "SA: nghiệp vụ ổn" : "Dev: log sạch", Usage.ZERO);
    });
    RoomService.Sent sent = new RoomService(tasks, parallel, sync, proposals, new RoomRouter(), guesser, projects, dev)
        .send("shop", id, "@SA rà nghiệp vụ, @Dev dò log", "", null);
    assertThat(sent.started()).extracting(ChatMessage::from).containsExactly("SA", "DEV");
    long end = System.currentTimeMillis() + 20_000;
    while (parallel.busy("shop", id) && System.currentTimeMillis() < end) {
      Thread.sleep(50);
    }
    assertThat(together.get()).isEqualTo(2);
    List<ChatMessage> chat = parallel.chat("shop", id).messages();
    assertThat(chat).extracting(ChatMessage::from).containsExactly("PMO", "SA", "DEV");
    assertThat(chat).extracting(ChatMessage::text).contains("SA: nghiệp vụ ổn", "Dev: log sạch");
  }

  /** AC-49 / FR-94: a Dev session that ignores the stop is cancelled after the timeout; what it wrote is committed as WIP. */
  @Test
  void devThatDoesNotStopIsCancelledAndItsWorkCommitted() throws Exception {
    String id = implementing("R-49");
    java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch cancelled = new java.util.concurrent.CountDownLatch(1);
    AgentService stuck = threaded(new AgentProvider() {
      @Override
      public Result run(Call call, java.util.function.Consumer<Event> sink) {
        try {
          Files.writeString(repos.resolve("order-service").resolve(CODE + "Half.java"),
              "package com.shop.order.service;\n\nclass Half {\n}\n");
        } catch (java.io.IOException e) {
          throw new java.io.UncheckedIOException(e);
        }
        started.countDown();
        try {
          cancelled.await(30, java.util.concurrent.TimeUnit.SECONDS); // never answers on its own
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        return new Result(false, "bị huỷ", Usage.ZERO);
      }

      @Override
      public void cancel(String token) {
        cancelled.countDown();
      }
    });
    stuck.answer("shop", id, "dev", "Làm tiếp", false, true);
    assertThat(started.await(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    long t0 = System.currentTimeMillis();
    assertThat(stuck.stopDev("shop", id, "PMO đổi logic", 1)).isTrue();
    assertThat(System.currentTimeMillis() - t0).isGreaterThanOrEqualTo(1000);
    assertThat(cancelled.getCount()).isZero();
    assertThat(stuck.busy("shop", id, "DEV")).isFalse();
    List<String> wip = dev.wipCommit("shop", id, "PMO đổi logic", Actor.DEVMAP);
    assertThat(wip).hasSize(1);
    Path repo = repos.resolve("order-service");
    assertThat(git.run(repo, "log", "-1", "--format=%s").output()).contains("WIP(R-49): tạm dừng do PMO đổi logic");
    assertThat(git.run(repo, "show", "--stat", "--format=", "HEAD").output()).contains("Half.java");
    assertThat(dev.view("shop", id).repos().get(0).pushedHead()).isNull();
    assertThat(history(id)).contains("Dev không dừng trong 1 giây: huỷ phiên", "commit WIP cục bộ");
  }
}
