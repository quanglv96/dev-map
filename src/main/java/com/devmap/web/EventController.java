package com.devmap.web;

import com.devmap.agent.AgentService.AgentProgress;
import com.devmap.graph.GraphService.GraphChanged;
import com.devmap.sync.SyncService.SyncChanged;
import com.devmap.ticket.TaskChanged;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.event.EventListener;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Server-Sent Events: open tabs learn about ticket ("task"), graph ("graph") and agent ("agent") changes, all projects or
 * ?project=; data sync ("sync") always.
 */
@RestController
public class EventController {

  private final Map<SseEmitter, String> emitters = new ConcurrentHashMap<>();

  @GetMapping("/api/events")
  public SseEmitter events(@RequestParam(defaultValue = "") String project) {
    SseEmitter emitter = new SseEmitter(0L);
    emitters.put(emitter, project);
    emitter.onCompletion(() -> emitters.remove(emitter));
    emitter.onTimeout(() -> emitters.remove(emitter));
    emitter.onError(e -> emitters.remove(emitter));
    return emitter;
  }

  @EventListener
  public void onTaskChanged(TaskChanged change) {
    send(change.project(), "task", change);
  }

  @EventListener
  public void onGraphChanged(GraphChanged change) {
    send(change.project(), "graph", change);
  }

  @EventListener
  public void onAgent(AgentProgress progress) {
    send(progress.project(), "agent", progress);
  }

  /** FR-56: the data repo is shared by every project, so every tab gets it. */
  @EventListener
  public void onSync(SyncChanged change) {
    send(null, "sync", change);
  }

  private void send(String changed, String name, Object data) {
    emitters.forEach((emitter, project) -> {
      if (changed != null && !project.isEmpty() && !project.equals(changed)) {
        return;
      }
      try {
        emitter.send(SseEmitter.event().name(name).data(data));
      } catch (IOException | IllegalStateException e) {
        emitters.remove(emitter);
      }
    });
  }
}
