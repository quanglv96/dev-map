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
import com.devmap.ticket.PmoOnlyException;
import com.devmap.ticket.Task;
import com.devmap.ticket.TaskChanged;
import com.devmap.ticket.TaskService;
import com.devmap.ticket.TaskService.Actor;
import com.devmap.ticket.TaskStatus;
import com.devmap.ticket.TicketGitService;
import com.devmap.ticket.TicketGitService.RepoView;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * Agent runner (FR-37 → FR-40, FR-43, FR-45, AIR-03/04). Each PMO question starts one new session built only from the ticket's
 * documents; the chat is stored for the PMO but never sent. SA sessions first update the index by git diff (pull), Dev sessions
 * first pull the base branch into the ticket worktree. When Dev hands over (sa_verify), an SA verification session starts by itself.
 * When the ticket is Done, the session scratch (input logs, work folders) is deleted; documents stay.
 */
@Service
public class AgentService {

  /** For the UI: the chat plus token totals of the ticket and of the whole project (FR-40). */
  public record ChatView(List<ChatMessage> messages, Usage ticket, Usage project, boolean running) {
  }

  /** Published on every step of a session; the web layer forwards it as the SSE event "agent". */
  public record AgentProgress(String project, String taskId, int messageId, String state) {
  }

  /** Tab title and icon (SRS "Hiển thị tiến trình"): tickets with an agent running, tickets whose last session failed. */
  public record Activity(List<String> running, List<String> failed, String queueTicket, boolean queueRunning) {
  }

  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

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
  // ponytail: queue runs live in memory; a restart stops them and the PMO presses "Chạy hàng đợi" again
  private final Map<String, String> queueRuns = new ConcurrentHashMap<>();

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
    Task t = tasks.detail(project, id).task();
    Role r = role(role, t.status());
    String q = masker.mask(question.strip(), projects.get(project).config().secrets()).text();
    return submit(project, id, r, t.status() == TaskStatus.SA_VERIFY && r == Role.SA ? Mode.VERIFY : Mode.ASK, q, fullScan);
  }

  private ChatMessage submit(String project, String id, Role role, Mode mode, String question, boolean fullScan) {
    String key = project + "/" + id;
    if (!busy.add(key)) {
      throw new IllegalArgumentException("Agent đang chạy cho " + id + "; chờ phiên này xong");
    }
    ChatMessage msg;
    try {
      Path folder = tasks.folder(project, id);
      List<ChatMessage> all = new ArrayList<>(chats.read(folder));
      if (mode == Mode.ASK) {
        all.add(new ChatMessage(all.size() + 1, now(), "PMO", "ask", question, List.of(), List.of(), null, "done", null));
      }
      msg = new ChatMessage(all.size() + 1, now(), role.name(), mode.name().toLowerCase(Locale.ROOT), "", List.of(),
          List.of(new Step("note", "Đang chờ lượt (tối đa số agent chạy cùng lúc)")), null, "running", null);
      all.add(msg);
      chats.write(folder, all);
      if (mode == Mode.ASK) {
        tasks.note(project, id, Actor.PMO, "Hỏi " + role + " (câu #" + msg.id() + ")");
      } else {
        tasks.note(project, id, Actor.DEVMAP, "Dev đã xong, tự mở phiên SA kiểm chứng (#" + msg.id() + ")");
      }
    } catch (RuntimeException e) {
      busy.remove(key);
      throw e;
    }
    int no = msg.id();
    progress(project, id, no, "running");
    runner.execute(() -> {
      try {
        run(project, id, role, mode, question, fullScan, no);
      } catch (RuntimeException e) {
        finish(project, id, no, "Lỗi: " + masker.mask(String.valueOf(e.getMessage())).text(), Usage.ZERO, "error", null);
      } finally {
        busy.remove(key);
      }
      afterSession(project, id, role, mode);
    });
    return msg;
  }

  private void run(String project, String id, Role role, Mode mode, String question, boolean fullScan, int no) {
    List<String> notes = prepare(project, id, role, fullScan);
    notes.forEach(n -> step(project, id, no, new Step("note", n)));
    ContextBuilder.Context ctx = context.build(project, id, role, mode, question, notes, no);
    Path dir = sessions.resolve(project).resolve(id);
    AgentSession s = new AgentSession(UUID.randomUUID().toString(), project, id, role, mode, no, dir.resolve(String.valueOf(no)),
        dir.resolve(no + ".log"));
    chats.log(s.log, "# Đầu vào phiên #" + no + " (" + role + ", " + mode + ") – " + now() + "\n\n## System prompt (tĩnh, cache)\n\n"
        + ctx.system() + "\n\n## Prompt\n\n" + ctx.prompt() + "\n\n## Kết quả công cụ\n\n");
    update(project, id, no, m -> new ChatMessage(m.id(), m.at(), m.from(), m.mode(), m.text(), ctx.files(), m.steps(), m.usage(),
        m.state(), m.heads()));
    live.put(s.token, s);
    AgentProvider.Result result;
    try {
      Files.createDirectories(s.workDir);
      result = provider.run(new AgentProvider.Call(ctx.system(), ctx.prompt(), s.workDir, mcpUrl(), s.token),
          e -> step(project, id, no, new Step(e.kind(), masker.mask(e.text()).text())));
    } catch (java.io.IOException e) {
      result = new AgentProvider.Result(false, "Không tạo được thư mục phiên: " + e.getMessage(), Usage.ZERO);
    } finally {
      live.remove(s.token);
      tools.forget(s);
      chats.deleteTree(s.workDir);
    }
    String answer = masker.mask(result.text(), projects.get(project).config().secrets()).text();
    if (!s.written.contains("summary.md")) {
      fallbackSummary(project, id, no, question, answer);
    }
    finish(project, id, no, answer, result.usage(), result.ok() ? "done" : "error", role == Role.DEV ? heads(project, id) : null);
    Usage u = result.usage();
    tasks.note(project, id, role.actor, (mode == Mode.VERIFY ? "Kiểm chứng" : "Trả lời câu #" + no) + (result.ok() ? "" : " (lỗi)")
        + ", token vào " + (u.input() + u.cacheRead() + u.cacheWrite()) + " (cache đọc " + u.cacheRead() + "), ra " + u.output());
  }

  /** AIR-04 / FR-24 / FR-28 / AC-09: what DevMap does before the agent starts. Failures become notes, never silent. */
  private List<String> prepare(String project, String id, Role role, boolean fullScan) {
    List<String> notes = new ArrayList<>();
    Task t = tasks.detail(project, id).task();
    if (role == Role.SA && t.status() == TaskStatus.NEW) {
      tasks.transition(project, id, TaskStatus.SA_ANALYSIS.code(), Actor.SA);
    }
    if (fullScan) {
      GraphService.Report r = graphs.update(project, true);
      notes.add("PMO bật quét toàn bộ cho câu này: đọc " + r.repos().stream().mapToInt(GraphService.RepoReport::parsed).sum()
          + " file, chỉ cập nhật chỉ mục chữ ký trên máy; tự tắt sau câu này");
    } else if (role == Role.SA) {
      try {
        GraphService.Report r = graphs.update(project, false);
        notes.add("Fetch nhánh nền và cập nhật chỉ mục theo git diff: " + r.repos().stream()
            .map(x -> x.repo() + " " + (x.parsed() > 0 ? "đọc " + x.parsed() + " file" : x.note())).toList());
      } catch (IllegalArgumentException e) {
        notes.add(e.getMessage());
      }
    }
    if (role == Role.DEV) {
      notes.addAll(pmoChanges(project, id));
      try {
        dev.start(project, id, Actor.DEV);
        notes.add("Pull nhánh nền mới nhất vào worktree của ticket: " + dev.view(project, id).repos().stream()
            .map(r -> r.repo() + "@" + (r.baseCommit() == null ? "-" : r.baseCommit().substring(0, 8))).toList());
      } catch (IllegalArgumentException | IllegalStateException e) {
        notes.add("Không pull được nhánh nền: " + e.getMessage());
      }
    }
    return notes;
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
      List<String> commits = before == null || before.equals(r.head()) ? List.of() : git.log(Path.of(r.worktree()), before, r.head());
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

  /** AIR-03: Dev handed over → SA verifies before anything reaches the PMO. */
  private void afterSession(String project, String id, Role role, Mode mode) {
    try {
      if (role == Role.DEV && tasks.detail(project, id).task().status() == TaskStatus.SA_VERIFY) {
        submit(project, id, Role.SA, Mode.VERIFY, "", false);
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
      tasks.note(project, firstQueued(project), Actor.PMO, "Chạy hàng đợi: Dev làm lần lượt theo thứ tự PMO xếp");
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
      if (!current.isEmpty() && (busy.contains(project + "/" + current) || inDevelopment(project, current))) {
        return;
      }
      List<Task> q = tasks.queue(project);
      if (q.isEmpty() || !current.isEmpty() && tasks.detail(project, current).task().status() == TaskStatus.IN_DEV) {
        queueRuns.remove(project);
        return;
      }
      String next = q.get(0).id();
      queueRuns.put(project, next);
      submit(project, next, Role.DEV, Mode.ASK, "Triển khai theo plan.md đã chốt (hàng đợi, ticket kế tiếp)", false);
    } catch (RuntimeException e) {
      queueRuns.remove(project);
    }
  }

  private boolean inDevelopment(String project, String id) {
    TaskStatus st = tasks.detail(project, id).task().status();
    return st == TaskStatus.SA_VERIFY || st == TaskStatus.IN_DEV && busy.contains(project + "/" + id);
  }

  public Activity activity(String project) {
    List<String> running = new ArrayList<>();
    List<String> failed = new ArrayList<>();
    for (Task t : tasks.list(project)) {
      if (busy.contains(project + "/" + t.id())) {
        running.add(t.id());
        continue;
      }
      chats.read(tasks.folder(project, t.id())).stream().filter(m -> !m.from().equals("PMO")).reduce((a, b) -> b)
          .filter(m -> m.state().equals("error")).ifPresent(m -> failed.add(t.id()));
    }
    String q = queueRuns.get(project);
    return new Activity(running, failed, q == null || q.isEmpty() ? null : q, q != null);
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
    Usage total = Usage.ZERO;
    for (Task t : tasks.list(project)) {
      total = total.plus(sum(chats.read(tasks.folder(project, t.id()))));
    }
    return new ChatView(messages, sum(messages), total, busy.contains(project + "/" + id));
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

  /** FR-45 / AC-12: once the PMO signed off (Done), drop the session context of the ticket; only its documents remain. */
  @EventListener
  public void onTaskChanged(TaskChanged e) {
    if (e.taskId() == null) {
      return;
    }
    if (queueRuns.containsKey(e.project())) {
      nextInQueue(e.project());
    }
    try {
      if (tasks.detail(e.project(), e.taskId()).task().status() == TaskStatus.DONE && !busy.contains(e.project() + "/" + e.taskId())) {
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
    Role r = role(role, t.status());
    ContextBuilder.Context c = context.build(project, id, r, t.status() == TaskStatus.SA_VERIFY && r == Role.SA ? Mode.VERIFY : Mode.ASK,
        question == null ? "" : masker.mask(question, projects.get(project).config().secrets()).text(), List.of(), 0);
    return "# System prompt (tĩnh, cache)\n\n" + c.system() + "\n\n# Prompt\n\n" + c.prompt() + "\n\n# Công cụ\n\n"
        + tools.list(r).stream().map(x -> "- " + x.name() + ": " + x.description()).reduce("", (a, b) -> a + b + "\n");
  }

  private static Role role(String role, TaskStatus status) {
    boolean devTime = status == TaskStatus.READY || status == TaskStatus.IN_DEV;
    if (role == null || role.isBlank()) {
      return devTime ? Role.DEV : Role.SA;
    }
    Role r = Role.valueOf(role.toUpperCase(Locale.ROOT));
    if (r == Role.DEV && !devTime) {
      throw new PmoOnlyException("Agent Dev chỉ làm khi PMO đã chốt giải pháp (Sẵn sàng / Đang triển khai)");
    }
    return r;
  }

  private void step(String project, String id, int no, Step step) {
    update(project, id, no, m -> {
      List<Step> steps = new ArrayList<>(m.steps());
      steps.add(new Step(step.kind(), clip(step.text(), 4000)));
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
