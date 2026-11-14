package com.devmap.orchestration;

import com.devmap.agent.AgentService;
import com.devmap.agent.AgentService.SessionEnded;
import com.devmap.agent.ChatMessage.Card;
import com.devmap.config.Project;
import com.devmap.config.ProjectService;
import com.devmap.config.RepoConfig;
import com.devmap.config.RoomConfig;
import com.devmap.orchestration.TriggerRules.Kind;
import com.devmap.proposal.Proposal;
import com.devmap.proposal.ProposalService;
import com.devmap.ticket.Task;
import com.devmap.ticket.TaskService;
import com.devmap.ticket.TaskService.Actor;
import com.devmap.ticket.TaskStatus;
import com.devmap.ticket.TicketGitService;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Automatic sync between agents (FR-77 → FR-84, FR-90 → FR-96). Agents never talk to each other: a proposal or a document change
 * becomes a durable event of the ticket, the trigger table names who reacts, and the reacting session gets only the change. Per
 * ticket everything runs in one lane (finished sessions first, then the next event), so two sessions never edit a document at the
 * same time; tickets run side by side within the agent limit. After every SA session that changed plan.md / analysis.md while
 * implementing, the hard rule classifies the impact: technical goes on, AC / business rule / scope stops Dev and goes to the PMO.
 */
@Service
public class Orchestrator {

  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
  private static final Set<String> DEV_TYPES = Set.of("BUG_FOUND", "SOLUTION_CHANGE", "SCOPE_CHANGE");
  private static final int SUMMARY_LINES = 10;

  private final TaskService tasks;
  private final ProposalService proposals;
  private final AgentService agents;
  private final TicketGitService dev;
  private final ProjectService projects;
  private final EventRepository events;
  private final Executor lane;
  private final Set<String> draining = ConcurrentHashMap.newKeySet();
  private final Set<String> again = ConcurrentHashMap.newKeySet();
  private final Map<String, Queue<SessionEnded>> finished = new ConcurrentHashMap<>();

  @Autowired
  public Orchestrator(TaskService tasks, ProposalService proposals, AgentService agents, TicketGitService dev, ProjectService projects,
      EventRepository events) {
    this(tasks, proposals, agents, dev, projects, events, Executors.newCachedThreadPool(r -> {
      Thread t = new Thread(r, "devmap-sync");
      t.setDaemon(true);
      return t;
    }));
  }

  /** lane: where each ticket's lane runs (tests pass Runnable::run to run everything in the caller's thread). */
  public Orchestrator(TaskService tasks, ProposalService proposals, AgentService agents, TicketGitService dev, ProjectService projects,
      EventRepository events, Executor lane) {
    this.tasks = tasks;
    this.proposals = proposals;
    this.agents = agents;
    this.dev = dev;
    this.projects = projects;
    this.events = events;
    this.lane = lane;
  }

  // ---------------------------------------------------------------- queue

  /** FR-82: stores the event first (durable), then runs the ticket's lane. */
  public SyncEvent enqueue(String project, String id, Kind kind, Map<String, String> payload, String origin, int round) {
    Map<String, String> clean = new LinkedHashMap<>();
    payload.forEach((k, v) -> {
      if (v != null) {
        clean.put(k, v);
      }
    });
    SyncEvent e = new SyncEvent(UUID.randomUUID().toString().substring(0, 8), kind, "PENDING", now(), null, null, clean, origin, round,
        null);
    events.append(tasks.folder(project, id), e);
    boolean paused = tasks.detail(project, id).task().flags().syncPaused();
    tasks.note(project, id, Actor.DEVMAP, "Sự kiện " + kind + " (" + e.id() + ") → " + kind.role
        + (paused ? ", đồng bộ đang tạm dừng: ghi lại, chạy khi PMO bật lại" : ""));
    kick(project, id);
    return e;
  }

  public List<SyncEvent> list(String project, String id) {
    return events.read(tasks.folder(project, id));
  }

  /** Runs the ticket's lane unless it already runs; then it loops once more. */
  public void kick(String project, String id) {
    String key = project + "/" + id;
    again.add(key);
    if (draining.contains(key)) {
      return;
    }
    lane.execute(() -> drain(project, id));
  }

