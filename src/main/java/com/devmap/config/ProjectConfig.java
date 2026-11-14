package com.devmap.config;

import com.devmap.secret.SecretsConfig;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Contents of config.yml in a project's data folder. */
public record ProjectConfig(
    String project,
    String color,
    String ticketCode,
    String ticketPattern,
    Map<String, String> branchPatterns,
    List<RepoConfig> repos,
    List<String> environments,
    boolean scanFullProject,
    SecretsConfig secrets,
    RoomConfig room) {

  /** manual: PMO may type a code, blank means auto-generate; auto: always generate. */
  public static final List<String> TICKET_CODE_MODES = List.of("manual", "auto");

  public static final Map<String, String> DEFAULT_BRANCH_PATTERNS = new TreeMap<>(Map.of(
      "FEAT", "feature/{code}-{slug}",
      "MAINT", "chore/{code}-{slug}",
      "BUG", "bugfix/{code}-{slug}"));

  public ProjectConfig {
    ticketCode = ticketCode == null ? "manual" : ticketCode;
    branchPatterns = branchPatterns == null ? DEFAULT_BRANCH_PATTERNS : new TreeMap<>(branchPatterns);
    repos = repos == null ? List.of() : List.copyOf(repos);
    environments = environments == null ? List.of() : List.copyOf(environments);
    secrets = secrets == null ? SecretsConfig.EMPTY : secrets;
    room = room == null ? RoomConfig.DEFAULT : room;
  }

  /** Without a room section (default room settings). */
  public ProjectConfig(String project, String color, String ticketCode, String ticketPattern, Map<String, String> branchPatterns,
      List<RepoConfig> repos, List<String> environments, boolean scanFullProject, SecretsConfig secrets) {
    this(project, color, ticketCode, ticketPattern, branchPatterns, repos, environments, scanFullProject, secrets, null);
  }

  public static ProjectConfig create(String project, String color, List<RepoConfig> repos) {
    return new ProjectConfig(project, color, null, null, null, repos, null, false, null, null);
  }

  public ProjectConfig withRoom(RoomConfig r) {
    return new ProjectConfig(project, color, ticketCode, ticketPattern, branchPatterns, repos, environments, scanFullProject, secrets, r);
  }

  public ProjectConfig withProject(String name) {
    return new ProjectConfig(name, color, ticketCode, ticketPattern, branchPatterns, repos, environments, scanFullProject, secrets,
        room);
  }
}
