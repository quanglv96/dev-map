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

/** Chatbox of a ticket: one question = one new agent session (FR-37 → FR-40). */
@RestController
@RequestMapping("/api/projects/{project}/tasks/{id}")
public class AgentController {

  public record AskRequest(String role, String question, boolean fullScan) {
  }

  private final AgentService agents;

  public AgentController(AgentService agents) {
    this.agents = agents;
  }

  @GetMapping("/chat")
  public AgentService.ChatView chat(@PathVariable String project, @PathVariable String id) {
    return agents.chat(project, id);
  }

  @PostMapping("/ask")
  public ChatMessage ask(@PathVariable String project, @PathVariable String id, @RequestBody AskRequest req) {
    return agents.ask(project, id, req.role(), req.question(), req.fullScan());
  }

  @GetMapping("/chat/input")
  public Map<String, String> input(@PathVariable String project, @PathVariable String id, @RequestParam int no) {
    return Map.of("content", agents.inputLog(project, id, no));
  }

  @GetMapping("/prompt")
  public Map<String, String> prompt(@PathVariable String project, @PathVariable String id, @RequestParam(defaultValue = "") String role) {
    return Map.of("content", agents.preview(project, id, role, ""));
  }
}