  private void drain(String project, String id) {
    String key = project + "/" + id;
    if (!draining.add(key)) {
      return;
    }
    try {
      while (again.remove(key)) {
        step(project, id, key);
      }
    } catch (RuntimeException e) {
      safeNote(project, id, "Lỗi hàng đợi đồng bộ: " + e.getMessage());
    } finally {
      draining.remove(key);
    }
    if (again.contains(key)) {
      kick(project, id); // something arrived between the last loop and the release
    }
  }

  private void step(String project, String id, String key) {
    while (true) {
      SessionEnded done = finished.getOrDefault(key, new ConcurrentLinkedQueue<>()).poll();
      if (done != null) {
        ended(done);
        continue;
      }
      Task t;
      try {
        t = tasks.detail(project, id).task();
      } catch (NoSuchElementException | IllegalStateException e) {
        return;
      }
      List<SyncEvent> all = events.read(tasks.folder(project, id));
      if (all.stream().anyMatch(e -> e.status().equals("RUNNING"))) {
        return; // its SessionEnded continues the lane
      }
      SyncEvent next = all.stream().filter(e -> e.status().equals("PENDING")).findFirst().orElse(null);
      if (next == null) {
        return;
      }
      if (t.status().isClosed()) {
        mark(project, id, next, "SKIPPED", "ticket đã " + t.status().code());
        continue;
      }
      if (t.flags().syncPaused()) {
        return; // FR-83: recorded, runs when the PMO turns sync back on
      }
      if (!next.kind().when.contains(t.status())) {
        mark(project, id, next, "SKIPPED", "ticket ở " + t.status().code() + ", luật " + next.kind() + " chỉ chạy ở "
            + next.kind().when.stream().map(TaskStatus::code).toList());
        continue;
      }
      if (agents.busy(project, id, next.kind().role)) {
        return; // the running session's SessionEnded continues the lane
      }
      RoomConfig room = room(project);
      long lastHour = all.stream().filter(e -> e.started() != null && LocalDateTime.parse(e.started(), TIME)
          .isAfter(LocalDateTime.now().minusHours(1))).count();
      if (lastHour >= room.maxAutoSessionsPerHour()) {
        String why = "Vượt trần " + room.maxAutoSessionsPerHour() + " phiên tự kích hoạt mỗi giờ";
        tasks.flags(project, id, f -> f.withSyncPaused(true).withNeedsPmo(why), Actor.DEVMAP, why + ": tạm dừng đồng bộ (FR-81)");
        agents.card(project, id, Card.notice("important", why + ". Đồng bộ tự động tạm dừng; bật lại khi đã xem xét."));
        return;
      }
      SyncEvent running = mark(project, id, next, "RUNNING", null);
      try {
        agents.trigger(project, id, next.kind().role, trigger(project, id, running));
      } catch (RuntimeException e) {
        mark(project, id, running, "FAILED", e.getMessage());
      }
    }
  }

  private SyncEvent mark(String project, String id, SyncEvent e, String status, String note) {
    String at = now();
    return events.update(tasks.folder(project, id), e.id(), x -> x.with(status, status.equals("RUNNING") ? at : x.started(),
        status.equals("RUNNING") ? null : at, note == null ? x.note() : note));
  }

  private AgentService.Trigger trigger(String project, String id, SyncEvent e) {
    Proposal p = e.get("proposal") == null ? null : proposals.get(project, id, e.get("proposal"));
    String change = switch (e.kind().context) {
      case PROPOSAL -> render(p);
      case DOC_DIFF -> docDiff(project, id, e);
      case TEXT -> e.get("text");
    };
    return new AgentService.Trigger(e.id(), e.kind().name(), e.kind().label, change, p == null ? List.of() : p.refs(),
        p == null ? null : p.location(), e.kind().task);
  }

