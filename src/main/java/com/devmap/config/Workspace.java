package com.devmap.config;

/**
 * Entry of ~/.devmap/workspaces.yml: a project, the absolute path of its data folder, and whether this machine pushes the
 * project's data repo without asking (null = the machine default, see D-53).
 */
public record Workspace(String name, String dataDir, Boolean autoPush) {

  public Workspace(String name, String dataDir) {
    this(name, dataDir, null);
  }
}
