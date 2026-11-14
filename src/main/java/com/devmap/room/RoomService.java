package com.devmap.room;

import com.devmap.agent.AgentService;
import com.devmap.agent.ChatMessage;
import com.devmap.agent.ChatMessage.Card;
import com.devmap.config.ProjectService;
import com.devmap.config.RoomConfig;
import com.devmap.orchestration.Orchestrator;
import com.devmap.orchestration.TriggerRules.Kind;
import com.devmap.proposal.Proposal;
import com.devmap.proposal.ProposalService;
import com.devmap.room.RoomRouter.Assignment;
import com.devmap.ticket.Task;
import com.devmap.ticket.TaskService;
import com.devmap.ticket.TaskService.Actor;
import com.devmap.ticket.TaskStatus;
import com.devmap.ticket.TicketGitService;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * The room of a ticket (FR-71 → FR-74, FR-90 → FR-93): the PMO writes once, the local router picks SA, Dev or both, and while
 * implementing a logic change or a bug becomes a structured proposal the orchestration hands to the right agent. The room only shows;
 * agents exchange through documents and events.
 */
@Service
public class RoomService {

  /** started: agent messages begun now. ask: "role" (Gửi SA / Gửi Dev) or "case" (B1–B4) when the router is not sure. */
  public record Sent(List<ChatMessage> started, String ask, List<String> options, String guess, List<String> evidence) {
  }

  public static final List<String> CASES = List.of("B1", "B2", "B3", "B4");

  private final TaskService tasks;
  private final AgentService agents;
  private final Orchestrator sync;
  private final ProposalService proposals;
  private final RoomRouter router;
  private final BugCaseGuesser guesser;
  private final ProjectService projects;
  private final TicketGitService dev;

  public RoomService(TaskService tasks, AgentService agents, Orchestrator sync, ProposalService proposals, RoomRouter router,
      BugCaseGuesser guesser, ProjectService projects, TicketGitService dev) {
    this.tasks = tasks;
    this.agents = agents;
    this.sync = sync;
    this.proposals = proposals;
    this.router = router;
    this.guesser = guesser;
    this.projects = projects;
    this.dev = dev;
  }