  private static String render(Proposal p) {
    if (p == null) {
      return "(không có đề xuất)";
    }
    StringBuilder sb = new StringBuilder("Đề xuất `").append(p.id()).append("` (mạch `").append(p.origin()).append("`, vòng ")
        .append(p.round()).append(")\n- Tác giả: ").append(p.author()).append("\n- Loại: ").append(p.type()).append("\n- Ảnh hưởng: ")
        .append(p.affects()).append("\n- Trạng thái: ").append(p.status()).append("\n- Tóm tắt: ").append(p.summary()).append('\n');
    if (p.location() != null) {
      sb.append("- Vị trí: ").append(p.location().repo()).append(':').append(p.location().file()).append(' ')
          .append(p.location().signature() == null ? "" : p.location().signature()).append('\n');
    }
    if (p.proposal() != null && !p.proposal().isBlank()) {
      sb.append("- Đề xuất: ").append(p.proposal()).append('\n');
    }
    if (p.note() != null) {
      sb.append("- Phản hồi: ").append(p.note()).append('\n');
    }
    if (!p.refs().isEmpty()) {
      sb.append("- Refs: ").append(p.refs()).append('\n');
    }
    return sb.toString();
  }

  private String docDiff(String project, String id, SyncEvent e) {
    String doc = e.get("doc");
    int from = Integer.parseInt(e.payload().getOrDefault("from", "0"));
    int to = Integer.parseInt(e.payload().getOrDefault("to", "0"));
    StringBuilder sb = new StringBuilder();
    if (e.get("text") != null) {
      sb.append(e.get("text")).append("\n\n");
    }
    if (doc == null || to == 0) {
      return sb.toString();
    }
    sb.append("Diff ").append(doc).append(" v").append(from).append(" → v").append(to).append(":\n\n```diff\n")
        .append(from == 0 ? tasks.version(project, id, doc, to) : tasks.diff(project, id, doc, from, to)).append("\n```\n");
    if (e.kind() == Kind.REAPPROVED) {
      String plan = tasks.version(project, id, doc, to);
      int at = plan.indexOf("Ảnh hưởng tới code đang làm");
      if (at >= 0) {
        sb.append("\n").append(plan, Math.max(0, plan.lastIndexOf('\n', at)), Math.min(plan.length(), at + 3000)).append('\n');
      }
    }
    return sb.toString();
  }

  // ---------------------------------------------------------------- session ends

  @EventListener
  public void onSessionEnded(SessionEnded e) {
    finished.computeIfAbsent(e.project() + "/" + e.taskId(), k -> new ConcurrentLinkedQueue<>()).add(e);
    kick(e.project(), e.taskId());
  }

  private void ended(SessionEnded e) {
    String project = e.project();
    String id = e.taskId();
    SyncEvent ev = e.trigger() == null ? null : events.read(tasks.folder(project, id)).stream()
        .filter(x -> x.id().equals(e.trigger())).findFirst().orElse(null);
    try {
      if (e.role().equals("SA")) {
        afterSa(e, ev);
      }
      if (ev != null) {
        summary(project, id, ev, e.ok());
      }
    } catch (RuntimeException ex) {
      safeNote(project, id, "Lỗi xử lý sau phiên #" + e.messageId() + ": " + ex.getMessage());
    } finally {
      if (ev != null) {
        mark(project, id, ev, e.ok() ? "DONE" : "FAILED", "phiên #" + e.messageId());
      }
    }
  }

