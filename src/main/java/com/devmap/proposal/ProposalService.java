package com.devmap.proposal;

import com.devmap.config.ProjectService;
import com.devmap.secret.SecretMasker;
import com.devmap.secret.SecretsConfig;
import com.devmap.ticket.TaskService;
import com.devmap.ticket.TaskService.Actor;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * FR-75 / FR-76: proposals of a ticket. Every text is masked before it is stored (FR-36); the open count lands on the ticket's
 * flags (FR-88); a new proposal is published so the orchestration can react without anyone relaying it (FR-77).
 */
@Service
public class ProposalService {

  /** What an author gives; replyTo: a proposal of the same discussion (keeps the origin for the loop guard). */
  public record Input(String author, String type, String summary, Proposal.Location location, String proposal, String affects,
      List<String> refs, String replyTo) {
  }

  /** Published after a proposal is stored. */
  public record Created(String project, String taskId, Proposal proposal) {
  }

  private static final DateTimeFormatter ID = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

  private final TaskService tasks;
  private final ProposalRepository repo;
  private final SecretMasker masker;
  private final ProjectService projects;
  private final ApplicationEventPublisher events;

  public ProposalService(TaskService tasks, ProposalRepository repo, SecretMasker masker, ProjectService projects,
      ApplicationEventPublisher events) {
    this.tasks = tasks;
    this.repo = repo;
    this.masker = masker;
    this.projects = projects;
    this.events = events;
  }

  public List<Proposal> list(String project, String id) {
    return repo.list(tasks.folder(project, id));
  }

  public Proposal get(String project, String id, String proposalId) {
    return repo.find(tasks.folder(project, id), proposalId)
        .orElseThrow(() -> new NoSuchElementException("Không có đề xuất " + proposalId));
  }

  public synchronized Proposal create(String project, String id, Input in) {
    String author = upper(in.author());
    String type = upper(in.type());
    String affects = in.affects() == null || in.affects().isBlank() ? "TECHNICAL_ONLY" : upper(in.affects());
    require(Proposal.AUTHORS, author, "author");
    require(Proposal.TYPES, type, "type");
    require(Proposal.AFFECTS, affects, "affects");
    if (in.summary() == null || in.summary().isBlank()) {
      throw new IllegalArgumentException("Đề xuất cần summary");
    }
    Path folder = tasks.folder(project, id);
    String origin = null;
    if (in.replyTo() != null && !in.replyTo().isBlank()) {
      origin = repo.find(folder, in.replyTo().strip()).map(Proposal::origin)
          .orElseThrow(() -> new NoSuchElementException("Không có đề xuất " + in.replyTo()));
    }
    String pid = LocalDateTime.now().format(ID) + "-" + author.toLowerCase(Locale.ROOT);
    for (int n = 2; repo.exists(folder, pid); n++) {
      pid = LocalDateTime.now().format(ID) + "-" + author.toLowerCase(Locale.ROOT) + "-" + n;
    }
    String root = origin == null ? pid : origin;
    int round = (int) repo.list(folder).stream().filter(p -> p.origin().equals(root) && p.author().equals(author)).count() + 1;
    SecretsConfig secrets = projects.get(project).config().secrets();
    Proposal.Location loc = in.location() == null ? null : new Proposal.Location(mask(in.location().repo(), secrets),
        mask(in.location().file(), secrets), mask(in.location().signature(), secrets));
    String status = type.equals("QUESTION") ? "NEEDS_PMO" : "OPEN";
    String now = now();
    Proposal p = new Proposal(pid, root, round, author, type, mask(in.summary().strip(), secrets), loc, mask(in.proposal(), secrets),
        affects, affects, in.refs() == null ? List.of() : in.refs().stream().map(r -> mask(r, secrets)).toList(), status, null,
        List.of(), now, now);
    repo.write(folder, p);
    tasks.note(project, id, actor(author), "Đề xuất " + type + " (" + affects + ") " + pid + ": " + clip(p.summary(), 160));
    if (status.equals("NEEDS_PMO")) {
      tasks.flags(project, id, f -> f.withNeedsPmo("Câu hỏi chờ PMO: " + clip(p.summary(), 120)), Actor.DEVMAP, null);
    }
    recount(project, id);
    events.publishEvent(new Created(project, id, p));
    return p;
  }

  /** SA (or the PMO, or DevMap's hard rule) settles a proposal; note and views are masked. */
  public synchronized Proposal resolve(String project, String id, String proposalId, String status, String affects,
      String agentAffects, String note, List<String> views, Actor actor) {
    String st = upper(status);
    require(Proposal.STATUSES, st, "status");
    Proposal p = get(project, id, proposalId);
    String a = affects == null || affects.isBlank() ? p.affects() : upper(affects);
    require(Proposal.AFFECTS, a, "affects");
    String agent = agentAffects == null || agentAffects.isBlank() ? p.agentAffects() : upper(agentAffects);
    SecretsConfig secrets = projects.get(project).config().secrets();
    Proposal next = p.resolved(st, a, agent, note == null ? p.note() : mask(note, secrets),
        views == null ? p.views() : views.stream().map(v -> mask(v, secrets)).toList(), now());
    if (next.equals(p)) {
      return p;
    }
    repo.write(tasks.folder(project, id), next);
    tasks.note(project, id, actor, "Đề xuất " + proposalId + " → " + st + " (" + a + ")" + (note == null ? "" : ": " + clip(note, 160)));
    recount(project, id);
    return next;
  }

  private void recount(String project, String id) {
    int open = (int) list(project, id).stream().filter(Proposal::open).count();
    tasks.flags(project, id, f -> f.withOpenProposals(open), Actor.DEVMAP, null);
  }

  private String mask(String text, SecretsConfig secrets) {
    return text == null ? null : masker.mask(text, secrets).text();
  }

  private static Actor actor(String author) {
    return switch (author) {
      case "SA" -> Actor.SA;
      case "DEV" -> Actor.DEV;
      default -> Actor.PMO;
    };
  }

  private static void require(List<String> allowed, String value, String field) {
    if (!allowed.contains(value)) {
      throw new IllegalArgumentException(field + " phải là một trong " + allowed + ": " + value);
    }
  }

  private static String upper(String s) {
    return s == null ? "" : s.strip().toUpperCase(Locale.ROOT);
  }

  static String clip(String s, int max) {
    String t = s == null ? "" : s.strip().replaceAll("\\s+", " ");
    return t.length() <= max ? t : t.substring(0, max) + "…";
  }

  private static String now() {
    return LocalDateTime.now().format(TIME);
  }
}
