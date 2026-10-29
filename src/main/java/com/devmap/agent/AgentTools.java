package com.devmap.agent;

import com.devmap.agent.AgentSession.Mode;
import com.devmap.agent.AgentSession.Role;
import com.devmap.config.Project;
import com.devmap.config.ProjectService;
import com.devmap.config.RepoConfig;
import com.devmap.git.GitService;
import com.devmap.graph.Graph;
import com.devmap.graph.Graph.Direction;
import com.devmap.graph.Graph.Node;
import com.devmap.graph.Graph.Subgraph;
import com.devmap.graph.GraphService;
import com.devmap.rule.QualityGate;
import com.devmap.rule.RuleService;
import com.devmap.secret.SecretFiles;
import com.devmap.secret.SecretMasker;
import com.devmap.ticket.Task;
import com.devmap.ticket.TaskDocs;
import com.devmap.ticket.TaskService;
import com.devmap.ticket.TaskStatus;
import com.devmap.ticket.TicketGitService;
import com.devmap.ticket.TicketGitService.RepoView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The only way an agent touches the project (CLAUDE.md §4, AIR-09/10, FR-34 → FR-36): signatures and snippets from the graph,
 * ticket documents, diffs, the gate, commits and file edits in the ticket worktree. Secret files are refused by path before any
 * read; every result goes through the masker. No tool returns a whole file or folder, and the raw PMO request (task.md, bugs/)
 * is never served: the agent sees spec.json (FR-44).
 */
@Component
class AgentTools {

  record Tool(String name, String description, ObjectNode schema, Set<Role> roles) {
  }

  static final int SNIPPET_LINES = 80;
  private static final int MAX_TEXT = 40_000;
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Pattern DIFF_FILE = Pattern.compile("(?m)^diff --git a/(\\S+) b/");
  private static final Map<Role, List<String>> DOCS = Map.of(
      Role.SA, List.of("analysis.md", "plan.md", "summary.md", "verify.md"),
      Role.DEV, List.of("summary.md"));
  private static final Map<Role, List<String>> MOVES = Map.of(
      Role.SA, List.of(TaskStatus.AWAITING_APPROVAL.code(), TaskStatus.IN_DEV.code(), TaskStatus.AWAITING_PUSH.code()),
      Role.DEV, List.of(TaskStatus.SA_VERIFY.code()));

  private final List<Tool> tools = List.of(
      tool("get_spec", "spec.json của ticket: yêu cầu PMO đã làm sạch (không có nguyên văn).", Set.of(Role.SA, Role.DEV)),
      tool("get_ticket_docs", "Đọc tài liệu của ticket: analysis.md, plan.md, summary.md, changes.md, tests.md, verify.md, history.md.",
          Set.of(Role.SA, Role.DEV), "names:array:Tên tài liệu"),
      tool("search_graph", "Tìm node trong dependency graph theo tên và trả lân cận tối đa 3 tầng (chỉ chữ ký).", Set.of(Role.SA, Role.DEV),
          "query:string:Tên class/method/API/service/topic/table", "depth?:integer:1-3, mặc định 1",
          "direction?:string:in (ai phụ thuộc vào nó) | out | both"),
      tool("get_signatures", "Chữ ký class/method của một node (class/service) hoặc của một file.", Set.of(Role.SA, Role.DEV),
          "node?:string:Id node, ví dụ cls:order-service:OrderService", "repo?:string:Tên repo", "file?:string:Đường dẫn trong repo"),
      tool("get_snippet", "Đoạn code của một method (node fn:...) hoặc một khoảng dòng tối đa " + SNIPPET_LINES
          + " dòng; đã che bí mật. Không trả nguyên file.", Set.of(Role.SA, Role.DEV),
          "node?:string:Id node fn:...", "repo?:string:Tên repo", "file?:string:Đường dẫn trong repo",
          "from?:integer:Dòng đầu", "to?:integer:Dòng cuối"),
      tool("write_ticket_doc", "Ghi tài liệu của ticket (SA: analysis.md, plan.md, summary.md, verify.md; Dev: summary.md). Bí mật bị che.",
          Set.of(Role.SA, Role.DEV), "name:string:Tên tài liệu", "content:string:Nội dung Markdown đầy đủ"),
      tool("set_status", "Chuyển trạng thái ticket. SA: awaiting_approval (gửi PMO chốt), in_dev (trả về Dev), awaiting_push "
          + "(kiểm chứng đạt). Dev: sa_verify (xong, gate đạt). Không chốt giải pháp hay push thay PMO được.",
          Set.of(Role.SA, Role.DEV), "to:string:Trạng thái đích"),
      tool("verify_facts", "Dữ kiện kiểm chứng DevMap tính từ git diff và graph: file lệch phạm vi, chữ ký/API bị đổi và nơi gọi.",
          Set.of(Role.SA)),
      tool("git_diff", "Diff của branch ticket so với nhánh nền ở một repo (cả phần chưa commit); đã che bí mật.",
          Set.of(Role.SA, Role.DEV),
          "repo:string:Tên repo"),
      tool("run_quality_gate", "Chạy quality gate (tách lớp, phân tích tĩnh, unit test) ở mọi repo của ticket, ghi tests.md.",
          Set.of(Role.DEV)),
      tool("commit", "Commit mọi thay đổi trong worktree của repo, message tự gắn mã ticket.", Set.of(Role.DEV),
          "repo:string:Tên repo", "message:string:Nội dung commit"),
      tool("write_file", "Tạo/ghi đè một file trong worktree của ticket.", Set.of(Role.DEV),
          "repo:string:Tên repo", "path:string:Đường dẫn trong repo", "content:string:Nội dung đầy đủ"),
      tool("edit_file", "Thay đúng một chỗ old bằng new trong một file của worktree.", Set.of(Role.DEV),
          "repo:string:Tên repo", "path:string:Đường dẫn trong repo", "old:string:Đoạn cũ, phải khớp đúng một lần",
          "new:string:Đoạn mới"));

