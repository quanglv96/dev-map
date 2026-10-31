package com.devmap.web;

import com.devmap.agent.AgentService;
import com.devmap.agent.ChatMessage;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Chatbox of a ticket: one question = one new agent session (FR-37 → FR-40); agent activity and the queue run of a project. */
@RestController
@RequestMapping("/api/projects/{project}")
public class AgentController {

  public record AskRequest(String role, String question, boolean fullScan) {
  }

  public record QueueRun(boolean on) {
  }

  private final AgentService agents;

  public AgentController(AgentService agents) {
    this.agents = agents;
  }

  @GetMapping("/tasks/{id}/chat")
  public AgentService.ChatView chat(@PathVariable String project, @PathVariable String id) {
    return agents.chat(project, id);
  }

  @PostMapping("/tasks/{id}/ask")
  public ChatMessage ask(@PathVariable String project, @PathVariable String id, @RequestBody AskRequest req) {
    return agents.ask(project, id, req.role(), req.question(), req.fullScan());
  }

  /** PMO approved the plan: Dev implements it from the documents; SA verifies by itself afterwards. */
  @PostMapping("/tasks/{id}/implement")
  public ChatMessage implement(@PathVariable String project, @PathVariable String id) {
    return agents.implement(project, id);
  }

  @GetMapping("/tasks/{id}/chat/input")
  public Map<String, String> input(@PathVariable String project, @PathVariable String id, @RequestParam int no) {
    return Map.of("content", agents.inputLog(project, id, no));
  }

  @GetMapping("/tasks/{id}/prompt")
  public Map<String, String> prompt(@PathVariable String project, @PathVariable String id, @RequestParam(defaultValue = "") String role) {
    return Map.of("content", agents.preview(project, id, role, ""));
  }

  /** Tab title, icon and notifications of the project page. */
  @GetMapping("/agents")
  public AgentService.Activity activity(@PathVariable String project) {
    return agents.activity(project);
  }

  /** FR-40: tokens of the project and the account quota Claude Code reports. */
  @GetMapping("/usage")
  public AgentService.UsageView usage(@PathVariable String project) {
    return agents.usage(project);
  }

    /** AC-04: Dev works the queue of Ready tickets in the PMO's order. */
  @PostMapping("/queue/run")
  public AgentService.Activity runQueue(@PathVariable String project, @RequestBody QueueRun req) {
    return agents.runQueue(project, req.on());
  }
}
