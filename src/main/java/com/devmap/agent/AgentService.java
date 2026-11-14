package com.devmap.agent;

import com.devmap.agent.AgentProvider.Usage;
import com.devmap.agent.AgentSession.Mode;
import com.devmap.agent.AgentSession.Role;
import com.devmap.agent.ChatMessage.Step;
import com.devmap.audit.AuditLog;
import com.devmap.config.ProjectService;
import com.devmap.git.GitService;
import com.devmap.graph.GraphService;
import com.devmap.secret.SecretMasker;
import com.devmap.secret.SecretsConfig;
import com.devmap.proposal.Proposal;
import com.devmap.ticket.PmoOnlyException;
import com.devmap.ticket.Task;
import com.devmap.ticket.TaskChanged;
import com.devmap.ticket.TaskService;
import com.devmap.ticket.TaskService.Actor;
import com.devmap.ticket.TaskStatus;
import com.devmap.ticket.TicketGitService;
import com.devmap.ticket.TicketGitService.RepoView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * Agent runner (FR-37 → FR-40, FR-43, FR-45, AIR-03/04). Each PMO question starts one new session built only from the ticket's
 * documents; the chat is stored for the PMO but never sent. SA sessions first update the index by git diff (pull), Dev sessions
 * first pull the base branch into the ticket worktree. When Dev hands over (verify), an SA verification session starts by itself.
 * When the ticket is Done, the session scratch (input logs, work folders) is deleted; documents stay.
 */
@Service
public class AgentService {

  /** For the UI: the chat plus token totals of the ticket and of the whole project (FR-40). */
  public record ChatView(List<ChatMessage> messages, Usage ticket, Usage project, boolean running, List<String> runningRoles) {
  }

  /**
   * A session started by a sync event instead of a PMO question (FR-77, FR-78). change: the proposal or document diff it is about;
   * refs/location: the document sections and code it may look at; task: what the role must do, from the trigger table.
   */
  public record Trigger(String eventId, String kind, String label, String change, List<String> refs, Proposal.Location location,
      String task) {

    public Trigger {
      refs = refs == null ? List.of() : List.copyOf(refs);
    }
  }

  /**
   * Published once a session ended and its slot is free: the orchestration compares the documents with how they were when the
   * session started (FR-79) and picks the next event of the ticket (FR-82). startVersions: plan.md / analysis.md version then.
   */
  public record SessionEnded(String project, String taskId, String role, String mode, String trigger, int messageId, boolean ok,
      TaskStatus startStatus, Map<String, Integer> startVersions, List<String> startRepos) {
  }

  /** Commits in the worktrees no Dev session made since the last one (FR-77, PMO edits code by hand). */
  public record PmoChange(String fingerprint, String text) {
  }

  /** Published on every step of a session; the web layer forwards it as the SSE event "agent". */
  public record AgentProgress(String project, String taskId, int messageId, String state) {
  }

  /**
   * Token/quota box of the project page: tokens of the project (all time and today), sessions running on this machine, and the
   * latest rate-limit window per type Claude Code reported (five_hour, seven_day…; empty with an API key).
   */
  public record UsageView(Usage project, Usage today, int running, List<Quota> quota, Usage sync, int syncSessions) {
  }

  /** resetsAt in epoch seconds, utilization as reported (fraction of the window) or null when Claude Code leaves it out. */
  public record Quota(String type, String status, Long resetsAt, Double utilization, String seenAt) {
  }

  /**
   * Tab title and icon (SRS "Hiển thị tiến trình"): tickets with an agent running and what it does (SA, DEV or VERIFY), tickets
   * whose last session failed, and open questions to the PMO per ticket (summary.md "Câu hỏi chờ PMO").
   */
  public record Activity(List<String> running, Map<String, String> working, List<String> failed, Map<String, Integer> questions,
      String queueTicket, boolean queueRunning, Map<String, String> doing) {
  }

  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
  private static final ObjectMapper JSON = new ObjectMapper();
  /** Question of the SA session DevMap starts by itself when the PMO creates a ticket with "analyse now". */
  static final String AUTO_ANALYZE = "Ticket mới: phân tích yêu cầu, đánh giá ảnh hưởng và đề xuất hướng xử lý cho PMO duyệt.";
  static final String REANALYZE = "PMO yêu cầu phân tích lại: plan.md đã chốt không còn khớp với các quyết định mới. Đọc analysis.md, "
      + "summary.md, plan.md và code đã commit, ghi plan.md bản mới chỉ gồm phần còn phải làm, rồi set_status wait_approval.";
  static final String REVISE = "PMO yêu cầu phân tích lại: phương án chưa được chốt. Đọc spec.json, summary.md, analysis.md, plan.md, "
      + "xem lại phương án, sửa analysis.md và plan.md nếu cần, rồi set_status wait_approval.";
  static final String IMPLEMENT = "Triển khai theo plan.md đã chốt";
  private static final Pattern QUESTIONS = Pattern.compile("(?imu)^#{1,6}\\s*(câu hỏi chờ pmo|questions for (the )?pmo).*$");
  private static final Pattern ITEM = Pattern.compile("^\\s*(?:\\d+[.)]|[-*])\\s+(.+)");
  private static final Pattern NONE = Pattern.compile("(?iu)^\\(?(không có|không còn|none|chưa có).*");

