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

  ContextBuilder(TaskService tasks, GraphService graphs, ConventionService conventions, RuleService rules, ProjectService projects,
      RequestCleaner cleaner, SecretMasker masker) {
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
        .append(resource(role == Role.SA ? "sa.md" : "dev.md")).append("\n\n# Quy tắc kiến trúc của dự án ").append(project).append("\n\n")
        .append("- Tách lớp bắt buộc: Controller → Service → Repository; Controller không import/gọi Repository (quality gate chặn).\n")
        .append("- Mẫu commit: `").append(rules.projectDefault(project).git().commitTemplate()).append("`; mọi commit mang mã ticket.\n")
        .append("- Không push, không force-push; branch được bảo vệ: ").append(rules.protectedBranches(project)).append(".\n")
        .append("- Repo của dự án: ").append(projects.get(project).config().repos().stream().map(r -> r.name() + "@" + r.baseBranch())
            .toList()).append(".\n\n# Coding convention (conventions.md)\n\n");
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
        .append(", branch `").append(t.branch()).append("`, mở lại ").append(t.reopened()).append(" lần.\n");
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

  private static String task(Role role, Mode mode, Task t) {
    if (mode == Mode.VERIFY) {
      return "KIỂM CHỨNG: Dev báo xong. Đối chiếu AC với diff ở mọi repo, ghi verify.md, rồi set_status awaiting_push hoặc in_dev.";
    }
    if (role == Role.DEV) {
      return "TRIỂN KHAI theo plan.md đã chốt, test từ AC, quality gate, commit, rồi set_status sa_verify.";
    }
    String what = switch (t.status()) {
      case NEW, SA_ANALYSIS -> "PHÂN TÍCH theo action_type trong spec.json; hỏi lại nếu mơ hồ; đủ rồi thì ghi analysis.md, plan.md, "
          + "summary.md và set_status awaiting_approval.";
      default -> "Trả lời câu hỏi của PMO; cập nhật tài liệu nếu PMO đổi yêu cầu.";
    };
    return what + " Luôn ghi summary.md cuối phiên.";
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