  /** FR-79 / FR-80: what an SA session changed, classified by the hard rule, and who has to react. */
  private void afterSa(SessionEnded e, SyncEvent ev) {
    String project = e.project();
    String id = e.taskId();
    Task t = tasks.detail(project, id).task();
    if (ev != null && ev.kind() == Kind.REANALYZE) {
      afterReanalyze(e, ev, t);
      return;
    }
    Map<String, int[]> changed = changedDocs(project, id, e.startVersions());
    boolean verifying = e.mode().equals("VERIFY");
    boolean implementing = e.startStatus() == TaskStatus.IMPLEMENT || e.startStatus() == TaskStatus.VERIFY;
    Proposal p = ev == null || ev.get("proposal") == null ? null : proposals.get(project, id, ev.get("proposal"));
    if (!implementing || changed.isEmpty() && t.repos().equals(e.startRepos())) {
      if (verifying && t.status() == TaskStatus.IMPLEMENT) {
        enqueue(project, id, Kind.VERIFY_FAILED, Map.of("text", verifyList(project, id), "source", "SA"), null, 0);
      } else if (p != null && implementing && p.author().equals("DEV") && t.status() == TaskStatus.IMPLEMENT) {
        enqueue(project, id, Kind.PROPOSAL_RESOLVED, Map.of("proposal", p.id(), "source", "SA"), p.origin(), p.round());
      }
      return;
    }
    ImpactRules.Verdict hard = ImpactRules.classify(old(project, id, "analysis.md", e), doc(project, id, "analysis.md"),
        old(project, id, "plan.md", e), doc(project, id, "plan.md"), repoNames(project), e.startRepos(), t.repos());
    String agent = p == null ? "TECHNICAL_ONLY" : p.agentAffects();
    String affects = Proposal.heavier(hard.affects(), agent);
    String source = ev == null ? (verifying ? "SA" : "PMO") : ev.payload().getOrDefault("source", "SA");
    String given = ev != null && ev.get("case") != null ? ev.get("case") : affects.equals("TECHNICAL_ONLY") ? "A1" : "A2";
    String kase = given.startsWith("B") && !affects.equals("TECHNICAL_ONLY") ? "B1→A2" : given;
    String versions = versions(changed);
    tasks.note(project, id, Actor.DEVMAP, "Thay đổi giữa chừng · nguồn " + source + " · case " + kase + " · " + versions
        + " · " + affects + (hard.technical() ? "" : " (luật cứng: " + hard.reason() + ")"));
    String why = reason(source, p);
    if (affects.equals("TECHNICAL_ONLY")) {
      if (p != null && p.status().equals("OPEN")) {
        proposals.resolve(project, id, p.id(), "APPLIED", affects, null, null, null, Actor.DEVMAP);
      }
      changed.forEach((doc, v) -> agents.card(project, id, Card.doc("SA đã " + (kase.equals("A1") && source.equals("PMO") ? "bổ sung "
          : "cập nhật ") + doc + " (v" + v[1] + ") theo " + why + (t.status() == TaskStatus.IMPLEMENT && !verifying
              ? ", Dev tiếp tục" : ""), doc, v[0], v[1])));
      if (t.status() == TaskStatus.IMPLEMENT && !verifying) {
        String doc = changed.containsKey("plan.md") ? "plan.md" : changed.keySet().iterator().next();
        int[] v = changed.get(doc);
        enqueue(project, id, Kind.DOC_CHANGED, Map.of("doc", doc, "from", String.valueOf(v[0]), "to", String.valueOf(v[1]), "source",
            "SA"), p == null ? null : p.origin(), p == null ? 0 : p.round() + 1);
      } else if (verifying && t.status() == TaskStatus.IMPLEMENT) {
        enqueue(project, id, Kind.VERIFY_FAILED, Map.of("text", verifyList(project, id), "source", "SA"), null, 0);
      }
      return;
    }
    if (p != null) {
      proposals.resolve(project, id, p.id(), "NEEDS_PMO", affects, null, hard.technical() ? null : "Luật cứng: " + hard.reason(), null,
          Actor.DEVMAP);
    }
    midChange(project, id, source, kase, (hard.technical() ? "SA đánh giá " + affects : hard.reason()) + " (" + why + ")",
        hard.repoAdded(), changed);
  }

