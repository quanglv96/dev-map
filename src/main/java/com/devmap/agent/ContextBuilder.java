package com.devmap.agent;

import com.devmap.agent.AgentSession.Mode;
import com.devmap.agent.AgentSession.Role;
import com.devmap.config.ProjectService;
import com.devmap.graph.Graph;
import com.devmap.graph.Graph.Edge;
import com.devmap.graph.GraphService;
import com.devmap.rule.ConventionService;
import com.devmap.rule.RuleService;
import com.devmap.secret.SecretMasker;
import com.devmap.ticket.RequestCleaner;
import com.devmap.ticket.Task;
import com.devmap.ticket.TaskService;
import com.devmap.ticket.TaskStatus;
import com.devmap.ticket.TicketGitService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Builds what one agent session receives (FR-37, AIR-09 → AIR-11, token layers 1–3 and 5).
 * System prompt: static per project and role (the SRS verbatim instructions, role rules, layering rule, approved conventions), so
 * it is identical across sessions and served from the prompt cache. Prompt: spec.json and the ticket documents, a 2-hop service
 * neighbourhood with API signatures, and the PMO's question after local cleaning. Never the chat, never task.md, never code files.
 */
@Component
class ContextBuilder {

  record Context(String system, String prompt, List<String> files) {
  }

  private static final int MAX_DOC = 12_000;

  private final TaskService tasks;
  private final GraphService graphs;
  private final ConventionService conventions;
  private final RuleService rules;
  private final ProjectService projects;
  private final RequestCleaner cleaner;
  private final SecretMasker masker;
  private final TicketGitService dev;

  ContextBuilder(TaskService tasks, GraphService graphs, ConventionService conventions, RuleService rules, ProjectService projects,
      RequestCleaner cleaner, SecretMasker masker, TicketGitService dev) {
    this.dev = dev;
    this.tasks = tasks;
    this.graphs = graphs;
    this.conventions = conventions;
    this.rules = rules;
    this.projects = projects;
    this.cleaner = cleaner;
    this.masker = masker;
  }

  String system(String project, Role role) {
    StringBuilder sb = new StringBuilder();
    sb.append(resource("execution.md")).append("\n\n").append(resource("workflow.md")).append("\n\n")
        .append(resource("ponytail.md")).append("\n\n")
        .append(resource(role == Role.SA ? "sa.md" : "dev.md")).append("\n\n# Quy tắc kiến trúc của dự án ").append(project).append("\n\n")
        .append("- Tách lớp bắt buộc: Controller → Service → Repository; Controller không import/gọi Repository (quality gate chặn).\n")
        .append("- Mẫu commit: `").append(rules.projectDefault(project).git().commitTemplate()).append("`; mọi commit mang mã ticket.\n")
        .append("- Không push, không force-push; branch được bảo vệ: ").append(rules.protectedBranches(project)).append(".\n")
        .append("- Repo của dự án: ").append(projects.get(project).config().repos().stream().map(r -> r.name() + "@" + r.baseBranch())
            .toList()).append(".\n")
        .append("- Thư mục tham khảo (chỉ đọc, ngoài repo dự án; list_reference/read_reference): ")
        .append(projects.references(project).keySet()).append(".\n\n# Coding convention (conventions.md)\n\n");
    String conv = conventions.view(project).content();
    sb.append(conv == null || conv.isBlank() ? "(chưa có; theo phong cách code xung quanh)" : conv);
    return masker.mask(sb.toString(), projects.get(project).config().secrets()).text();
  }

