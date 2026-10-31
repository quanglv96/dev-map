package com.devmap.web;

import com.devmap.rule.QualityGate;
import com.devmap.ticket.Task;
import com.devmap.ticket.TaskService;
import com.devmap.ticket.TaskService.Actor;
import com.devmap.ticket.TicketGitService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Dev side of a ticket: worktrees, commit, quality gate, push. Calls come from the PMO's browser, so they act as the PMO. */
@RestController
@RequestMapping("/api/projects/{project}/tasks/{id}")
public class DevController {

  public record CommitRequest(String repo, String message) {
  }

  public record ApproveRequest(String repo, List<String> files) {
  }

  public record DirectRequest(boolean direct) {
  }

  private final TicketGitService dev;
  private final TaskService tasks;

  public DevController(TicketGitService dev, TaskService tasks) {
    this.dev = dev;
    this.tasks = tasks;
  }

  @PutMapping("/repos")
  public Task repos(@PathVariable String project, @PathVariable String id, @RequestBody List<String> repos) {
    return tasks.setRepos(project, id, repos, Actor.PMO);
  }

  @GetMapping("/dev")
  public TicketGitService.View view(@PathVariable String project, @PathVariable String id) {
    return dev.view(project, id);
  }

  @PutMapping("/dev/direct")
  public TicketGitService.View direct(@PathVariable String project, @PathVariable String id, @RequestBody DirectRequest req) {
    return dev.setDirect(project, id, req.direct(), Actor.PMO);
  }

  @PostMapping("/dev/start")
  public TicketGitService.View start(@PathVariable String project, @PathVariable String id) {
    return dev.start(project, id, Actor.PMO);
  }

  @GetMapping("/dev/diff")
  public Map<String, String> diff(@PathVariable String project, @PathVariable String id, @RequestParam String repo) {
    return Map.of("diff", dev.diff(project, id, repo));
  }

  @GetMapping("/dev/scope")
  public TicketGitService.ScopeCheck scope(@PathVariable String project, @PathVariable String id, @RequestParam String repo) {
    return dev.checkScope(project, id, repo);
  }

  @PostMapping("/dev/approve")
  public TicketGitService.ScopeCheck approve(@PathVariable String project, @PathVariable String id, @RequestBody ApproveRequest req) {
    return dev.approve(project, id, req.repo(), req.files() == null ? List.of() : req.files(), Actor.PMO);
  }

  @PostMapping("/dev/commit")
  public TicketGitService.View commit(@PathVariable String project, @PathVariable String id, @RequestBody CommitRequest req) {
    return dev.commit(project, id, req.repo(), req.message(), Actor.PMO);
  }

  @PostMapping("/dev/gate")
  public List<QualityGate.Result> gate(@PathVariable String project, @PathVariable String id) {
    return dev.gate(project, id, Actor.PMO);
  }

  @PostMapping("/dev/push")
  public TicketGitService.View push(@PathVariable String project, @PathVariable String id) {
    return dev.confirmPush(project, id, Actor.PMO);
  }
}