  /**
   * FR-91 / FR-94 / FR-96: an AC, business-rule or scope change while implementing. Dev stops safely and its work becomes a WIP commit;
   * then SA prepares the re-approval (what to keep, fix, drop). With room.skip_reapproval_for_pmo_changes, a PMO change that adds no
   * repo skips the approval and the ticket stays in IMPLEMENT.
   */
  public void midChange(String project, String id, String source, String kase, String reason, boolean repoAdded,
      Map<String, int[]> changed) {
    RoomConfig room = room(project);
    agents.stopDev(project, id, "thay đổi " + kase + ": " + reason, room.safeStopSeconds());
    List<String> wip = dev.wipCommit(project, id, kase + " " + reason, Actor.DEVMAP);
    boolean skip = room.skipReapprovalForPmoChanges() && source.equals("PMO") && !repoAdded;
    Task t = tasks.flags(project, id, f -> f.withMidChange(true).withNeedsPmo(skip ? null : "Thay đổi " + kase + " cần PMO chốt lại: "
        + reason), Actor.DEVMAP, "Dừng Dev an toàn" + (wip.isEmpty() ? "" : ", commit WIP " + wip) + " · " + reason);
    if (!skip && (t.status() == TaskStatus.IMPLEMENT || t.status() == TaskStatus.VERIFY)) {
      tasks.transition(project, id, TaskStatus.ANALYSIS.code(), Actor.DEVMAP);
    } else if (skip && t.status() == TaskStatus.VERIFY) {
      tasks.transition(project, id, TaskStatus.IMPLEMENT.code(), Actor.DEVMAP);
    }
    agents.card(project, id, Card.notice(skip ? "warn" : "important", "Thay đổi " + kase + " (" + reason + "). Dev đã dừng"
        + (wip.isEmpty() ? "" : ", commit WIP cục bộ " + wip + " (không push)") + ". " + (skip
            ? "Cấu hình bỏ chốt lại: SA cập nhật tài liệu rồi Dev làm tiếp, không qua Chờ PMO chốt."
            : "Ticket về Phân tích; SA chuẩn bị bản chốt lại.")));
    StringBuilder text = new StringBuilder("Nguồn: " + source + ", case " + kase + ". " + reason + "\n");
    if (!wip.isEmpty()) {
      text.append("Commit WIP: ").append(wip).append('\n');
    }
    changed.forEach((doc, v) -> text.append("\nDiff ").append(doc).append(" v").append(v[0]).append(" → v").append(v[1])
        .append(":\n```diff\n").append(v[0] == 0 ? "" : tasks.diff(project, id, doc, v[0], v[1])).append("\n```\n"));
    enqueue(project, id, Kind.REANALYZE, Map.of("text", text.toString(), "source", source, "case", kase, "skip", String.valueOf(skip),
        "repo_added", String.valueOf(repoAdded)), null, 0);
  }

  private void afterReanalyze(SessionEnded e, SyncEvent ev, Task t) {
    String project = e.project();
    String id = e.taskId();
    Map<String, int[]> changed = changedDocs(project, id, e.startVersions());
    boolean skip = Boolean.parseBoolean(ev.get("skip"));
    if (skip) {
      ImpactRules.Verdict hard = ImpactRules.classify("", "", old(project, id, "plan.md", e), doc(project, id, "plan.md"),
          repoNames(project), e.startRepos(), t.repos());
      if (!hard.repoAdded()) {
        tasks.flags(project, id, f -> f.withMidChange(false).withNeedsPmo(null), Actor.DEVMAP,
            "Đã áp dụng thay đổi của PMO, không chốt lại (room.skip_reapproval_for_pmo_changes)");
        changed.forEach((doc, v) -> agents.card(project, id, Card.doc("Đã áp dụng thay đổi của PMO, không chốt lại: " + doc + " v" + v[0]
            + " → v" + v[1], doc, v[0], v[1])));
        int[] v = changed.getOrDefault("plan.md", null);
        if (v != null) {
          enqueue(project, id, Kind.REAPPROVED, Map.of("doc", "plan.md", "from", String.valueOf(v[0]), "to", String.valueOf(v[1]),
              "source", "PMO"), null, 0);
        }
        return;
      }
      // a new repo or service still needs the PMO (FR-96)
      tasks.flags(project, id, f -> f.withNeedsPmo("Thay đổi thêm repo/service mới: cần PMO chốt lại"), Actor.DEVMAP, hard.reason()
          + ": vẫn phải chốt lại");
      if (t.status() == TaskStatus.IMPLEMENT) {
        tasks.transition(project, id, TaskStatus.ANALYSIS.code(), Actor.DEVMAP);
      }
      t = tasks.detail(project, id).task();
    }
    if (t.status() == TaskStatus.ANALYSIS && !doc(project, id, "plan.md").isBlank() && !doc(project, id, "analysis.md").isBlank()) {
      tasks.transition(project, id, TaskStatus.WAIT_APPROVAL.code(), Actor.DEVMAP); // SA forgot set_status: the PMO still decides
    }
    if (tasks.detail(project, id).task().status() == TaskStatus.WAIT_APPROVAL) {
      int v = tasks.detail(project, id).reapproval() == null ? 0 : tasks.detail(project, id).reapproval();
      changed.forEach((doc, x) -> agents.card(project, id, Card.doc("SA đã cập nhật " + doc + " (v" + x[1] + ") cho bản chốt lại", doc,
          x[0], x[1])));
      agents.card(project, id, Card.notice("important", "Chờ PMO chốt lại · v" + v + ": xem analysis.md (AC đổi) và plan.md mục "
          + "\"Ảnh hưởng tới code đang làm\" (giữ / sửa / bỏ)."));
    }
  }