  /** notes: what DevMap did before the session (pull, full scan, PMO's own edits). */
  Context build(String project, String id, Role role, Mode mode, String question, List<String> notes, int sessionNo) {
    Task t = tasks.detail(project, id).task();
    List<String> files = new ArrayList<>();
    StringBuilder sb = new StringBuilder("# Phiên mới · ").append(role).append(" · ").append(id).append(" · câu hỏi #").append(sessionNo)
        .append("\n\nKhông có lịch sử chat: mọi ngữ cảnh ở dưới và trong công cụ devmap.\n")
        .append("Ticket: ").append(t.type()).append(", trạng thái `").append(t.status().code()).append("`, repo ").append(t.repos())
        .append(dev.view(project, id).direct() ? ", code và commit thẳng trên nhánh nền (PMO chọn, không có branch ticket)"
            : ", branch `" + t.branch() + "`").append(", mở lại ").append(t.reopened()).append(" lần.\n");
    List<String> docs = new ArrayList<>(List.of("spec.json", "summary.md", "analysis.md", "plan.md"));
    if (role == Role.DEV) {
      docs.addAll(List.of("verify.md", "changes.md"));
    } else if (mode == Mode.VERIFY) {
      docs.add("changes.md");
    }
    for (String name : docs) {
      String content = doc(project, id, name);
      if (!content.isBlank()) {
        files.add(name);
        sb.append("\n## ").append(name).append("\n\n").append(content.length() > MAX_DOC
            ? content.substring(0, MAX_DOC) + "\n…(còn tiếp: get_ticket_docs)" : content).append('\n');
      }
    }
    String graph = graphContext(project, id);
    if (!graph.isEmpty()) {
      files.add("graph");
      sb.append("\n## Graph quanh module đích (tối đa 2 tầng, chỉ chữ ký)\n\n").append(graph);
    }
    if (!notes.isEmpty()) {
      sb.append("\n## DevMap đã làm trước phiên này\n\n");
      notes.forEach(n -> sb.append("- ").append(n).append('\n'));
    }
    sb.append("\n## Nhiệm vụ\n\n").append(task(role, mode, t)).append('\n');
    if (question != null && !question.isBlank()) {
      RequestCleaner.Cleaned c = cleaner.clean(question);
      sb.append("\n## Câu hỏi của PMO (đã làm sạch cục bộ)\n\n").append(c.summary().isBlank() ? question : c.summary()).append('\n');
      c.constraints().forEach(x -> sb.append("- Ràng buộc: ").append(x).append('\n'));
    }
    return new Context(system(project, role), masker.mask(sb.toString(), projects.get(project).config().secrets()).text(), files);
  }

  /**
   * FR-78: a session started by an event gets only the change (proposal or document diff), the document sections its refs name, and
   * the signatures around its location; never the whole ticket documents, never the chat. The system prompt is the role's usual
   * one, so the prompt cache still serves it.
   */
  Context buildSync(String project, String id, Role role, AgentService.Trigger trigger, List<String> notes, int sessionNo) {
    Task t = tasks.detail(project, id).task();
    List<String> files = new ArrayList<>(List.of("spec.json"));
    StringBuilder sb = new StringBuilder("# Phiên tự kích hoạt · ").append(role).append(" · ").append(id).append(" · sự kiện ")
        .append(trigger.kind()).append(" · #").append(sessionNo)
        .append("\n\nKhông có lịch sử chat, không có toàn bộ tài liệu ticket: chỉ phần thay đổi dưới đây. Cần thêm thì dùng ")
        .append("get_ticket_docs / get_snippet cho đúng đoạn cần, tối đa 2–3 tầng.\n")
        .append("Ticket: ").append(t.type()).append(", trạng thái `").append(t.status().code()).append("`, repo ").append(t.repos())
        .append(", branch `").append(t.branch()).append("`.\n");
    sb.append("\n## spec.json\n\n").append(clipDoc(doc(project, id, "spec.json"), 2000)).append('\n');
    String summary = doc(project, id, "summary.md");
    if (!summary.isBlank()) {
      files.add("summary.md");
      sb.append("\n## summary.md (tóm tắt, đã cập nhật sau mỗi lần đồng bộ)\n\n").append(clipDoc(summary, 3000)).append('\n');
    }
    sb.append("\n## Thay đổi cần xử lý\n\n")
        .append(trigger.change() == null || trigger.change().isBlank() ? "(không có)" : trigger.change())
        .append('\n');
    StringBuilder refs = new StringBuilder();
    for (String ref : trigger.refs()) {
      String section = section(project, id, ref);
      if (!section.isBlank()) {
        refs.append("\n### ").append(ref).append("\n\n").append(section).append('\n');
        files.add(ref);
      }
    }
    if (!refs.isEmpty()) {
      sb.append("\n## Đoạn tài liệu liên quan (theo refs)\n").append(refs);
    }
    String code = locationContext(project, trigger.location());
    if (!code.isBlank()) {
      files.add("graph");
      sb.append("\n## Chữ ký code liên quan (theo location, 1 tầng)\n\n").append(code);
    }
    if (!notes.isEmpty()) {
      sb.append("\n## DevMap đã làm trước phiên này\n\n");
      notes.forEach(n -> sb.append("- ").append(n).append('\n'));
    }
    sb.append("\n## Nhiệm vụ\n\n").append(trigger.task()).append(" Luôn ghi summary.md cuối phiên (đọc bản hiện tại bằng get_ticket_docs, "
        + "chỉ sửa phần của mình).\n");
    return new Context(system(project, role), masker.mask(sb.toString(), projects.get(project).config().secrets()).text(), files);
  }

