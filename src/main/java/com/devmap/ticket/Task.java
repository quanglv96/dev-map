package com.devmap.ticket;

import java.util.List;

/**
 * Front matter of task.md. handoff: set while the ticket waits to continue on another machine (FR-55). flags: what needs attention
 * (FR-88); reopened is the "reopened" flag (count) and stays its own field as before.
 */
public record Task(
    String id,
    String type,
    String title,
    TaskStatus status,
    List<String> repos,
    String branch,
    int version,
    int reopened,
    String created,
    String updated,
    Handoff handoff,
    Flags flags) {

  public static final List<String> TYPES = List.of("FEAT", "MAINT", "BUG");

  /** FR-55: the machine that handed the ticket over and the pushed commit of each repo to continue from. */
  public record Handoff(String machine, String at, List<Repo> repos) {

    public record Repo(String repo, String branch, String commit) {
    }

    public Handoff {
      repos = repos == null ? List.of() : List.copyOf(repos);
    }
  }

  /**
   * FR-88. needsPmo: why the ticket waits for the PMO while keeping its status (null = not waiting); openProposals: proposals still
   * OPEN or NEEDS_PMO; syncPaused: automatic sync between agents is off (FR-83); midChange: AC/scope changed while implementing;
   * fileOverlap: other open tickets touching the same files; approvedPlan: plan.md version of the last PMO approval (FR-87).
   */
  public record Flags(String needsPmo, int openProposals, boolean syncPaused, boolean midChange, List<String> fileOverlap,
      Integer approvedPlan) {

    public static final Flags NONE = new Flags(null, 0, false, false, List.of(), null);

    public Flags {
      fileOverlap = fileOverlap == null ? List.of() : List.copyOf(fileOverlap);
    }

    public Flags withNeedsPmo(String reason) {
      return new Flags(reason, openProposals, syncPaused, midChange, fileOverlap, approvedPlan);
    }

    public Flags withOpenProposals(int n) {
      return new Flags(needsPmo, n, syncPaused, midChange, fileOverlap, approvedPlan);
    }

    public Flags withSyncPaused(boolean v) {
      return new Flags(needsPmo, openProposals, v, midChange, fileOverlap, approvedPlan);
    }

    public Flags withMidChange(boolean v) {
      return new Flags(needsPmo, openProposals, syncPaused, v, fileOverlap, approvedPlan);
    }

    public Flags withFileOverlap(List<String> ids) {
      return new Flags(needsPmo, openProposals, syncPaused, midChange, ids, approvedPlan);
    }

    public Flags withApprovedPlan(Integer v) {
      return new Flags(needsPmo, openProposals, syncPaused, midChange, fileOverlap, v);
    }
  }

  public Task {
    repos = repos == null ? List.of() : List.copyOf(repos);
    flags = flags == null ? Flags.NONE : flags;
  }

  Task withStatus(TaskStatus s, String now) {
    return new Task(id, type, title, s, repos, branch, version, reopened, created, now, handoff, flags);
  }

  Task withVersion(int v, String now) {
    return new Task(id, type, title, status, repos, branch, v, reopened, created, now, handoff, flags);
  }

  Task withBranch(String b, String now) {
    return new Task(id, type, title, status, repos, b, version, reopened, created, now, handoff, flags);
  }

  Task withRepos(List<String> r, String now) {
    return new Task(id, type, title, status, r, branch, version, reopened, created, now, handoff, flags);
  }

  Task withHandoff(Handoff h, String now) {
    return new Task(id, type, title, status, repos, branch, version, reopened, created, now, h, flags);
  }

  Task withFlags(Flags f, String now) {
    return new Task(id, type, title, status, repos, branch, version, reopened, created, now, handoff, f);
  }

  Task withReopened(int n) {
    return new Task(id, type, title, status, repos, branch, version, n, created, updated, handoff, flags);
  }

  /** FR-86 DONE → ANALYSIS: same code, reopened +1; a new round, so the flags of the previous one are cleared. */
  Task reopen(String now) {
    return new Task(id, type, title, TaskStatus.ANALYSIS, repos, branch, version, reopened + 1, created, now, handoff,
        new Flags(null, flags.openProposals(), flags.syncPaused(), false, List.of(), flags.approvedPlan()));
  }
}
