package com.devmap.agent;

import com.devmap.agent.AgentProvider.Usage;
import java.util.List;
import java.util.Map;

/**
 * One entry of chat.json in the ticket folder. The chat is for the PMO to read back only: no agent session ever receives it.
 * from: PMO | SA | DEV. state: running | done | error. context: ticket files the session was built from.
 * heads: commit of each worktree when a Dev session ended, to spot the PMO's own changes next time (FR-28).
 */
public record ChatMessage(int id, String at, String from, String mode, String text, List<String> context, List<Step> steps,
    Usage usage, String state, Map<String, String> heads) {

  /** kind: text | tool | error | note. */
  public record Step(String kind, String text) {
  }

  public ChatMessage {
    context = context == null ? List.of() : List.copyOf(context);
    steps = steps == null ? List.of() : List.copyOf(steps);
    heads = heads == null ? Map.of() : Map.copyOf(heads);
  }

  ChatMessage with(String newText, List<Step> newSteps, Usage newUsage, String newState, Map<String, String> newHeads) {
    return new ChatMessage(id, at, from, mode, newText, context, newSteps, newUsage, newState, newHeads);
  }
}
