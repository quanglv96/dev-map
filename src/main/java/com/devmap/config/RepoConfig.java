package com.devmap.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * One git repo of a project and the branch to pull before any work. config.yml stores name and remote only; path is where the
 * repo lives on this machine (~/.devmap/workspaces.yml, FR-51), null until the PMO picks or clones it here.
 */
public record RepoConfig(String name, String path, String baseBranch, List<ServiceInfo> services, String remote) {

  public RepoConfig {
    services = services == null ? List.of() : List.copyOf(services);
  }

  public RepoConfig(String name, String path, String baseBranch, List<ServiceInfo> services) {
    this(name, path, baseBranch, services, null);
  }

  /** Key of this repo in the machine's path map: the normalized remote, or project/name for a repo without one. */
  public String key(String project) {
    return remote == null || remote.isBlank() ? project + "/" + name : normalize(remote);
  }

  /** git@github.com:acme/x.git, https://github.com/acme/x and ssh://git@github.com/acme/x/ all give github.com/acme/x. */
  public static String normalize(String remote) {
    String r = remote.strip().replace('\\', '/');
    r = r.replaceFirst("^[a-zA-Z][a-zA-Z0-9+.-]*://", "").replaceFirst("^[^@/]+@", "");
    if (!remote.contains("://")) {
      r = r.replaceFirst("^([^/:]+):(?!/)", "$1/"); // scp-like host:path
    }
    r = r.replaceFirst("/+$", "").replaceFirst("\\.git$", "");
    int slash = r.indexOf('/');
    return slash < 0 ? r.toLowerCase(Locale.ROOT) : r.substring(0, slash).toLowerCase(Locale.ROOT) + r.substring(slash);
  }

  /** The repo folder on this machine; a clear error instead of an NPE when it was not picked here yet. */
  public Path dir() {
    if (path == null || path.isBlank()) {
      throw new IllegalStateException("Repo " + name + " chưa có đường dẫn trên máy này: mở dự án để chọn folder hoặc clone");
    }
    return Path.of(path);
  }

  public RepoConfig withPath(String p) {
    return new RepoConfig(name, p, baseBranch, services, remote);
  }

  public RepoConfig withRemote(String r) {
    return new RepoConfig(name, path, baseBranch, services, r);
  }
}
