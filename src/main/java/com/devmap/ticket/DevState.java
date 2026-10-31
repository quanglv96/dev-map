package com.devmap.ticket;

import java.util.ArrayList;
import java.util.List;

/**
 * dev.json in a ticket folder: the worktree of each repo and what has been verified and pushed. TaskService reads it to keep
 * the gates honest: SA verification only after a passing quality gate on the current commit, Done only after the verified
 * commit was pushed. direct: PMO option, no ticket branch, the worktree is detached on the base branch and the push goes to it.
 */
public record DevState(List<RepoWork> repos, List<String> approved, boolean direct) {

  /** gateHead: commit the last passing gate ran on (null = not passed); pushedHead: commit pushed after PMO confirmation. */
  public record RepoWork(String repo, String worktree, String branch, String baseBranch, String baseCommit, String gateHead,
      String pushedHead, String pulledAt) {

    RepoWork withBase(String commit, String at) {
      return new RepoWork(repo, worktree, branch, baseBranch, commit, null, pushedHead, at);
    }

    RepoWork withGate(String head) {
      return new RepoWork(repo, worktree, branch, baseBranch, baseCommit, head, pushedHead, pulledAt);
    }

    RepoWork withPushed(String head) {
      return new RepoWork(repo, worktree, branch, baseBranch, baseCommit, gateHead, head, pulledAt);
    }
  }

  public static final DevState EMPTY = new DevState(List.of(), List.of(), false);

  public DevState {
    repos = repos == null ? List.of() : List.copyOf(repos);
    approved = approved == null ? List.of() : List.copyOf(approved);
  }

  public RepoWork repo(String name) {
    return repos.stream().filter(r -> r.repo().equals(name)).findFirst().orElse(null);
  }

  DevState with(RepoWork work) {
    List<RepoWork> out = new ArrayList<>(repos.stream().filter(r -> !r.repo().equals(work.repo())).toList());
    out.add(work);
    return new DevState(out, approved, direct);
  }

  DevState withApproved(List<String> list) {
    return new DevState(repos, list, direct);
  }

  DevState withDirect(boolean value) {
    return new DevState(repos, approved, value);
  }
}
