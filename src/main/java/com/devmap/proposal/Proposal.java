package com.devmap.proposal;

import java.util.List;

/**
 * FR-75: one finding or proposal of SA, Dev or the PMO, stored as {@code tasks/<ticket>/proposals/<time>-<author>.json}.
 * origin: id of the first proposal of the same discussion (itself for a new one) and round: how many times it went back and forth,
 * both for the loop guard (FR-81). agentAffects: impact as the agent judged it; affects: after the hard rule (FR-79).
 * views: SA and Dev viewpoints when the loop guard handed the decision to the PMO.
 */
public record Proposal(
    String id,
    String origin,
    int round,
    String author,
    String type,
    String summary,
    Location location,
    String proposal,
    String affects,
    String agentAffects,
    List<String> refs,
    String status,
    String note,
    List<String> views,
    String created,
    String updated) {

  public static final List<String> AUTHORS = List.of("SA", "DEV", "PMO");
  public static final List<String> TYPES = List.of("BUG_FOUND", "SOLUTION_CHANGE", "SCOPE_CHANGE", "QUESTION");
  /** In order of weight: anything but TECHNICAL_ONLY needs the PMO (FR-79). */
  public static final List<String> AFFECTS = List.of("TECHNICAL_ONLY", "AC", "BUSINESS_RULE", "SCOPE");
  public static final List<String> STATUSES = List.of("OPEN", "APPLIED", "REJECTED", "NEEDS_PMO");

  /** Where the finding is: repo, file and method signature (any may be null). */
  public record Location(String repo, String file, String signature) {
  }

  public Proposal {
    refs = refs == null ? List.of() : List.copyOf(refs);
    views = views == null ? List.of() : List.copyOf(views);
  }

  public boolean open() {
    return status.equals("OPEN") || status.equals("NEEDS_PMO");
  }

  /** The heavier of two impacts (TECHNICAL_ONLY < AC < BUSINESS_RULE < SCOPE). */
  public static String heavier(String a, String b) {
    return AFFECTS.indexOf(a == null ? "TECHNICAL_ONLY" : a) >= AFFECTS.indexOf(b == null ? "TECHNICAL_ONLY" : b)
        ? (a == null ? "TECHNICAL_ONLY" : a) : b;
  }

  Proposal resolved(String newStatus, String newAffects, String newAgentAffects, String newNote, List<String> newViews, String at) {
    return new Proposal(id, origin, round, author, type, summary, location, proposal, newAffects, newAgentAffects, refs, newStatus,
        newNote, newViews, created, at);
  }
}