  /** Approved again after a mid-change (WAIT_APPROVAL → IMPLEMENT): Dev gets only the plan diff since the previous approval. */
  public Task transition(String project, String id, String to) {
    Task before = tasks.detail(project, id).task();
    int planBefore = docVersion(project, id, "plan.md");
    Task moved = agents.transition(project, id, to);
    if (before.status() == TaskStatus.WAIT_APPROVAL && moved.status() == TaskStatus.IMPLEMENT) {
      Integer from = before.flags().approvedPlan();
      enqueue(project, id, Kind.REAPPROVED, Map.of("doc", "plan.md", "from", String.valueOf(from == null ? 0 : Math.min(from, planBefore)),
          "to", String.valueOf(planBefore), "source", "PMO"), null, 0);
    }
    return moved;
  }

  // ---------------------------------------------------------------- proposals, undo, pause, bug after deploy

  /** FR-76 card for every proposal; FR-77 Dev's findings go to SA; FR-81 the third round goes to the PMO instead. */
  @EventListener
  public void onProposal(ProposalService.Created c) {
    Proposal p = c.proposal();
    String who = switch (p.author()) {
      case "DEV" -> "Dev";
      case "SA" -> "SA";
      default -> "PMO";
    };
    agents.card(c.project(), c.taskId(), Card.proposal(who + " ghi đề xuất " + p.type() + " (" + p.affects() + "): " + p.summary(),
        p.id()));
    if (p.type().equals("QUESTION")) {
      agents.card(c.project(), c.taskId(), Card.notice("important", "Câu hỏi chờ PMO: " + p.summary()));
      return;
    }
    if (!p.author().equals("DEV") || !DEV_TYPES.contains(p.type())) {
      return;
    }
    int max = room(c.project()).maxRounds();
    if (p.round() > max) {
      loopGuard(c.project(), c.taskId(), p, max);
      return;
    }
    enqueue(c.project(), c.taskId(), Kind.DEV_PROPOSAL, Map.of("proposal", p.id(), "source", "DEV"), p.origin(), p.round());
  }

  private void loopGuard(String project, String id, Proposal p, int max) {
    String sa = proposals.list(project, id).stream()
        .filter(x -> x.origin().equals(p.origin()) && !x.id().equals(p.id()) && x.note() != null)
        .reduce((a, b) -> b).map(Proposal::note).orElse("(SA chưa ghi lý do)");
    List<String> views = List.of("Dev: " + p.summary() + (p.proposal() == null || p.proposal().isBlank() ? "" : " – " + p.proposal()),
        "SA: " + sa);
    proposals.resolve(project, id, p.id(), "NEEDS_PMO", null, null, "SA và Dev chưa thống nhất sau " + max + " vòng", views, Actor.DEVMAP);
    tasks.flags(project, id, f -> f.withNeedsPmo("SA và Dev chưa thống nhất sau " + max + " vòng: " + p.summary()), Actor.DEVMAP,
        "Chống vòng lặp (FR-81): dừng mạch " + p.origin() + " sau " + max + " vòng, chờ PMO quyết");
    agents.card(project, id, Card.notice("important", "SA và Dev chưa thống nhất sau " + max + " vòng về: " + p.summary()
        + "\n\n- " + views.get(0) + "\n- " + views.get(1) + "\n\nPMO quyết ở thẻ đề xuất."));
  }

  /** FR-80 / AC-38: the document goes back to version n; Dev is told when it is implementing. */
  public TaskService.Doc undo(String project, String id, String doc, int from, int to) {
    TaskService.Doc d = tasks.restore(project, id, doc, from, Actor.PMO);
    agents.markUndone(project, id, doc, to);
    agents.card(project, id, Card.notice("info", "PMO hoàn tác " + doc + " v" + to + " → nội dung v" + from + " (v" + d.versions() + ")"));
    if (tasks.detail(project, id).task().status() == TaskStatus.IMPLEMENT) {
      enqueue(project, id, Kind.UNDONE, Map.of("doc", doc, "from", String.valueOf(to), "to", String.valueOf(d.versions()), "source",
          "PMO"), null, 0);
    }
    return d;
  }