  /** "analysis.md#AC-2": the heading or line naming AC-2 and what follows it up to the next heading of that level (max 40 lines). */
  private String section(String project, String id, String ref) {
    int hash = ref.indexOf('#');
    if (hash < 0) {
      return ""; // a whole document is never sent (FR-78)
    }
    String name = ref.substring(0, hash).strip();
    String anchor = ref.substring(hash + 1).strip().toLowerCase(java.util.Locale.ROOT);
    if (!com.devmap.ticket.TaskRepository.isDocName(name) || name.equals("task.md") || anchor.isEmpty()) {
      return "";
    }
    String[] lines = doc(project, id, name).split("\n", -1);
    for (int i = 0; i < lines.length; i++) {
      if (!lines[i].toLowerCase(java.util.Locale.ROOT).contains(anchor)) {
        continue;
      }
      StringBuilder out = new StringBuilder(lines[i]).append('\n');
      boolean heading = lines[i].startsWith("#");
      int level = heading ? lines[i].indexOf(' ') : 0;
      for (int j = i + 1; j < lines.length && j < i + 40; j++) {
        int l = lines[j].startsWith("#") ? lines[j].indexOf(' ') : 0;
        // a heading runs to the next heading of its level; a list line or table row only takes its indented sub-lines
        if (heading ? l > 0 && l <= level : lines[j].isBlank() || !Character.isWhitespace(lines[j].charAt(0))) {
          break;
        }
        out.append(lines[j]).append('\n');
      }
      return out.toString();
    }
    return "";
  }

  /** Signatures of the file the location names, and the graph nodes next to its method (depth 1). */
  private String locationContext(String project, com.devmap.proposal.Proposal.Location loc) {
    if (loc == null || loc.repo() == null || loc.file() == null) {
      return "";
    }
    try {
      Graph g = graphs.graph(project);
      StringBuilder sb = new StringBuilder();
      g.nodesIn(loc.repo(), loc.file()).stream().limit(40).forEach(n -> sb.append("- ").append(n.id()).append(" | ")
          .append(n.signature() == null ? n.kind() : n.signature()).append('\n'));
      if (loc.signature() != null && !loc.signature().isBlank()) {
        List<Graph.Node> hits = g.search(loc.signature(), 3);
        if (!hits.isEmpty()) {
          Graph.Subgraph sub = g.neighborhood(hits.stream().map(Graph.Node::id).toList(), 1, Graph.Direction.BOTH, 30);
          sub.edges().forEach(e -> sb.append("- ").append(e.from()).append(" -").append(e.type()).append("-> ").append(e.to())
              .append('\n'));
        }
      }
      return sb.toString();
    } catch (RuntimeException e) {
      return ""; // no index: the agent can still search_graph
    }
  }

  private static String clipDoc(String text, int max) {
    return text.length() <= max ? text : text.substring(0, max) + "\n…(còn tiếp: get_ticket_docs)";
  }

