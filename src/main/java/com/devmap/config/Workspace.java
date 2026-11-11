package com.devmap.config;

import java.util.List;

/**
 * Entry of ~/.devmap/workspaces.yml: a project, the absolute path of its data folder, whether this machine pushes the
 * project's data repo without asking (null = the machine default, see D-53) and the read-only reference folders the PMO lets the
 * agents read on this machine (D-70).
 */
public record Workspace(String name, String dataDir, Boolean autoPush, List<String> references) {

  public Workspace {
    references = references == null ? List.of() : List.copyOf(references);
  }

  public Workspace(String name, String dataDir) {
    this(name, dataDir, null, null);
  }

  public Workspace withAutoPush(Boolean on) {
    return new Workspace(name, dataDir, on, references);
  }

  public Workspace withDataDir(String dir) {
    return new Workspace(name, dir, autoPush, references);
  }

  public Workspace withReferences(List<String> refs) {
    return new Workspace(name, dataDir, autoPush, refs);
  }
}
