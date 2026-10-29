package com.devmap.ticket;

import java.util.List;

/** spec.json (FR-44): the only form of the PMO request an LLM ever receives. */
public record Spec(
    String issueId,
    String project,
    String actionType,
    List<String> targetModules,
    String summary,
    List<String> constraints,
    String source) {

  public static final List<String> ACTION_TYPES = List.of("FEAT", "MAINT", "BUG", "REOPEN_BUG", "ADD_LOGIC");
}