  private final TaskService tasks;
  private final TicketGitService dev;
  private final GraphService graphs;
  private final GitService git;
  private final ProjectService projects;
  private final RuleService rules;
  private final SecretMasker masker;
  private final VerifyFacts verifier;
  private final Map<String, VerifyFacts.Facts> facts = new ConcurrentHashMap<>();

  AgentTools(TaskService tasks, TicketGitService dev, GraphService graphs, GitService git, ProjectService projects, RuleService rules,
      SecretMasker masker, VerifyFacts verifier) {
    this.tasks = tasks;
    this.dev = dev;
    this.graphs = graphs;
    this.git = git;
    this.projects = projects;
    this.rules = rules;
    this.masker = masker;
    this.verifier = verifier;
  }

  List<Tool> list(Role role) {
    return tools.stream().filter(t -> t.roles().contains(role)).toList();
  }

  /** Runs a tool for the session; the returned text is masked. Errors are exceptions whose message goes back to the agent. */
  String call(AgentSession s, String name, JsonNode args) {
    Tool tool = tools.stream().filter(t -> t.name().equals(name)).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Không có công cụ " + name));
    if (!tool.roles().contains(s.role)) {
      throw new IllegalArgumentException("Vai " + s.role + " không dùng được " + name);
    }
    String out = switch (name) {
      case "get_spec" -> doc(s, "spec.json");
      case "get_ticket_docs" -> docs(s, args.path("names"));
      case "search_graph" -> search(s, text(args, "query"), args.path("depth").asInt(1), args.path("direction").asText("both"));
      case "get_signatures" -> signatures(s, args);
      case "get_snippet" -> snippet(s, args);
      case "write_ticket_doc" -> writeDoc(s, text(args, "name"), text(args, "content"));
      case "set_status" -> setStatus(s, text(args, "to"));
      case "verify_facts" -> facts(s).markdown();
      case "git_diff" -> diff(s, text(args, "repo"));
      case "run_quality_gate" -> gate(s);
      case "commit" -> commit(s, text(args, "repo"), text(args, "message"));
      case "write_file" -> writeFile(s, text(args, "repo"), text(args, "path"), text(args, "content"));
      case "edit_file" -> editFile(s, text(args, "repo"), text(args, "path"), text(args, "old"), text(args, "new"));
      default -> throw new IllegalArgumentException("Không có công cụ " + name);
    };
    return mask(s, cap(out));
  }

