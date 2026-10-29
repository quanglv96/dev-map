package com.devmap.rule;

import java.util.List;

/**
 * Effective rules of one service (FR-29 → FR-32) after preset ← project default ← service file.
 * Paths in {@link Scope} are globs relative to the service folder. What is not here cannot be configured: every push needs
 * the PMO's confirmation, force-push does not exist and protected branches are never pushed (FR-30).
 */
public record Rules(String preset, Git git, Quality quality, Scope scope) {

  /** Project-wide only: a repo can hold several services but has one history. */
  public record Git(String commitTemplate, List<String> protectedBranches) {
  }

  public record Quality(boolean layering, boolean staticAnalysis, List<String> staticCommands, boolean unitTest,
      String unitTestCommand, boolean integrationTest, String integrationTestCommand, int timeoutMinutes) {
  }

  /** forbidden: never edited; schema and dependency changes need the PMO's approval (FR-32). */
  public record Scope(List<String> forbiddenPaths, List<String> schemaPaths, List<String> dependencyFiles) {
  }
}