  /** to: "sa" / "dev" when the PMO pressed a button or named the role; bugCase: B1–B4 chosen after the room asked. */
  public Sent send(String project, String id, String text, String to, String bugCase) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("Chưa nhập nội dung");
    }
    Task t = tasks.detail(project, id).task();
    RoomConfig cfg = projects.get(project).config().room();
    if (bugCase != null && !bugCase.isBlank()) {
      return bug(project, id, t, text, bugCase.toUpperCase(Locale.ROOT), true);
    }
    RoomRouter.Route route = to == null || to.isBlank() ? router.route(text, t.status(), cfg)
        : routeTo(to.toUpperCase(Locale.ROOT), text, t.status(), cfg);
    if (route.ask()) {
      return new Sent(List.of(), "role", List.of("sa", "dev"), null, List.of());
    }
    Assignment bug = route.to().stream().filter(a -> a.kind() == RoomRouter.Kind.BUG).findFirst().orElse(null);
    if (bug != null && route.to().size() == 1) {
      BugCaseGuesser.Guess g = guesser.guess(project, id, text);
      if (!g.sure()) {
        return new Sent(List.of(), "case", CASES, g.kase(), g.evidence());
      }
      return bug(project, id, t, text, g.kase(), false);
    }
    agents.pmoMessage(project, id, text);
    List<ChatMessage> started = new ArrayList<>();
    for (Assignment a : route.to()) {
      if (a.role().equals("SA") && a.kind() != RoomRouter.Kind.ASK) {
        Proposal p = proposals.create(project, id, new ProposalService.Input("PMO", a.kind() == RoomRouter.Kind.BUG ? "BUG_FOUND"
            : "SOLUTION_CHANGE", a.text(), null, a.text(), "TECHNICAL_ONLY", List.of(), null));
        sync.enqueue(project, id, a.kind() == RoomRouter.Kind.BUG ? Kind.PMO_BUG : Kind.PMO_CHANGE,
            Map.of("proposal", p.id(), "source", "PMO"), p.origin(), 1);
      } else {
        started.add(agents.answer(project, id, a.role(), a.text(), false, false));
      }
    }
    return new Sent(started, null, List.of(), null, List.of());
  }

  private RoomRouter.Route routeTo(String role, String text, TaskStatus status, RoomConfig cfg) {
    if (!role.equals("SA") && !role.equals("DEV")) {
      throw new IllegalArgumentException("to phải là sa hoặc dev");
    }
    RoomRouter.Route auto = router.route("@" + role + " " + text, status, cfg);
    return new RoomRouter.Route(auto.to(), false);
  }

  /** FR-92 cases B1–B4 of a bug the PMO reports; chosen: the PMO pressed the button (no second guess). */
  private Sent bug(String project, String id, Task t, String text, String kase, boolean chosen) {
    if (!CASES.contains(kase)) {
      throw new IllegalArgumentException("Case phải là một trong " + CASES);
    }
    boolean implementing = t.status() == TaskStatus.IMPLEMENT || t.status() == TaskStatus.VERIFY;
    BugCaseGuesser.Guess g = guesser.guess(project, id, text);
    agents.pmoMessage(project, id, text);
    switch (kase) {
      case "B1" -> {
        Proposal p = proposals.create(project, id, new ProposalService.Input("PMO", "BUG_FOUND", text, location(g, text), text,
            "TECHNICAL_ONLY", List.of(), null));
        boolean clear = "B1".equals(g.kase()) && g.sure() && t.status() == TaskStatus.IMPLEMENT;
        // FR-92: cause clear (names code Dev just wrote) → Dev fixes first; SA always adds the case to the AC, and finds the
        // cause first when it is not clear (its plan.md update then reaches Dev as DOC_CHANGED)
        if (clear) {
          sync.enqueue(project, id, Kind.PMO_BUG_DEV, Map.of("proposal", p.id(), "source", "PMO", "case", "B1"), p.origin(), 1);
        }
        sync.enqueue(project, id, Kind.PMO_BUG, Map.of("proposal", p.id(), "source", "PMO", "case", "B1"), p.origin(), 1);
      }
      case "B2" -> {
        Proposal p = proposals.create(project, id, new ProposalService.Input("PMO", "SCOPE_CHANGE", text, location(g, text),
            "Sửa luôn bug ở code cũ trong task này: " + text, "SCOPE", List.of(), null));
        if (implementing) {
          proposals.resolve(project, id, p.id(), "NEEDS_PMO", "SCOPE", "SCOPE", "B2: mở rộng phạm vi, xử lý như A2", null, Actor.DEVMAP);
          sync.midChange(project, id, "PMO", "B2", "PMO muốn sửa luôn bug ở code cũ cùng khu vực: " + text, false, Map.of());
        } else {
          agents.answer(project, id, "sa", "Bổ sung vào phân tích: sửa luôn bug ở code cũ cùng khu vực: " + text, false, false);
        }
      }
      case "B3" -> {
        String other = g.other();
        if (other == null) {
          throw new IllegalArgumentException("Không đoán được task đã Done nào; chọn \"Tạo task mới\" hoặc báo bug ở trang task đó");
        }
        sync.reportAfterDeploy(project, other, "bug", text);
        agents.card(project, id, Card.notice("info", "Bug thuộc " + other + " (đã Done): đã mở lại " + other + " bằng \"Báo bug\", chạy "
            + "riêng; task này không đổi."));
        overlap(project, id, other);
      }
      default -> {
        TaskService.Created c = tasks.create(project, "BUG", "", "", text);
        agents.card(project, id, Card.notice("info", "Bug không thuộc task nào: đã tạo task " + c.task().id() + "; task này không đổi."));
        try {
          agents.analyzeNew(project, c.task().id());
        } catch (RuntimeException e) {
          // the new ticket waits in New; the PMO starts SA from its page
        }
      }
    }
    tasks.note(project, id, Actor.PMO, "Báo bug · case " + kase + (chosen ? " (PMO chọn)" : " (DevMap đoán: " + g.evidence() + ")"));
    return new Sent(List.of(), null, List.of(), kase, g.evidence());
  }

  /** FR-92 B3: both tickets touch the same files → flag both and ask the PMO which goes first. */
  private void overlap(String project, String id, String other) {
    Set<String> mine = files(project, id);
    mine.retainAll(files(project, other));
    if (mine.isEmpty()) {
      return;
    }
    for (String[] pair : List.of(new String[] {id, other}, new String[] {other, id})) {
      tasks.flags(project, pair[0], f -> {
        Set<String> ids = new LinkedHashSet<>(f.fileOverlap());
        ids.add(pair[1]);
        return f.withFileOverlap(List.copyOf(ids));
      }, Actor.DEVMAP, "Chạm cùng file với " + pair[1] + ": " + mine);
      agents.card(project, pair[0], Card.choice("Task " + pair[0] + " và " + pair[1] + " chạm cùng file " + mine
          + ". Làm task nào trước? Task còn lại tạm dừng ở điểm an toàn.", List.of("priority:" + id, "priority:" + other)));
    }
  }

  /** The PMO's answer to the file-overlap question: the other ticket stops at a safe point and its sync pauses. */
  public void priority(String project, String id, String first) {
    Task t = tasks.detail(project, id).task();
    if (!t.flags().fileOverlap().contains(first) && !first.equals(id)) {
      throw new IllegalArgumentException("Không có chồng file giữa " + id + " và " + first);
    }
    List<String> group = new ArrayList<>(t.flags().fileOverlap());
    group.add(id);
    for (String other : group) {
      if (other.equals(first)) {
        tasks.flags(project, other, f -> f.withSyncPaused(false), Actor.PMO, "PMO ưu tiên làm task này trước");
        sync.kick(project, other);
        continue;
      }
      agents.stopDev(project, other, "PMO ưu tiên " + first + " (chạm cùng file)", projects.get(project).config().room().safeStopSeconds());
      List<String> wip = dev.wipCommit(project, other, "PMO ưu tiên " + first, Actor.DEVMAP);
      tasks.flags(project, other, f -> f.withSyncPaused(true), Actor.PMO, "Tạm dừng ở điểm an toàn: PMO ưu tiên " + first
          + (wip.isEmpty() ? "" : ", WIP " + wip));
      agents.card(project, other, Card.notice("warn", "PMO ưu tiên " + first + ": task này tạm dừng ở điểm an toàn"
          + (wip.isEmpty() ? "" : " (WIP " + wip + ")") + "; bật lại đồng bộ khi " + first + " xong."));
    }
  }

  /** PMO decides a proposal (FR-76); needs_pmo clears once nothing waits for the PMO any more. */
  public Proposal decide(String project, String id, String proposalId, String status, String note) {
    String st = status == null ? "" : status.toUpperCase(Locale.ROOT);
    if (!st.equals("APPLIED") && !st.equals("REJECTED")) {
      throw new IllegalArgumentException("PMO chọn APPLIED hoặc REJECTED");
    }
    Proposal p = proposals.resolve(project, id, proposalId, st, null, null, note == null || note.isBlank() ? "PMO quyết: " + st
        : "PMO: " + note, null, Actor.PMO);
    if (proposals.list(project, id).stream().noneMatch(x -> x.status().equals("NEEDS_PMO"))) {
      tasks.flags(project, id, f -> f.withNeedsPmo(null), Actor.PMO, null);
    }
    agents.card(project, id, Card.notice("info", "PMO quyết đề xuất " + proposalId + ": " + st + (note == null || note.isBlank() ? ""
        : " – " + note)));
    return p;
  }

  public List<Proposal> proposals(String project, String id) {
    return proposals.list(project, id);
  }

  /** Files the ticket changed, from changes.md ("File sửa" list). */
  private Set<String> files(String project, String id) {
    Set<String> out = new LinkedHashSet<>();
    String text;
    try {
      text = tasks.readDoc(project, id, "changes.md").content();
    } catch (NoSuchElementException e) {
      return out;
    }
    boolean in = false;
    for (String line : text.split("\n")) {
      if (line.startsWith("- ")) {
        in = line.startsWith("- File sửa");
        continue;
      }
      if (in && line.strip().startsWith("- ")) {
        out.add(line.strip().substring(2).strip());
      }
    }
    return out;
  }

  private static Proposal.Location location(BugCaseGuesser.Guess g, String text) {
    List<String> files = BugCaseGuesser.tokens(text).stream().filter(x -> x.contains(".")).toList();
    List<String> names = BugCaseGuesser.tokens(text).stream().filter(x -> !x.contains(".")).toList();
    return files.isEmpty() && names.isEmpty() ? null
        : new Proposal.Location(null, files.isEmpty() ? null : files.get(0), names.isEmpty() ? null : names.get(0));
  }
}
