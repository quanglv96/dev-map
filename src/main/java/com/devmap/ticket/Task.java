package com.devmap.ticket;

import java.util.List;

/** Front matter of task.md. handoff: set while the ticket waits to continue on another machine (FR-55). */
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
    Handoff handoff) {

  public static final List<String> TYPES = List.of("FEAT", "MAINT", "BUG");

  /** FR-55: the machine that handed the ticket over and the pushed commit of each repo to continue from. */
  public record Handoff(String machine, String at, List<Repo> repos) {

    public record Repo(String repo, String branch, String commit) {
    }

    public Handoff {
      repos = repos == null ? List.of() : List.copyOf(repos);
    }
  }

  public Task {
    repos = repos == null ? List.of() : List.copyOf(repos);
  }

  Task withStatus(TaskStatus s, String now) {
    return new Task(id, type, title, s, repos, branch, version, reopened, created, now, handoff);
  }

  Task withVersion(int v, String now) {
    return new Task(id, type, title, status, repos, branch, v, reopened, created, now, handoff);
  }

  Task withRepos(List<String> r, String now) {
    return new Task(id, type, title, status, r, branch, version, reopened, created, now, handoff);
  }

  Task withHandoff(Handoff h, String now) {
    return new Task(id, type, title, status, repos, branch, version, reopened, created, now, h);
  }

  Task reopen(String now) {
    return new Task(id, type, title, TaskStatus.SA_ANALYSIS, repos, branch, version, reopened + 1, created, now, handoff);
  }
}