  private final TaskService tasks;
  private final TicketGitService dev;
  private final GraphService graphs;
  private final GitService git;
  private final ProjectService projects;
  private final ContextBuilder context;
  private final AgentTools tools;
  private final AgentProvider provider;
  private final ChatRepository chats;
  private final SecretMasker masker;
  private final ApplicationEventPublisher events;
  private final Environment env;
  private final Path sessions;
  private final Executor runner;
  private final Map<String, AgentSession> live = new ConcurrentHashMap<>();
  private final Set<String> busy = ConcurrentHashMap.newKeySet();
  /** project/ticket → SA, DEV or VERIFY while a session runs or waits for a slot (tab title and progress bar). */
  private final Map<String, String> working = new ConcurrentHashMap<>();
  /** FR-89 activity line, project/ticket/ROLE → "SA đang cập nhật plan.md theo đề xuất của Dev"; runtime only, never written. */
  private final Map<String, String> doing = new ConcurrentHashMap<>();
  /** FR-94: stop asked for a Dev session that has not reached the provider yet. */
  private final Map<String, String> stopRequests = new ConcurrentHashMap<>();
  // ponytail: queue runs live in memory; a restart stops them and the PMO presses "Dev triển khai" again
  private final Map<String, String> queueRuns = new ConcurrentHashMap<>();
  // ponytail: the account quota lives in memory, refreshed by every session; after a restart it shows again after the next one
  private final Map<String, Quota> quota = new ConcurrentHashMap<>();

  @Autowired
  public AgentService(TaskService tasks, TicketGitService dev, GraphService graphs, GitService git, ProjectService projects,
      ContextBuilder context, AgentTools tools, AgentProvider provider, ChatRepository chats, SecretMasker masker,
      ApplicationEventPublisher events, Environment env, @Value("${devmap.home:${user.home}/.devmap}") Path home,
      @Value("${devmap.agent.max-concurrent:2}") int maxConcurrent) {
    this(tasks, dev, graphs, git, projects, context, tools, provider, chats, masker, events, env, home,
        // NFR-12: at most N agents at once on this machine, the rest wait in line
        Executors.newFixedThreadPool(Math.max(1, maxConcurrent), r -> {
          Thread t = new Thread(r, "devmap-agent");
          t.setDaemon(true);
          return t;
        }));
  }

  AgentService(TaskService tasks, TicketGitService dev, GraphService graphs, GitService git, ProjectService projects,
      ContextBuilder context, AgentTools tools, AgentProvider provider, ChatRepository chats, SecretMasker masker,
      ApplicationEventPublisher events, Environment env, Path home, Executor runner) {
    this.tasks = tasks;
    this.dev = dev;
    this.graphs = graphs;
    this.git = git;
    this.projects = projects;
    this.context = context;
    this.tools = tools;
    this.provider = provider;
    this.chats = chats;
    this.masker = masker;
    this.events = events;
    this.env = env;
    this.sessions = home.resolve("sessions");
    this.runner = runner;
  }

  /**
   * A PMO question (FR-37). role: "sa", "dev" or blank (by status: Ready/In dev → Dev, otherwise SA). fullScan: AC-09, the full
   * signature scan runs once for this question only and is not remembered. Returns the agent's message, filled in as it runs.
   */
  public ChatMessage ask(String project, String id, String role, String question, boolean fullScan) {
    if (question == null || question.isBlank()) {
      throw new IllegalArgumentException("Chưa nhập câu hỏi");
    }
    return answer(project, id, role, question, fullScan, true);
  }

  /** withPmo: false when the room already showed the PMO's message (one message, two roles: FR-74). */
  public ChatMessage answer(String project, String id, String role, String question, boolean fullScan, boolean withPmo) {
    if (question == null || question.isBlank()) {
      throw new IllegalArgumentException("Chưa nhập câu hỏi");
    }
    Task t = tasks.detail(project, id).task();
    Role r = role(role, t.status(), projects.get(project).config().room().devCanProposeBeforeApproval());
    String q = masker.mask(question.strip(), projects.get(project).config().secrets()).text();
    return submit(project, id, r, t.status() == TaskStatus.VERIFY && r == Role.SA ? Mode.VERIFY : Mode.ASK, q, fullScan, null,
        withPmo);
  }

  /** The PMO's message in the room, shown once before it is routed (FR-71); masked like any question. */
  public ChatMessage pmoMessage(String project, String id, String text) {
    String q = masker.mask(text.strip(), projects.get(project).config().secrets()).text();
    return append(project, id, no -> new ChatMessage(no, now(), "PMO", "ask", q, List.of(), List.of(), null, "done", null));
  }

  /** FR-76 / FR-80: a DevMap card in the room (masked). */
  public ChatMessage card(String project, String id, ChatMessage.Card card) {
    SecretsConfig secrets = projects.get(project).config().secrets();
    ChatMessage.Card c = new ChatMessage.Card(card.type(), masker.mask(card.text(), secrets).text(), card.doc(), card.from(), card.to(),
        card.proposal(), card.level(), card.options(), card.undone());
    ChatMessage m = append(project, id, no -> new ChatMessage(no, now(), "DEVMAP", "card", "", List.of(), List.of(), null, "done", null,
        c, null));
    progress(project, id, m.id(), "done");
    return m;
  }

  /** FR-80: the doc card whose change was undone keeps its place in the room, marked as undone. */
  public void markUndone(String project, String id, String doc, int to) {
    synchronized (this) {
      Path folder = tasks.folder(project, id);
      List<ChatMessage> all = new ArrayList<>(chats.read(folder));
      all.replaceAll(m -> m.card() != null && "doc".equals(m.card().type()) && doc.equals(m.card().doc())
          && Integer.valueOf(to).equals(m.card().to()) ? new ChatMessage(m.id(), m.at(), m.from(), m.mode(), m.text(), m.context(),
              m.steps(), m.usage(), m.state(), m.heads(), m.card().asUndone(), m.trigger()) : m);
      chats.write(folder, all);
    }
  }

  private synchronized ChatMessage append(String project, String id, java.util.function.IntFunction<ChatMessage> make) {
    Path folder = tasks.folder(project, id);
    List<ChatMessage> all = new ArrayList<>(chats.read(folder));
    ChatMessage m = make.apply(all.size() + 1);
    all.add(m);
    chats.write(folder, all);
    return m;
  }

