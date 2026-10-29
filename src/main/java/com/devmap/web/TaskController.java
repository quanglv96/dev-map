package com.devmap.web;

import com.devmap.ticket.Task;
import com.devmap.ticket.TaskService;
import com.devmap.ticket.TaskService.Actor;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Ticket API. Every call here comes from the PMO's browser, so it acts as {@link Actor#PMO}. */
@RestController
@RequestMapping("/api")
public class TaskController {

  public record CreateTaskRequest(String type, String code, String title, String request) {
  }

  public record TransitionRequest(String to) {
  }

  public record DocRequest(String content) {
  }

  public record ReopenRequest(String kind, String description) {
  }

  private final TaskService tasks;

  public TaskController(TaskService tasks) {
    this.tasks = tasks;
  }

  @GetMapping("/overview")
  public List<TaskService.ProjectOverview> overview() {
    return tasks.overview();
  }

  @GetMapping("/projects/{project}/tasks")
  public List<Task> list(@PathVariable String project) {
    return tasks.list(project);
  }

  @PostMapping("/projects/{project}/tasks")
  @ResponseStatus(HttpStatus.CREATED)
  public TaskService.Created create(@PathVariable String project, @RequestBody CreateTaskRequest req) {
    return tasks.create(project, req.type(), req.code(), req.title(), req.request());
  }

  @GetMapping("/projects/{project}/tasks/{id}")
  public TaskService.Detail detail(@PathVariable String project, @PathVariable String id) {
    return tasks.detail(project, id);
  }

  @PostMapping("/projects/{project}/tasks/{id}/transition")
  public Task transition(@PathVariable String project, @PathVariable String id, @RequestBody TransitionRequest req) {
    return tasks.transition(project, id, req.to(), Actor.PMO);
  }

  @PostMapping("/projects/{project}/tasks/{id}/reopen")
  public Task reopen(@PathVariable String project, @PathVariable String id, @RequestBody ReopenRequest req) {
    return tasks.reopen(project, id, req.kind(), req.description(), Actor.PMO);
  }

  @GetMapping("/projects/{project}/tasks/{id}/doc")
  public TaskService.Doc doc(@PathVariable String project, @PathVariable String id, @RequestParam String name) {
    return tasks.readDoc(project, id, name);
  }

  @PutMapping("/projects/{project}/tasks/{id}/doc")
  public TaskService.Doc writeDoc(@PathVariable String project, @PathVariable String id, @RequestParam String name,
      @RequestBody DocRequest req) {
    return tasks.writeDoc(project, id, name, req.content(), Actor.PMO);
  }

  @GetMapping("/projects/{project}/tasks/{id}/doc/version")
  public Map<String, String> version(@PathVariable String project, @PathVariable String id, @RequestParam String name,
      @RequestParam int n) {
    return Map.of("content", tasks.version(project, id, name, n));
  }

  @GetMapping("/projects/{project}/tasks/{id}/doc/diff")
  public Map<String, String> diff(@PathVariable String project, @PathVariable String id, @RequestParam String name,
      @RequestParam int from, @RequestParam int to) {
    return Map.of("diff", tasks.diff(project, id, name, from, to));
  }

  @GetMapping("/projects/{project}/queue")
  public List<Task> queue(@PathVariable String project) {
    return tasks.queue(project);
  }

  @PutMapping("/projects/{project}/queue")
  public List<Task> reorder(@PathVariable String project, @RequestBody List<String> order) {
    return tasks.reorderQueue(project, order);
  }
}