  /** FR-83: off keeps recording events; on runs what was recorded. Turning on also clears the hourly-cap stop. */
  public Task pause(String project, String id, boolean paused) {
    Task t = tasks.flags(project, id, f -> {
      Task.Flags out = f.withSyncPaused(paused);
      return !paused && f.needsPmo() != null && f.needsPmo().startsWith("Vượt trần") ? out.withNeedsPmo(null) : out;
    }, Actor.PMO, paused ? "Tạm dừng đồng bộ tự động" : "Bật lại đồng bộ tự động");
    if (!paused) {
      kick(project, id);
    }
    return t;
  }

  /** FR-77 / FR-10: PMO reports a bug on a Done ticket; it reopens and SA puts the right logic in analysis.md before Dev. */
  public Task reportAfterDeploy(String project, String id, String kind, String description) {
    agents.requireIdle(project, id);
    Task t = tasks.reopen(project, id, kind, description, Actor.PMO);
    List<String> bugs = tasks.detail(project, id).docs().stream().filter(d -> d.startsWith("bugs/")).toList();
    String entry = bugs.isEmpty() ? "" : bugs.get(bugs.size() - 1);
    agents.card(project, id, Card.notice("info", ("bug".equals(kind) ? "PMO báo bug sau deploy" : "PMO bổ sung logic") + " (" + entry
        + "): " + description));
    enqueue(project, id, Kind.BUG_AFTER_DEPLOY, Map.of("text", ("bug".equals(kind) ? "Báo bug " : "Bổ sung logic ") + entry + ":\n"
        + tasks.readDoc(project, id, entry).content(), "source", "PMO"), null, 0);
    return t;
  }

  // ---------------------------------------------------------------- PMO edits code by hand, restart

  /** FR-77: commits in an implementing ticket's worktrees that no Dev session made → SA records them (once per head). */
  public int scanPmoChanges() {
    int n = 0;
    for (Project p : projects.list()) {
      if (p.error() != null) {
        continue;
      }
      for (Task t : tasks.list(p.name())) {
        if ((t.status() != TaskStatus.IMPLEMENT && t.status() != TaskStatus.VERIFY) || agents.busy(p.name(), t.id())) {
          continue;
        }
        try {
          AgentService.PmoChange c = agents.pmoCodeChange(p.name(), t.id());
          if (c == null || list(p.name(), t.id()).stream().anyMatch(e -> c.fingerprint().equals(e.get("fingerprint")))) {
            continue;
          }
          dev.writeChanges(p.name(), t.id());
          agents.card(p.name(), t.id(), Card.notice("info", "Phát hiện PMO tự sửa code:\n" + c.text()));
          enqueue(p.name(), t.id(), Kind.PMO_CODE_CHANGE, Map.of("text", "Commit PMO tự làm:\n" + c.text(), "fingerprint", c.fingerprint(),
              "source", "PMO"), null, 0);
          n++;
        } catch (RuntimeException e) {
          // a repo that cannot be read now is checked again on the next round
        }
      }
    }
    return n;
  }