  /** FR-77: a session started by a sync event; the caller checked that the role is free (busy() is false). */
  public ChatMessage trigger(String project, String id, String role, Trigger trigger) {
    Role r = Role.valueOf(role.toUpperCase(Locale.ROOT));
    return submit(project, id, r, Mode.SYNC, trigger.change(), false, trigger, false);
  }

  /** Any session of this role (SA, DEV) runs or waits for a slot on the ticket. */
  public boolean busy(String project, String id, String role) {
    return busy.contains(key(project, id, Role.valueOf(role.toUpperCase(Locale.ROOT))));
  }

  public boolean busy(String project, String id) {
    return busy.contains(key(project, id, Role.SA)) || busy.contains(key(project, id, Role.DEV));
  }

  /**
   * FR-94 safe stop: the running Dev session may finish the file edit it is doing, every later tool call is refused, and it ends.
   * Still running after {@code seconds}: the provider ends it. Returns false when no Dev session was running.
   */
  public boolean stopDev(String project, String id, String reason, int seconds) {
    String key = key(project, id, Role.DEV);
    if (!busy.contains(key)) {
      return false;
    }
    stopRequests.put(key, reason);
    List<AgentSession> mine = live.values().stream()
        .filter(s -> s.project.equals(project) && s.taskId.equals(id) && s.role == Role.DEV).toList();
    mine.forEach(s -> s.stop = reason);
    tasks.note(project, id, Actor.DEVMAP, "Yêu cầu Dev dừng an toàn sau thao tác sửa file hiện tại: " + reason);
    if (!waitIdle(key, seconds * 1000L)) {
      live.values().stream().filter(s -> s.project.equals(project) && s.taskId.equals(id) && s.role == Role.DEV)
          .forEach(s -> provider.cancel(s.token));
      tasks.note(project, id, Actor.DEVMAP, "Dev không dừng trong " + seconds + " giây: huỷ phiên");
      waitIdle(key, 10_000L);
    }
    return true;
  }

