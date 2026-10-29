package com.devmap.config;

import java.util.List;

/** One git repo of a project and the branch to pull before any work. */
public record RepoConfig(String name, String path, String baseBranch, List<ServiceInfo> services) {

  public RepoConfig {
    services = services == null ? List.of() : List.copyOf(services);
  }
}