  private static String task(Role role, Mode mode, Task t) {
    if (mode == Mode.VERIFY) {
      return "KIỂM CHỨNG: Dev báo xong. Đối chiếu AC với diff ở mọi repo, ghi verify.md, rồi set_status wait_confirm hoặc implement.";
    }
    if (role == Role.DEV && t.status() != TaskStatus.READY && t.status() != TaskStatus.IMPLEMENT) {
      return "PMO CHƯA CHỐT giải pháp: chỉ trả lời câu hỏi và ghi phát hiện/đề xuất bằng write_proposal (FR-73). Không sửa code, "
          + "không commit: DevMap từ chối mọi thao tác sửa code trước khi chốt.";
    }
    if (role == Role.DEV) {
      return "TRIỂN KHAI theo plan.md đã chốt, test từ AC, quality gate, commit, rồi set_status verify. Phát hiện bug hay cần đổi giải "
          + "pháp thì ghi write_proposal (SA tự cập nhật tài liệu), không tự đổi phạm vi.";
    }
    String what = switch (t.status()) {
      case NEW, ANALYSIS -> "PHÂN TÍCH theo action_type trong spec.json; hỏi lại nếu mơ hồ; đủ rồi thì ghi analysis.md, plan.md, "
          + "summary.md và set_status wait_approval.";
      default -> "Trả lời câu hỏi của PMO; cập nhật tài liệu nếu PMO đổi yêu cầu.";
    };
    // a follow-up question must not rebuild the impact table: that is the graph sweep that costs tokens on every answer
    String known = t.repos().isEmpty() ? "" : " Service ảnh hưởng đã xác định " + t.repos()
        + " (bảng trong plan.md/summary.md): không quét lại graph, chỉ tra điểm mới PMO nêu; set_repos chỉ khi phạm vi đổi.";
    return what + known + " Luôn ghi summary.md cuối phiên.";
  }

  /** Token layer 2: services of the ticket, their service-level dependencies 2 hops out, and their API signatures. */
  private String graphContext(String project, String id) {
    Graph g = graphs.graph(project);
    List<String> seeds = graphs.ticketServices(project, id);
    if (seeds.isEmpty()) {
      return "";
    }
    Graph.Subgraph level = g.serviceLevel();
    Set<String> reached = new LinkedHashSet<>(seeds);
    Set<Edge> shown = new LinkedHashSet<>();
    for (int hop = 0; hop < 2; hop++) {
      for (Edge e : level.edges()) {
        if (reached.contains(e.from()) || reached.contains(e.to())) {
          shown.add(e);
        }
      }
      shown.forEach(e -> {
        reached.add(e.from());
        reached.add(e.to());
      });
    }
    StringBuilder sb = new StringBuilder("Service của ticket: ").append(seeds).append('\n');
    shown.stream().limit(40).forEach(e -> sb.append("- ").append(e.from()).append(" -").append(e.type()).append("/").append(e.confidence())
        .append("-> ").append(e.to()).append('\n'));
    for (String svc : seeds) {
      g.serviceDetail(svc).nodes().stream().filter(n -> n.kind().equals("api") && n.service() != null && svc.equals("svc:" + n.service()))
          .limit(30).forEach(n -> sb.append("- ").append(n.id()).append(" | ").append(n.signature()).append('\n'));
    }
    if (g.nodeCount() <= seeds.size() * 3 && shown.isEmpty()) {
      sb.append("(Graph chưa có chỉ mục chữ ký: dùng search_graph; PMO có thể bật \"Quét toàn bộ dự án\" cho một câu hỏi.)\n");
    }
    return sb.toString();
  }

  private String doc(String project, String id, String name) {
    try {
      return tasks.readDoc(project, id, name).content();
    } catch (NoSuchElementException e) {
      return "";
    }
  }

  private static String resource(String name) {
    try {
      // The app's own class loader: agent pool threads may carry the system loader, which cannot see BOOT-INF/classes in the jar
      return new ClassPathResource("prompts/" + name, ContextBuilder.class.getClassLoader()).getContentAsString(StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Thiếu prompts/" + name + ": " + e.getMessage(), e);
    }
  }
}
