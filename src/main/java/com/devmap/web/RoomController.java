package com.devmap.web;

import com.devmap.orchestration.Orchestrator;
import com.devmap.orchestration.SyncEvent;
import com.devmap.proposal.Proposal;
import com.devmap.room.RoomService;
import com.devmap.ticket.Task;
import com.devmap.ticket.TaskService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Room of a ticket (PROMPT_ROOM): PMO messages, proposals, undo of automatic updates, sync pause. Every call is the PMO's. */
@RestController
@RequestMapping("/api/projects/{project}/tasks/{id}/room")
public class RoomController {

  public record SendRequest(String text, String to, String bugCase) {
  }

  public record PauseRequest(boolean paused) {
  }

  public record UndoRequest(String doc, int from, int to) {
  }

  public record DecideRequest(String status, String note) {
  }

  public record PriorityRequest(String first) {
  }

  private final RoomService room;
  private final Orchestrator sync;

  public RoomController(RoomService room, Orchestrator sync) {
    this.room = room;
    this.sync = sync;
  }

  @PostMapping("/send")
  public RoomService.Sent send(@PathVariable String project, @PathVariable String id, @RequestBody SendRequest req) {
    return room.send(project, id, req.text(), req.to(), req.bugCase());
  }

  @GetMapping("/proposals")
  public List<Proposal> proposals(@PathVariable String project, @PathVariable String id) {
    return room.proposals(project, id);
  }

  @PostMapping("/proposals/{proposal}")
  public Proposal decide(@PathVariable String project, @PathVariable String id, @PathVariable String proposal,
      @RequestBody DecideRequest req) {
    return room.decide(project, id, proposal, req.status(), req.note());
  }

  @GetMapping("/events")
  public List<SyncEvent> events(@PathVariable String project, @PathVariable String id) {
    return sync.list(project, id);
  }

  @PostMapping("/pause")
  public Task pause(@PathVariable String project, @PathVariable String id, @RequestBody PauseRequest req) {
    return sync.pause(project, id, req.paused());
  }

  @PostMapping("/undo")
  public TaskService.Doc undo(@PathVariable String project, @PathVariable String id, @RequestBody UndoRequest req) {
    return sync.undo(project, id, req.doc(), req.from(), req.to());
  }

  @PostMapping("/priority")
  public Map<String, String> priority(@PathVariable String project, @PathVariable String id, @RequestBody PriorityRequest req) {
    room.priority(project, id, req.first());
    return Map.of("first", req.first());
  }
}