  /** Facts are computed once per verify session (and again after Dev changed code, since each session is new). */
  VerifyFacts.Facts facts(AgentSession s) {
    return facts.computeIfAbsent(s.token,
        k -> verifier.compute(s.project, s.taskId, optDoc(s, "analysis.md") + "\n" + optDoc(s, "plan.md")));
  }

  void forget(AgentSession s) {
    facts.remove(s.token);
  }

  private String docs(AgentSession s, JsonNode names) {
    if (!names.isArray() || names.isEmpty()) {
      throw new IllegalArgumentException("names phải là mảng tên tài liệu");
    }
    StringBuilder sb = new StringBuilder();
    for (JsonNode n : names) {
      String name = n.asText();
      if (!TaskDocs.ALL.contains(name) || name.equals("task.md")) {
        throw new IllegalArgumentException("Không đọc được " + name + " (yêu cầu gốc chỉ có ở dạng spec.json)");
      }
      sb.append("## ").append(name).append("\n\n").append(optDoc(s, name).isEmpty() ? "(chưa có)" : optDoc(s, name)).append("\n\n");
    }
    return sb.toString();
  }

  private String search(AgentSession s, String query, int depth, String direction) {
    Graph g = graphs.graph(s.project);
    List<Node> hits = g.search(query, 10);
    if (hits.isEmpty()) {
      return "Không có node nào khớp \"" + query + "\"" + (g.nodeCount() <= projects.get(s.project).config().repos().size() * 3
          ? ". Graph chưa có chỉ mục: PMO có thể bật \"Quét toàn bộ dự án\" cho câu hỏi." : "");
    }
    Direction dir = switch (direction) {
      case "in" -> Direction.IN;
      case "out" -> Direction.OUT;
      default -> Direction.BOTH;
    };
    StringBuilder sb = new StringBuilder("Khớp:\n");
    hits.forEach(n -> sb.append(line(n)));
    Subgraph sub = g.neighborhood(hits.stream().limit(3).map(Node::id).toList(), depth, dir, 80);
    sb.append("\nLân cận ").append(Math.max(1, Math.min(depth, 3))).append(" tầng (").append(direction).append("):\n");
    sub.nodes().forEach(n -> sb.append(line(n)));
    sb.append("\nEdge:\n");
    sub.edges().forEach(e -> sb.append("- ").append(e.from()).append(" -").append(e.type()).append("/").append(e.confidence())
        .append("-> ").append(e.to()).append('\n'));
    return sb.append(sub.truncated() ? "(đã cắt bớt)\n" : "").toString();
  }

  private String signatures(AgentSession s, JsonNode args) {
    Graph g = graphs.graph(s.project);
    List<Node> nodes;
    if (args.hasNonNull("node")) {
      String id = args.get("node").asText();
      if (g.node(id) == null) {
        throw new NoSuchElementException("Không có node " + id);
      }
      List<String> children = g.edges().stream().filter(e -> e.from().equals(id) && e.type().equals("contains"))
          .map(Graph.Edge::to).toList();
      nodes = new ArrayList<>(List.of(g.node(id)));
      children.stream().map(g::node).forEach(nodes::add);
    } else {
      String file = safePath(text(args, "file"));
      nodes = g.nodesIn(text(args, "repo"), file);
    }
    StringBuilder sb = new StringBuilder();
    nodes.stream().limit(150).forEach(n -> sb.append(line(n)));
    return sb.length() == 0 ? "Không có chữ ký nào (file chưa được index?)" : sb.toString();
  }

  private String snippet(AgentSession s, JsonNode args) {
    String repo;
    String file;
    int from;
    int to;
    if (args.hasNonNull("node")) {
      Node n = graphs.graph(s.project).node(args.get("node").asText());
      if (n == null || !n.kind().equals("function")) {
        throw new IllegalArgumentException("get_snippet theo node chỉ nhận method/function (fn:...); class dùng get_signatures");
      }
      repo = n.repo();
      file = n.file();
      from = n.line();
      to = Math.min(n.endLine(), n.line() + SNIPPET_LINES - 1);
    } else {
      repo = text(args, "repo");
      file = text(args, "file");
      from = args.path("from").asInt(1);
      to = args.path("to").asInt(from + SNIPPET_LINES - 1);
      if (to < from || to - from >= SNIPPET_LINES) {
        throw new IllegalArgumentException("Tối đa " + SNIPPET_LINES + " dòng mỗi lần");
      }
    }
    String path = safePath(file);
    String text = source(s, repo, path);
    if (text == null) {
      throw new NoSuchElementException(repo + ":" + path + " không tồn tại");
    }
    String[] lines = text.split("\n", -1);
    StringBuilder sb = new StringBuilder(repo + ":" + path + " dòng " + from + "-" + Math.min(to, lines.length) + "\n");
    for (int i = Math.max(1, from); i <= Math.min(to, lines.length); i++) {
      sb.append(i).append(": ").append(lines[i - 1]).append('\n');
    }
    return sb.toString();
  }