  private boolean waitIdle(String key, long millis) {
    long end = System.currentTimeMillis() + millis;
    while (busy.contains(key) && System.currentTimeMillis() < end) {
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return !busy.contains(key);
      }
    }
    return !busy.contains(key);
  }

  /** PMO approved the plan (Ready / In dev): Dev implements it on its own; SA verifies by itself when Dev hands over (AIR-03). */
  public ChatMessage implement(String project, String id) {
    Task t = tasks.detail(project, id).task();
    if (t.status() != TaskStatus.READY && t.status() != TaskStatus.IMPLEMENT) {
      throw new IllegalArgumentException("Chỉ triển khai được khi PMO đã chốt giải pháp (Sẵn sàng / Đang triển khai)");
    }
    if (t.repos().isEmpty()) {
      throw new IllegalArgumentException("Ticket chưa có repo nào; SA hoặc PMO chọn repo ở cột phải");
    }
    return submit(project, id, Role.DEV, Mode.ASK, IMPLEMENT, false, null, true);
  }

  /** A new ticket goes to SA right away (PMO ticked "analyse now" on the form); SA moves it New → SA analysis as usual. */
  public ChatMessage analyzeNew(String project, String id) {
    return submit(project, id, Role.SA, Mode.ASK, AUTO_ANALYZE, false, null, true);
  }

  /** D-66: no session may still be writing to a ticket the PMO cancels; FR-45 purge then runs on the change event. */
  public Task cancel(String project, String id, String reason) {
    requireIdle(project, id);
    return tasks.cancel(project, id, reason, Actor.PMO);
  }

  /** D-72: no agent is started; the PMO resumes with the usual buttons (chat, Dev triển khai) once the status is right. */
  public Task forceStatus(String project, String id, String to, String reason) {
    requireIdle(project, id);
    return tasks.forceStatus(project, id, to, reason, Actor.PMO);
  }

  /**
   * PMO moves a ticket by hand. Any move back to SA analysis starts SA right away, so the ticket never sits in "SA phân tích" with no
   * session; after approval (D-68) SA writes a new plan. It must not pull the plan from under a running Dev or SA.
   */
  public Task transition(String project, String id, String to) {
    TaskStatus from = tasks.detail(project, id).task().status();
    boolean toSa = from != TaskStatus.NEW && TaskStatus.parse(to) == TaskStatus.ANALYSIS;
    if (toSa) {
      requireIdle(project, id);
    }
    Task moved = tasks.transition(project, id, to, Actor.PMO);
    if (toSa) {
      submit(project, id, Role.SA, Mode.ASK, from.isReanalysis(TaskStatus.ANALYSIS) ? REANALYZE : REVISE, false, null, true);
    }
    return moved;
  }

  public void requireIdle(String project, String id) {
    if (busy(project, id)) {
      throw new IllegalArgumentException("Agent đang chạy cho " + id + "; dừng hoặc chờ phiên này xong");
    }
  }

  private static String key(String project, String id, Role role) {
    return project + "/" + id + "/" + role.name();
  }

  private ChatMessage submit(String project, String id, Role role, Mode mode, String question, boolean fullScan, Trigger trigger,
      boolean withPmo) {
    String key = key(project, id, role);
    if (!busy.add(key)) {
      throw new IllegalArgumentException("Agent " + role + " đang chạy cho " + id + "; chờ phiên này xong");
    }
    working.put(project + "/" + id, mode == Mode.VERIFY ? "VERIFY" : role.name());
    doing.put(key, trigger != null ? trigger.label() : mode == Mode.VERIFY ? "SA đang kiểm chứng diff với yêu cầu"
        : role == Role.SA ? "SA đang trả lời PMO" : "Dev đang làm việc");
    ChatMessage msg;
    TaskStatus startStatus;
    Map<String, Integer> startVersions = new LinkedHashMap<>();
    List<String> startRepos;
    try {
      Task t = tasks.detail(project, id).task();
      startStatus = t.status();
      startRepos = t.repos();
      for (String doc : List.of("analysis.md", "plan.md")) {
        try {
          startVersions.put(doc, tasks.readDoc(project, id, doc).versions());
        } catch (NoSuchElementException e) {
          startVersions.put(doc, 0);
        }
      }
      String modeName = mode.name().toLowerCase(Locale.ROOT);
      synchronized (this) {
        if (withPmo && mode == Mode.ASK) {
          append(project, id, no -> new ChatMessage(no, now(), "PMO", "ask", question, List.of(), List.of(), null, "done", null));
        }
        msg = append(project, id, no -> new ChatMessage(no, now(), role.name(), modeName, "", List.of(),
            List.of(new Step("note", trigger != null ? "Tự kích hoạt: " + trigger.label()
                : "Đang chờ lượt (tối đa số agent chạy cùng lúc)")),
            null, "running", null, null, trigger == null ? null : trigger.eventId()));
      }
      if (trigger != null) {
        tasks.note(project, id, Actor.DEVMAP, "Tự mở phiên " + role + " do sự kiện " + trigger.kind() + " (#" + msg.id() + ")");
      } else if (mode == Mode.ASK) {
        tasks.note(project, id, Actor.PMO, "Hỏi " + role + " (câu #" + msg.id() + ")");
      } else {
        tasks.note(project, id, Actor.DEVMAP, "Dev đã xong, tự mở phiên SA kiểm chứng (#" + msg.id() + ")");
      }
    } catch (RuntimeException e) {
      busy.remove(key);
      doing.remove(key);
      working.remove(project + "/" + id);
      throw e;
    }
    int no = msg.id();
    progress(project, id, no, "running");
    runner.execute(() -> {
      boolean ok = false;
      try {
        ok = run(project, id, role, mode, question, fullScan, no, trigger);
      } catch (RuntimeException e) {
        finish(project, id, no, "Lỗi: " + masker.mask(String.valueOf(e.getMessage())).text(), Usage.ZERO, "error", null);
      } finally {
        busy.remove(key);
        doing.remove(key);
        stopRequests.remove(key);
        if (!busy(project, id)) {
          working.remove(project + "/" + id);
        }
      }
      events.publishEvent(new SessionEnded(project, id, role.name(), mode.name(), trigger == null ? null : trigger.eventId(), no, ok,
          startStatus, Map.copyOf(startVersions), startRepos));
      afterSession(project, id, role, mode);
    });
    return msg;
  }

  private boolean run(String project, String id, Role role, Mode mode, String question, boolean fullScan, int no, Trigger trigger) {
    List<String> notes = prepare(project, id, role, fullScan);
    notes.forEach(n -> step(project, id, no, new Step("note", n)));
    ContextBuilder.Context ctx = trigger != null ? context.buildSync(project, id, role, trigger, notes, no)
        : context.build(project, id, role, mode, question, notes, no);
    Path dir = sessions.resolve(project).resolve(id);
    AgentSession s = new AgentSession(UUID.randomUUID().toString(), project, id, role, mode, no, dir.resolve(String.valueOf(no)),
        dir.resolve(no + ".log"), trigger == null ? null : trigger.eventId());
    s.stop = stopRequests.get(key(project, id, role));
    chats.log(s.log, "# Đầu vào phiên #" + no + " (" + role + ", " + mode + ") – " + now() + "\n\n## System prompt (tĩnh, cache)\n\n"
        + ctx.system() + "\n\n## Prompt\n\n" + ctx.prompt() + "\n\n## Kết quả công cụ\n\n");
    update(project, id, no, m -> new ChatMessage(m.id(), m.at(), m.from(), m.mode(), m.text(), ctx.files(), m.steps(), m.usage(),
        m.state(), m.heads()));
    live.put(s.token, s);
    AgentProvider.Result result;
    try {
      Files.createDirectories(s.workDir);
      result = s.stop != null ? new AgentProvider.Result(false, "DevMap đã dừng phiên trước khi bắt đầu: " + s.stop, Usage.ZERO)
          : provider.run(new AgentProvider.Call(ctx.system(), ctx.prompt(), s.workDir, mcpUrl(), s.token),
          e -> {
            if (e.kind().equals("quota")) {
              quota(e.text());
            } else {
              step(project, id, no, new Step(e.kind(), e.text()));
            }
          });
    } catch (java.io.IOException e) {
      result = new AgentProvider.Result(false, "Không tạo được thư mục phiên: " + e.getMessage(), Usage.ZERO);
    } finally {
      live.remove(s.token);
      tools.forget(s);
      if (!chats.deleteTree(s.workDir)) {
        step(project, id, no, new Step("note", "Chưa xoá được thư mục tạm " + s.workDir + " (file đang bị khoá); sẽ dọn khi ticket xong"));
      }
    }
    if (role == Role.DEV && s.stop == null) {
      commitLeftovers(s); // a stopped session is committed as WIP by the orchestration (FR-94)
    }
    String answer = masker.mask(result.text(), projects.get(project).config().secrets()).text();
    if (!s.written.contains("summary.md")) {
      fallbackSummary(project, id, no, question, answer);
    }
    finish(project, id, no, answer, result.usage(), result.ok() ? "done" : "error", role == Role.DEV ? heads(project, id) : null);
    Usage u = result.usage();
    tasks.note(project, id, role.actor, (mode == Mode.VERIFY ? "Kiểm chứng" : trigger != null ? "Phiên tự kích hoạt #" + no + " ("
        + trigger.kind() + ")" : "Trả lời câu #" + no) + (result.ok() ? "" : " (lỗi)")
        + ", token vào " + (u.input() + u.cacheRead() + u.cacheWrite()) + " (cache đọc " + u.cacheRead() + "), ra " + u.output());
    return result.ok();
  }

  /** AIR-04 / FR-24 / FR-28 / AC-09: what DevMap does before the agent starts. Failures become notes, never silent. */
  private List<String> prepare(String project, String id, Role role, boolean fullScan) {
    List<String> notes = new ArrayList<>();
    Task t = tasks.detail(project, id).task();
    if (role == Role.SA && t.status() == TaskStatus.NEW) {
      tasks.transition(project, id, TaskStatus.ANALYSIS.code(), Actor.SA);
    }
    if (fullScan) {
      GraphService.Report r = graphs.update(project, true);
      notes.add("PMO bật quét toàn bộ cho câu này: đọc " + r.repos().stream().mapToInt(GraphService.RepoReport::parsed).sum()
          + " file, chỉ cập nhật chỉ mục chữ ký trên máy; tự tắt sau câu này");
    } else if (role == Role.SA) {
      try {
        // only the ticket's repos (every repo while SA has picked none): a follow-up question must not refetch the whole project
        GraphService.Report r = graphs.update(project, false, t.repos());
        List<String> moved = r.repos().stream().filter(x -> x.parsed() > 0 || x.removed() > 0 || !x.note().endsWith("đã mới nhất"))
            .map(x -> x.repo() + " " + (x.parsed() > 0 ? "đọc " + x.parsed() + " file" : x.note())).toList();
        notes.add(moved.isEmpty() ? "Chỉ mục " + r.repos().size() + " repo của ticket đã mới nhất theo nhánh nền"
            : "Cập nhật chỉ mục theo git diff: " + moved);
      } catch (IllegalArgumentException e) {
        notes.add(e.getMessage());
      }
    }
    if (role == Role.DEV && !(t.status() == TaskStatus.READY || t.status() == TaskStatus.IMPLEMENT)) {
      notes.add("PMO chưa chốt giải pháp (" + t.status().code() + "): Dev chỉ trả lời và ghi đề xuất, không sửa code (FR-73)");
    } else if (role == Role.DEV) {
      notes.addAll(pmoChanges(project, id));
      Map<String, String> seen = baseSeen(project, id);
      try {
        dev.start(project, id, Actor.DEV);
        List<RepoView> repos = dev.view(project, id).repos();
        notes.add("Pull nhánh nền mới nhất vào repo của service: " + repos.stream()
            .map(r -> r.repo() + "@" + (r.baseCommit() == null ? "-" : r.baseCommit().substring(0, 8))).toList());
        notes.addAll(baseUpdates(repos, seen));
      } catch (IllegalArgumentException | IllegalStateException e) {
        if (tasks.detail(project, id).task().status() == TaskStatus.READY) {
          // still Ready: every edit and commit would be refused, so do not spend a Dev session on it
          throw new IllegalStateException("Chưa bắt đầu triển khai được (ticket vẫn Sẵn sàng): " + e.getMessage()
              + ". PMO xử lý repo rồi bấm Triển khai lại");
        }
        notes.add("Không pull được nhánh nền: " + e.getMessage());
      }
    }
    return notes;
  }

  /** Base commit each repo was last seen at: the previous pull of this ticket, else the index SA analysed against. */
  private Map<String, String> baseSeen(String project, String id) {
    Map<String, String> out = new LinkedHashMap<>();
    try {
      graphs.status(project).repos().stream().filter(r -> r.indexedCommit() != null).forEach(r -> out.put(r.repo(), r.indexedCommit()));
    } catch (RuntimeException e) {
      // no index yet: only the previous pull counts
    }
    dev.view(project, id).repos().stream().filter(r -> r.baseCommit() != null).forEach(r -> out.put(r.repo(), r.baseCommit()));
    return out;
  }

  /** Base-branch commits that landed since then: Dev reviews them against plan.md before coding and records it in summary.md. */
  private List<String> baseUpdates(List<RepoView> repos, Map<String, String> seen) {
    List<String> out = new ArrayList<>();
    for (RepoView r : repos) {
      String before = seen.get(r.repo());
      if (before == null || r.baseCommit() == null || before.equals(r.baseCommit())) {
        continue;
      }
      try {
        Path dir = Path.of(r.worktree());
        List<String> commits = git.log(dir, before, r.baseCommit());
        if (!commits.isEmpty()) {
          out.add("Nhánh nền có commit mới ở " + r.repo() + " từ lúc SA phân tích/lần pull trước: " + clip(commits.toString(), 600)
              + "; file: " + clip(git.changedFiles(dir, before, r.baseCommit()).toString(), 800)
              + ". Rà soát với plan.md trước khi code, ghi vào summary.md mục \"Rà soát nhánh nền\".");
        }
      } catch (IllegalStateException e) {
        out.add(r.repo() + ": không so được nhánh nền với " + before.substring(0, 8) + " (" + clip(e.getMessage(), 200) + ")");
      }
    }
    return out;
  }

  /** FR-28: commits and uncommitted files in the worktrees that no Dev session made since the last one. */
  private List<String> pmoChanges(String project, String id) {
    Map<String, String> last = chats.read(tasks.folder(project, id)).stream().filter(m -> m.from().equals("DEV") && !m.heads().isEmpty())
        .reduce((a, b) -> b).map(ChatMessage::heads).orElse(Map.of());
    List<String> out = new ArrayList<>();
    boolean pmo = false;
    for (RepoView r : dev.view(project, id).repos()) {
      if (r.head() == null) {
        continue;
      }
      String before = last.get(r.repo());
      Path wt = Path.of(r.worktree());
      // a head from before a history rewrite is gone: nothing to compare against
      List<String> commits = before == null || before.equals(r.head()) || !git.hasCommit(wt, before) ? List.of()
          : git.log(wt, before, r.head());
      // commits after the last Dev session are the PMO's; an uncommitted file may also be left by that session itself
      if (!commits.isEmpty()) {
        out.add("Commit PMO tự làm ở " + r.repo() + ": " + commits + ". Đọc lại trước khi sửa tiếp.");
      }
      if (!r.uncommitted().isEmpty()) {
        out.add("File chưa commit ở " + r.repo() + " (phiên Dev trước để lại hoặc PMO sửa tay): " + r.uncommitted()
            + ". Xem git_diff trước khi sửa tiếp.");
      }
      pmo |= !commits.isEmpty();
    }
    if (!out.isEmpty()) {
      dev.writeChanges(project, id);
      tasks.note(project, id, Actor.DEVMAP,
          (pmo ? "Phát hiện commit PMO tự làm" : "Worktree còn file chưa commit") + ", đã ghi changes.md");
    }
    return out;
  }

  /**
   * FR-26: Dev commits its own work. Files this session changed but left uncommitted are committed for it with a generated
   * message; a repo that also holds files Dev did not touch (PMO's hand edits) is left for the PMO, as are secrets/approvals.
   */
  private void commitLeftovers(AgentSession s) {
    try {
      for (RepoView r : dev.view(s.project, s.taskId).repos()) {
        Set<String> mine = s.edited.getOrDefault(r.repo(), Set.of());
        if (r.uncommitted().isEmpty() || mine.isEmpty()) {
          continue;
        }
        List<String> others = r.uncommitted().stream().filter(f -> !mine.contains(f)).toList();
        if (!others.isEmpty()) {
          step(s.project, s.taskId, s.messageId, new Step("note", r.repo() + ": không tự commit vì còn file Dev không sửa: " + others));
          continue;
        }
        try {
          dev.commit(s.project, s.taskId, r.repo(),
              "Dev session #" + s.messageId + ": update " + clip(String.join(", ", r.uncommitted()), 200), Actor.DEV);
          step(s.project, s.taskId, s.messageId, new Step("note", r.repo() + ": DevMap tự commit " + r.uncommitted().size()
              + " file Dev đã sửa nhưng chưa commit"));
        } catch (RuntimeException e) {
          step(s.project, s.taskId, s.messageId, new Step("note", r.repo() + ": không tự commit được: " + e.getMessage()));
        }
      }
    } catch (RuntimeException e) {
      step(s.project, s.taskId, s.messageId, new Step("note", "Không kiểm được file chưa commit: " + e.getMessage()));
    }
  }

  /** FR-77: commits in the ticket worktrees after the last Dev session that no Dev session made; null when there are none. */
  public PmoChange pmoCodeChange(String project, String id) {
    Map<String, String> last = chats.read(tasks.folder(project, id)).stream().filter(m -> m.from().equals("DEV") && !m.heads().isEmpty())
        .reduce((a, b) -> b).map(ChatMessage::heads).orElse(Map.of());
    StringBuilder text = new StringBuilder();
    StringBuilder print = new StringBuilder();
    for (RepoView r : dev.view(project, id).repos()) {
      String before = last.get(r.repo());
      if (r.head() == null || before == null || before.equals(r.head()) || !git.hasCommit(Path.of(r.worktree()), before)) {
        continue;
      }
      List<String> commits = git.log(Path.of(r.worktree()), before, r.head());
      if (!commits.isEmpty()) {
        text.append("- ").append(r.repo()).append(": ").append(clip(commits.toString(), 600)).append(", file: ")
            .append(clip(git.changedFiles(Path.of(r.worktree()), before, r.head()).toString(), 600)).append('\n');
        print.append(r.repo()).append('@').append(r.head()).append(';');
      }
    }
    return print.isEmpty() ? null : new PmoChange(print.toString(), text.toString());
  }

  /** AIR-03: Dev handed over → SA verifies before anything reaches the PMO. */
  private void afterSession(String project, String id, Role role, Mode mode) {
    try {
      if (role == Role.DEV && tasks.detail(project, id).task().status() == TaskStatus.VERIFY && !busy(project, id, "SA")) {
        submit(project, id, Role.SA, Mode.VERIFY, "", false, null, false);
      }
    } catch (RuntimeException e) {
      // the PMO can start verification by asking SA
    }
    nextInQueue(project);
  }

  /**
   * AC-04 / FR-07: Dev works the Ready tickets one by one in the order the PMO set. The next one starts only when the previous
   * left development (SA verification passed → waits for push, or Done); a failed or returned ticket stops the line for the PMO.
   */
  public Activity runQueue(String project, boolean on) {
    projects.get(project);
    if (!on) {
      queueRuns.remove(project);
    } else if (!queueRuns.containsKey(project)) {
      tasks.note(project, firstQueued(project), Actor.PMO, "Dev triển khai hàng đợi: làm lần lượt theo thứ tự PMO xếp");
      queueRuns.put(project, "");
      nextInQueue(project);
    }
    return activity(project);
  }

  private String firstQueued(String project) {
    List<Task> q = tasks.queue(project);
    if (q.isEmpty()) {
      throw new IllegalArgumentException("Hàng đợi trống: chưa có ticket nào Sẵn sàng");
    }
    return q.get(0).id();
  }

  private synchronized void nextInQueue(String project) {
    String current = queueRuns.get(project);
    if (current == null) {
      return;
    }
    try {
      if (!current.isEmpty() && (busy(project, current) || inDevelopment(project, current))) {
        return;
      }
      List<Task> q = tasks.queue(project);
      // still In dev (failed) or still Ready (could not start): stop the line for the PMO, never retry the same ticket
      TaskStatus st = current.isEmpty() ? null : tasks.detail(project, current).task().status();
      if (q.isEmpty() || st == TaskStatus.IMPLEMENT || st == TaskStatus.READY) {
        queueRuns.remove(project);
        return;
      }
      String next = q.get(0).id();
      queueRuns.put(project, next);
      submit(project, next, Role.DEV, Mode.ASK, IMPLEMENT + " (hàng đợi, ticket kế tiếp)", false, null, true);
    } catch (RuntimeException e) {
      queueRuns.remove(project);
    }
  }

  private boolean inDevelopment(String project, String id) {
    TaskStatus st = tasks.detail(project, id).task().status();
    return st == TaskStatus.VERIFY || st == TaskStatus.IMPLEMENT && busy(project, id);
  }

  public Activity activity(String project) {
    List<String> running = new ArrayList<>();
    Map<String, String> what = new LinkedHashMap<>();
    List<String> failed = new ArrayList<>();
    Map<String, Integer> questions = new LinkedHashMap<>();
    Map<String, String> now = new LinkedHashMap<>();
    for (Task t : tasks.list(project)) {
      String key = project + "/" + t.id();
      if (busy(project, t.id())) {
        running.add(t.id());
        what.put(t.id(), working.getOrDefault(key, "SA"));
        now.put(t.id(), String.join(" · ", java.util.stream.Stream.of(Role.SA, Role.DEV)
            .map(r -> doing.get(key(project, t.id(), r))).filter(x -> x != null).toList()));
        continue;
      }
      chats.read(tasks.folder(project, t.id())).stream().filter(m -> !m.from().equals("PMO")).reduce((a, b) -> b)
          .filter(m -> m.state().equals("error")).ifPresent(m -> failed.add(t.id()));
      int n = t.status().isClosed() ? 0 : openQuestions(project, t.id());
      if (n > 0) {
        questions.put(t.id(), n);
      }
    }
    String q = queueRuns.get(project);
    return new Activity(running, what, failed, questions, q == null || q.isEmpty() ? null : q, q != null, now);
  }

  /** Items under "Câu hỏi chờ PMO" in summary.md: the same rule as pendingQuestions() of the ticket page's Questions tab. */
  static int countQuestions(String summary) {
    Matcher m = QUESTIONS.matcher(summary);
    if (!m.find()) {
      return 0;
    }
    int n = 0;
    for (String line : summary.substring(m.end()).split("\\R")) {
      if (line.matches("#{1,6}\\s.*")) {
        break;
      }
      Matcher item = ITEM.matcher(line);
      if (item.find() && !NONE.matcher(item.group(1)).matches()) {
        n++;
      }
    }
    return n;
  }

  private int openQuestions(String project, String id) {
    try {
      return countQuestions(tasks.readDoc(project, id, "summary.md").content());
    } catch (NoSuchElementException e) {
      return 0;
    }
  }

  /** Sessions running or waiting for a slot on this machine; `devmap serve` warns before stopping while this is above 0. */
  public int runningCount() {
    return busy.size();
  }

  /** FR-38: the next session must find this one in summary.md even if the agent forgot to write it. */
  private void fallbackSummary(String project, String id, int no, String question, String answer) {
    String old;
    try {
      old = tasks.readDoc(project, id, "summary.md").content();
    } catch (NoSuchElementException e) {
      old = "";
    }
    String add = "\n## Phiên #" + no + " (" + now() + ", DevMap ghi vì agent chưa cập nhật)\n\n- Câu hỏi: " + clip(question, 400)
        + "\n- Trả lời: " + clip(answer, 1200) + "\n";
    tasks.writeDoc(project, id, "summary.md", (old.isBlank() ? "# Tóm tắt – " + id + "\n" : old) + add, Actor.DEVMAP);
  }

  private Map<String, String> heads(String project, String id) {
    Map<String, String> out = new LinkedHashMap<>();
    dev.view(project, id).repos().stream().filter(r -> r.head() != null).forEach(r -> out.put(r.repo(), r.head()));
    return out;
  }

  AgentSession session(String token) {
    AgentSession s = token == null ? null : live.get(token);
    if (s == null) {
      throw new NoSuchElementException("Phiên agent không tồn tại hoặc đã kết thúc");
    }
    return s;
  }

  void logTool(AgentSession s, String name, JsonNode args, String result, boolean error) {
    SecretsConfig secrets = projects.get(s.project).config().secrets();
    chats.log(s.log, "### " + name + " " + masker.mask(String.valueOf(args), secrets).text() + "\n\n```\n" + result + "\n```\n\n");
    JsonNode brief = args.isObject() ? ((ObjectNode) args.deepCopy()).without(List.of("content", "old", "new")) : args;
    String[] f = tools.auditFields(s, name, args);
    events.publishEvent(new AuditLog.Event(Path.of(projects.get(s.project).dataDir()), s.role.name(), s.taskId, f[0], f[1], f[2],
        "Công cụ " + name + " " + clip(masker.mask(String.valueOf(brief), secrets).text(), 300)
            + (error ? " → lỗi: " + clip(result, 200) : "")));
  }

  public ChatView chat(String project, String id) {
    List<ChatMessage> messages = chats.read(tasks.folder(project, id));
    List<String> roles = java.util.stream.Stream.of(Role.SA, Role.DEV).filter(r -> busy.contains(key(project, id, r)))
        .map(Role::name).toList();
    return new ChatView(messages, sum(messages), sum(projectMessages(project)), !roles.isEmpty(), roles);
  }

  /** sync: tokens of the sessions started by events, so the PMO sees what the automatic sync costs (PROMPT_ROOM §3). */
  public UsageView usage(String project) {
    List<ChatMessage> all = projectMessages(project);
    String today = now().substring(0, 10);
    List<ChatMessage> sync = all.stream().filter(m -> "sync".equals(m.mode())).toList();
    return new UsageView(sum(all), sum(all.stream().filter(m -> m.at().startsWith(today)).toList()), busy.size(),
        quota.values().stream().sorted(java.util.Comparator.comparing(Quota::type)).toList(), sum(sync), sync.size());
  }

  private List<ChatMessage> projectMessages(String project) {
    return tasks.list(project).stream().flatMap(t -> chats.read(tasks.folder(project, t.id())).stream()).toList();
  }

  private void quota(String json) {
    try {
      JsonNode n = JSON.readTree(json);
      String type = n.path("rateLimitType").asText("unknown");
      quota.put(type, new Quota(type, n.path("status").asText(), n.hasNonNull("resetsAt") ? n.get("resetsAt").asLong() : null,
          n.hasNonNull("utilization") ? n.get("utilization").asDouble() : null, now()));
    } catch (java.io.IOException e) {
      // a malformed quota line only means the box keeps its previous value
    }
  }

  /** The input log of one session (AC-10, AC-12: proof of what the LLM received). Gone once the ticket is Done (FR-45). */
  public String inputLog(String project, String id, int no) {
    tasks.folder(project, id);
    Path f = sessions.resolve(project).resolve(id).resolve(no + ".log");
    try {
      return Files.readString(f);
    } catch (java.io.IOException e) {
      throw new NoSuchElementException("Không còn nhật ký đầu vào của phiên #" + no + " (ticket đã đóng hoặc phiên chưa chạy)");
    }
  }

  /** FR-45 / AC-12: once the ticket is Done or Cancelled, drop the session context of the ticket; only its documents remain. */
  @EventListener
  public void onTaskChanged(TaskChanged e) {
    if (e.taskId() == null) {
      return;
    }
    if (queueRuns.containsKey(e.project())) {
      nextInQueue(e.project());
    }
    try {
      if (tasks.detail(e.project(), e.taskId()).task().status().isClosed() && !busy(e.project(), e.taskId())) {
        live.values().removeIf(s -> s.project.equals(e.project()) && s.taskId.equals(e.taskId()));
        chats.deleteTree(sessions.resolve(e.project()).resolve(e.taskId()));
      }
    } catch (NoSuchElementException | IllegalStateException ex) {
      // ticket or project gone
    }
  }

  /** prompt for `devmap prompt`: exactly what a session would receive now, without running it. */
  public String preview(String project, String id, String role, String question) {
    Task t = tasks.detail(project, id).task();
    Role r = role(role, t.status(), projects.get(project).config().room().devCanProposeBeforeApproval());
    ContextBuilder.Context c = context.build(project, id, r, t.status() == TaskStatus.VERIFY && r == Role.SA ? Mode.VERIFY : Mode.ASK,
        question == null ? "" : masker.mask(question, projects.get(project).config().secrets()).text(), List.of(), 0);
    return "# System prompt (tĩnh, cache)\n\n" + c.system() + "\n\n# Prompt\n\n" + c.prompt() + "\n\n# Công cụ\n\n"
        + tools.list(r).stream().map(x -> "- " + x.name() + ": " + x.description()).reduce("", (a, b) -> a + b + "\n");
  }

  /**
   * FR-73: before the PMO approves, Dev may still answer and write proposals (room.dev_can_propose_before_approval); its tools
   * refuse any code change until the ticket is in IMPLEMENT, whatever the prompt says.
   */
  private static Role role(String role, TaskStatus status, boolean devCanPropose) {
    if (status == TaskStatus.CANCELLED) {
      throw new IllegalArgumentException("Ticket đã huỷ, không mở phiên agent");
    }
    boolean devTime = status == TaskStatus.READY || status == TaskStatus.IMPLEMENT;
    if (role == null || role.isBlank()) {
      return devTime ? Role.DEV : Role.SA;
    }
    Role r = Role.valueOf(role.toUpperCase(Locale.ROOT));
    if (r == Role.DEV && !devTime && (!devCanPropose || status.isClosed())) {
      throw new PmoOnlyException("Agent Dev chỉ làm khi PMO đã chốt giải pháp (Sẵn sàng / Đang triển khai)");
    }
    return r;
  }

  /** Every step goes through the masker here: notes carry git output and error messages, not only agent events. */
  private void step(String project, String id, int no, Step step) {
    String text = clip(masker.mask(step.text(), projects.get(project).config().secrets()).text(), 4000);
    update(project, id, no, m -> {
      List<Step> steps = new ArrayList<>(m.steps());
      steps.add(new Step(step.kind(), text));
      return m.with(m.text(), steps, m.usage(), m.state(), m.heads());
    });
    progress(project, id, no, "running");
  }

  private void finish(String project, String id, int no, String text, Usage usage, String state, Map<String, String> heads) {
    update(project, id, no, m -> m.with(text, m.steps(), usage, state, heads));
    progress(project, id, no, state);
  }

  private synchronized void update(String project, String id, int no, java.util.function.UnaryOperator<ChatMessage> change) {
    Path folder = tasks.folder(project, id);
    List<ChatMessage> all = new ArrayList<>(chats.read(folder));
    all.replaceAll(m -> m.id() == no ? change.apply(m) : m);
    chats.write(folder, all);
  }

  private void progress(String project, String id, int no, String state) {
    events.publishEvent(new AgentProgress(project, id, no, state));
  }

  private String mcpUrl() {
    return "http://127.0.0.1:" + env.getProperty("local.server.port", env.getProperty("server.port", "7070")) + "/mcp";
  }

  private static Usage sum(List<ChatMessage> messages) {
    return messages.stream().map(ChatMessage::usage).filter(u -> u != null).reduce(Usage.ZERO, Usage::plus);
  }

  private static String clip(String s, int max) {
    String t = s == null ? "" : s.strip();
    return t.length() <= max ? t : t.substring(0, max) + "…";
  }

  private static String now() {
    return LocalDateTime.now().format(TIME);
  }
}