  /** FR-82: events that were running when DevMap stopped run again; then the PMO-edit watcher starts. */
  @EventListener(ApplicationReadyEvent.class)
  public void resume() {
    recover();
    var timer = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "devmap-sync-watch");
      t.setDaemon(true);
      return t;
    });
    // ponytail: polls every minute; a git hook or file watcher if a minute of delay ever matters
    timer.scheduleWithFixedDelay(() -> {
      try {
        scanPmoChanges();
      } catch (RuntimeException e) {
        // next round
      }
    }, 1, 1, TimeUnit.MINUTES);
  }

  /** Running → pending again (the session was lost with the process), then every ticket with pending events runs. */
  public int recover() {
    int n = 0;
    for (Project p : projects.list()) {
      if (p.error() != null) {
        continue;
      }
      for (Task t : tasks.list(p.name())) {
        try {
          for (SyncEvent e : list(p.name(), t.id())) {
            if (e.status().equals("RUNNING")) {
              events.update(tasks.folder(p.name(), t.id()), e.id(),
                  x -> x.with("PENDING", null, null, "chạy lại sau khi DevMap khởi động"));
              n++;
            }
          }
          if (list(p.name(), t.id()).stream().anyMatch(e -> e.status().equals("PENDING"))) {
            kick(p.name(), t.id());
          }
        } catch (RuntimeException e) {
          // a broken events.json stays for the PMO to see
        }
      }
    }
    return n;
  }

  // ---------------------------------------------------------------- helpers

  /** FR-84: one line per automatic sync in summary.md, so the next session starts from the right picture. */
  private void summary(String project, String id, SyncEvent ev, boolean ok) {
    String head = "## Đồng bộ tự động";
    String text = doc(project, id, "summary.md");
    String line = "- " + now().substring(0, 16) + " · " + ev.kind() + " → " + ev.kind().role + (ok ? "" : " (lỗi)")
        + (ev.get("case") == null ? "" : " · case " + ev.get("case"));
    int at = text.indexOf(head);
    List<String> lines = new ArrayList<>();
    String before = text;
    String after = "";
    if (at >= 0) {
      before = text.substring(0, at);
      String rest = text.substring(at + head.length());
      int next = rest.indexOf("\n## ");
      String body = next < 0 ? rest : rest.substring(0, next);
      after = next < 0 ? "" : rest.substring(next);
      body.lines().filter(l -> l.startsWith("- ")).forEach(lines::add);
    }
    lines.add(line);
    List<String> keep = lines.subList(Math.max(0, lines.size() - SUMMARY_LINES), lines.size());
    String out = (before.isBlank() ? "# Tóm tắt – " + id + "\n\n" : before.stripTrailing() + "\n\n") + head + "\n\n"
        + String.join("\n", keep) + "\n" + after;
    tasks.writeDoc(project, id, "summary.md", out, Actor.DEVMAP);
  }

  private String verifyList(String project, String id) {
    String v = doc(project, id, "verify.md");
    return v.length() <= 6000 ? v : v.substring(0, 6000) + "\n…(còn tiếp trong verify.md)";
  }

  private Map<String, int[]> changedDocs(String project, String id, Map<String, Integer> start) {
    Map<String, int[]> out = new LinkedHashMap<>();
    for (String doc : List.of("plan.md", "analysis.md")) {
      int before = start.getOrDefault(doc, 0);
      int now = docVersion(project, id, doc);
      if (now > before) {
        out.put(doc, new int[] {before, now});
      }
    }
    return out;
  }

  private static String versions(Map<String, int[]> changed) {
    return changed.isEmpty() ? "không đổi tài liệu" : String.join(", ", changed.entrySet().stream()
        .map(x -> x.getKey() + " v" + x.getValue()[0] + " → v" + x.getValue()[1]).toList());
  }

  private static String reason(String source, Proposal p) {
    if (p == null) {
      return "yêu cầu của " + (source.equals("DEV") ? "Dev" : source);
    }
    return switch (p.author()) {
      case "DEV" -> "đề xuất của Dev";
      case "PMO" -> p.type().equals("BUG_FOUND") ? "bug PMO báo" : "yêu cầu của PMO";
      default -> "đề xuất của SA";
    };
  }

  private String old(String project, String id, String doc, SessionEnded e) {
    int v = e.startVersions().getOrDefault(doc, 0);
    return v == 0 ? "" : tasks.version(project, id, doc, v);
  }

  private int docVersion(String project, String id, String doc) {
    try {
      return tasks.readDoc(project, id, doc).versions();
    } catch (NoSuchElementException e) {
      return 0;
    }
  }

  private String doc(String project, String id, String doc) {
    try {
      return tasks.readDoc(project, id, doc).content();
    } catch (NoSuchElementException e) {
      return "";
    }
  }

  private List<String> repoNames(String project) {
    return projects.get(project).config().repos().stream().map(RepoConfig::name).toList();
  }

  private RoomConfig room(String project) {
    return projects.get(project).config().room();
  }

  private void safeNote(String project, String id, String text) {
    try {
      tasks.note(project, id, Actor.DEVMAP, text);
    } catch (RuntimeException e) {
      // ticket gone
    }
  }

  private static String now() {
    return LocalDateTime.now().format(TIME);
  }
}