  /** The ticket worktree when there is one (Dev's current code), else the base-branch commit (what the index was built from). */
  private String source(AgentSession s, String repo, String path) {
    RepoView w = worktree(s, repo);
    if (w != null) {
      Path f = Path.of(w.worktree()).resolve(path);
      try {
        return Files.exists(f) ? Files.readString(f).replace("\r\n", "\n") : null;
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    RepoConfig rc = repoConfig(s, repo);
    String head = git.resolveBranch(Path.of(rc.path()), rc.baseBranch());
    return head == null ? null : git.show(Path.of(rc.path()), head, path);
  }

  private String writeDoc(AgentSession s, String name, String content) {
    if (!DOCS.get(s.role).contains(name)) {
      throw new IllegalArgumentException(s.role + " chỉ ghi được " + DOCS.get(s.role));
    }
    TaskService.Doc d = tasks.writeDoc(s.project, s.taskId, name, content, s.role.actor);
    s.written.add(name);
    return "Đã ghi " + name + (d.versions() > 0 ? " (v" + d.versions() + ")" : "") + (d.maskedCount() > 0 ? ", đã che " + d.maskedCount()
        + " giá trị giống bí mật" : "");
  }

  private String setStatus(AgentSession s, String to) {
    if (!MOVES.get(s.role).contains(to)) {
      throw new IllegalArgumentException(s.role + " chỉ chuyển được sang " + MOVES.get(s.role)
          + "; chốt giải pháp và push là việc của PMO");
    }
    Task t = tasks.detail(s.project, s.taskId).task();
    if (to.equals(TaskStatus.AWAITING_APPROVAL.code()) && (optDoc(s, "analysis.md").isBlank() || optDoc(s, "plan.md").isBlank())) {
      throw new IllegalArgumentException("Cần analysis.md và plan.md trước khi gửi PMO chốt");
    }
    if (t.status() == TaskStatus.SA_VERIFY) {
      requireVerified(s, t, to.equals(TaskStatus.AWAITING_PUSH.code()));
    }
    tasks.transition(s.project, s.taskId, to, s.role.actor);
    return "Ticket chuyển sang " + to;
  }

  /** FR-43 / FR-11 / AC-11: SA decides only after writing verify.md in this session; passing needs every flagged item addressed. */
  private void requireVerified(AgentSession s, Task t, boolean passing) {
    if (s.mode != Mode.VERIFY || !s.written.contains("verify.md")) {
      throw new IllegalArgumentException("Ghi verify.md (đối chiếu AC với diff) trong phiên kiểm chứng trước khi quyết");
    }
    if (!passing) {
      return;
    }
    String verify = optDoc(s, "verify.md").toLowerCase();
    List<String> missing = facts(s).mustMention().stream().filter(m -> !verify.contains(m.toLowerCase())).toList();
    if (!missing.isEmpty()) {
      throw new IllegalArgumentException("verify.md chưa xét: " + missing
          + ". Lệch phạm vi hoặc chưa kiểm nơi gọi thì trả về Dev (in_dev)");
    }
    if (t.reopened() > 0) {
      List<String> bugs = tasks.detail(s.project, s.taskId).docs().stream().filter(d -> d.startsWith("bugs/")).toList();
      String last = bugs.isEmpty() ? null : bugs.get(bugs.size() - 1).replaceAll("^bugs/|\\.md$", "");
      if (last != null && !optDoc(s, "analysis.md").contains(last)) {
        throw new IllegalArgumentException("Ticket mở lại: cập nhật analysis.md theo logic đúng và thêm case " + last
            + " vào AC trước (FR-11)");
      }
    }
  }

  private String diff(AgentSession s, String repo) {
    if (worktree(s, repo) == null) {
      throw new NoSuchElementException(repo + ": chưa có worktree");
    }
    return withoutSecretFiles(dev.diff(s.project, s.taskId, repo));
  }

  /** Drops whole file blocks of secret files from a diff before masking (FR-34). */
  static String withoutSecretFiles(String diff) {
    StringBuilder out = new StringBuilder();
    for (String block : diff.split("(?m)(?=^diff --git )")) {
      var m = DIFF_FILE.matcher(block);
      if (m.find() && SecretFiles.isSecret(m.group(1))) {
        out.append("diff --git a/").append(m.group(1)).append(" (file bí mật, không hiển thị)\n");
      } else {
        out.append(block);
      }
    }
    return out.toString();
  }

  private String gate(AgentSession s) {
    StringBuilder sb = new StringBuilder();
    for (QualityGate.Result r : dev.gate(s.project, s.taskId, s.role.actor)) {
      sb.append(r.service()).append(": ").append(r.passed() ? "ĐẠT" : "KHÔNG ĐẠT").append('\n');
      r.steps().forEach(st -> sb.append("- ").append(st.name()).append(": ").append(st.passed() ? "đạt" : "không đạt\n" + st.output())
          .append('\n'));
    }
    return sb.append("Chi tiết trong tests.md").toString();
  }

  private String commit(AgentSession s, String repo, String message) {
    RepoView r = dev.commit(s.project, s.taskId, repo, message, s.role.actor).repos().stream()
        .filter(v -> v.repo().equals(repo)).findFirst().orElseThrow();
    return "Đã commit ở " + repo + ": " + (r.commits().isEmpty() ? "" : r.commits().get(0));
  }

  private String writeFile(AgentSession s, String repo, String path, String content) {
    Path f = editable(s, repo, path);
    if (content.contains("[MASKED_")) {
      throw new IllegalArgumentException("Nội dung có giá trị đã che [MASKED_…]: không ghi giá trị che vào code; PMO tự điền nếu cần");
    }
    try {
      Files.createDirectories(f.getParent());
      Files.writeString(f, content);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return "Đã ghi " + repo + ":" + path + scopeWarning(s, repo, path);
  }

  private String editFile(AgentSession s, String repo, String path, String old, String now) {
    Path f = editable(s, repo, path);
    if (old.contains("[MASKED_") || now.contains("[MASKED_")) {
      throw new IllegalArgumentException("Đoạn có giá trị đã che [MASKED_…]: dòng chứa bí mật do PMO tự sửa");
    }
    try {
      String raw = Files.readString(f);
      boolean crlf = raw.contains("\r\n");
      String text = crlf ? raw.replace("\r\n", "\n") : raw;
      int at = text.indexOf(old);
      if (old.isEmpty() || at < 0 || text.indexOf(old, at + 1) >= 0) {
        throw new IllegalArgumentException("old phải khớp đúng một lần trong " + path + "; lấy lại đoạn code bằng get_snippet");
      }
      String out = text.substring(0, at) + now + text.substring(at + old.length());
      Files.writeString(f, crlf ? out.replace("\n", "\r\n") : out); // keep the file's line endings: no whole-file diff
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return "Đã sửa " + repo + ":" + path + scopeWarning(s, repo, path);
  }

  /** FR-34 + NFR-06: inside the ticket worktree only, never a secret file, only while the ticket is in development. */
  private Path editable(AgentSession s, String repo, String path) {
    if (tasks.detail(s.project, s.taskId).task().status() != TaskStatus.IN_DEV) {
      throw new IllegalArgumentException("Chỉ sửa code khi ticket đang triển khai");
    }
    RepoView w = worktree(s, repo);
    if (w == null) {
      throw new NoSuchElementException(repo + ": chưa có worktree");
    }
    Path root = Path.of(w.worktree()).toAbsolutePath().normalize();
    Path f = root.resolve(safePath(path)).normalize();
    if (!f.startsWith(root) || f.startsWith(root.resolve(".git"))) {
      throw new IllegalArgumentException("Đường dẫn phải nằm trong worktree của ticket");
    }
    return f;
  }

  private String scopeWarning(AgentSession s, String repo, String path) {
    RuleService.ScopeReport r = rules.scope(s.project, repo, List.of(path));
    return r.clean() ? "" : ". Lưu ý: file thuộc thư mục cấm/DB schema/dependency, commit sẽ chờ PMO duyệt";
  }

  /** NFR-10: repo, service and rule of a tool call for the audit log; the service comes from the file path when there is one. */
  String[] auditFields(AgentSession s, String name, JsonNode args) {
    String repo = args.path("repo").asText(null);
    String path = args.hasNonNull("path") ? args.path("path").asText() : args.path("file").asText(null);
    String service = null;
    if (repo != null) {
      try {
        service = rules.serviceOf(s.project, repo, path);
      } catch (RuntimeException e) {
        // unknown repo: the tool call already failed and says so
      }
    }
    String rule = switch (name) {
      case "write_file", "edit_file" -> "scope + chặn file bí mật (FR-32, FR-34)";
      case "commit" -> "git.commit_template, scope";
      case "run_quality_gate" -> "quality: tách lớp, phân tích tĩnh, unit test";
      case "set_status" -> "luồng 8 trạng thái, cổng duyệt PMO";
      default -> "lọc bí mật, ngữ cảnh 2–3 tầng, không nguyên file (FR-34/35)";
    };
    return new String[] {repo, service, rule};
  }

  /** Relative path inside a repo, never a secret file (refused before any read, FR-34). */
  static String safePath(String path) {
    String p = path.replace('\\', '/').replaceAll("^\\./", "");
    if (p.isBlank() || p.startsWith("/") || p.matches("^[A-Za-z]:.*") || List.of(p.split("/")).contains("..")) {
      throw new IllegalArgumentException("Đường dẫn phải là đường dẫn tương đối trong repo: " + path);
    }
    if (SecretFiles.isSecret(p)) {
      throw new IllegalArgumentException("File bí mật, DevMap không bao giờ mở: " + path);
    }
    return p;
  }

  private RepoView worktree(AgentSession s, String repo) {
    return dev.view(s.project, s.taskId).repos().stream().filter(r -> r.repo().equals(repo) && r.head() != null).findFirst().orElse(null);
  }

  private RepoConfig repoConfig(AgentSession s, String repo) {
    Project p = projects.get(s.project);
    return p.config().repos().stream().filter(r -> r.name().equals(repo)).findFirst()
        .orElseThrow(() -> new NoSuchElementException("Dự án không có repo " + repo));
  }

  private String doc(AgentSession s, String name) {
    return tasks.readDoc(s.project, s.taskId, name).content();
  }

  String optDoc(AgentSession s, String name) {
    try {
      return doc(s, name);
    } catch (NoSuchElementException e) {
      return "";
    }
  }

  String mask(AgentSession s, String text) {
    return masker.mask(text, projects.get(s.project).config().secrets()).text();
  }

  private static String line(Node n) {
    return "- " + n.id() + " | " + n.kind() + (n.signature() == null || n.signature().isBlank() ? "" : " | " + n.signature())
        + (n.file() == null || n.file().isBlank() ? "" : " | " + n.repo() + ":" + n.file() + (n.line() > 0 ? ":" + n.line() : "")) + "\n";
  }

  private static String cap(String text) {
    return text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT) + "\n…(đã cắt, hỏi hẹp hơn)";
  }

  private static String text(JsonNode args, String field) {
    JsonNode v = args.get(field);
    if (v == null || v.isNull()) {
      throw new IllegalArgumentException("Thiếu tham số " + field);
    }
    return v.asText();
  }

  /** fields: "name:type:description", "name?:..." for optional. */
  private static Tool tool(String name, String description, Set<Role> roles, String... fields) {
    ObjectNode schema = JSON.createObjectNode().put("type", "object");
    ObjectNode props = schema.putObject("properties");
    ArrayNode required = JSON.createArrayNode();
    for (String f : fields) {
      String[] p = f.split(":", 3);
      boolean optional = p[0].endsWith("?");
      String key = optional ? p[0].substring(0, p[0].length() - 1) : p[0];
      ObjectNode prop = props.putObject(key).put("type", p[1]).put("description", p[2]);
      if (p[1].equals("array")) {
        prop.putObject("items").put("type", "string");
      }
      if (!optional) {
        required.add(key);
      }
    }
    if (!required.isEmpty()) {
      schema.set("required", required);
    }
    return new Tool(name, description, schema, roles);
  }
}
