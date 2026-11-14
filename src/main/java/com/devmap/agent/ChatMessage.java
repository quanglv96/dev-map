package com.devmap.agent;

import com.devmap.agent.AgentProvider.Usage;
import java.util.List;
import java.util.Map;

/**
 * One entry of chat.json in the ticket folder. The chat is for the PMO to read back only: no agent session ever receives it.
 * from: PMO | SA | DEV. state: running | done | error. context: ticket files the session was built from.
 * heads: commit of each worktree when a Dev session ended, to spot the PMO's own changes next time (FR-28).
 * from DEVMAP with mode "card": a room card (FR-76, FR-80), no session behind it. mode "sync": a session started by an event.
 * trigger: the sync event behind a "sync" session, to tell the PMO what the automatic sync costs.
 */
public record ChatMessage(int id, String at, String from, String mode, String text, List<String> context, List<Step> steps,
    Usage usage, String state, Map<String, String> heads, Card card, String trigger) {

  /**
   * type: doc (an automatic update of doc from → to, with diff and undo), proposal (id), notice (level info|warn|important),
   * choice (options: buttons sending text again with a decision). undone: the doc card was undone.
   */
  public record Card(String type, String text, String doc, Integer from, Integer to, String proposal, String level,
      List<String> options, boolean undone) {

    public Card {
      options = options == null ? List.of() : List.copyOf(options);
    }

    public static Card notice(String level, String text) {
      return new Card("notice", text, null, null, null, null, level, null, false);
    }

    public static Card doc(String text, String doc, int from, int to) {
      return new Card("doc", text, doc, from, to, null, "info", null, false);
    }

    public static Card proposal(String text, String id) {
      return new Card("proposal", text, null, null, null, id, "info", null, false);
    }

    public static Card choice(String text, List<String> options) {
      return new Card("choice", text, null, null, null, null, "warn", options, false);
    }

    Card asUndone() {
      return new Card(type, text, doc, from, to, proposal, level, options, true);
    }
  }

  public ChatMessage(int id, String at, String from, String mode, String text, List<String> context, List<Step> steps, Usage usage,
      String state, Map<String, String> heads) {
    this(id, at, from, mode, text, context, steps, usage, state, heads, null, null);
  }

  /** kind: text | tool | error | note. */
  public record Step(String kind, String text) {
  }

  public ChatMessage {
    context = context == null ? List.of() : List.copyOf(context);
    steps = steps == null ? List.of() : List.copyOf(steps);
    heads = heads == null ? Map.of() : Map.copyOf(heads);
  }

  ChatMessage with(String newText, List<Step> newSteps, Usage newUsage, String newState, Map<String, String> newHeads) {
    return new ChatMessage(id, at, from, mode, newText, context, newSteps, newUsage, newState, newHeads, card, trigger);
  }
}
