package com.devmap.config;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

/**
 * ~/.devmap/workspaces.yml, never synced: which projects exist and where their data folders are, the synced data root, this
 * machine's name, sync settings and where each project repo lives on this machine (FR-51).
 */
@Repository
public class WorkspaceRepository {

  /** auto_push: push the data repo without asking (FR-49, default true: it only holds masked documents). */
  public record SyncSettings(Boolean autoPush) {
    public SyncSettings {
      autoPush = autoPush == null ? Boolean.TRUE : autoPush;
    }
  }

  record WorkspacesFile(String dataDir, String machine, SyncSettings sync, Map<String, String> repos, List<Workspace> projects) {
    WorkspacesFile {
      sync = sync == null ? new SyncSettings(null) : sync;
      repos = repos == null ? Map.of() : new TreeMap<>(repos);
      projects = projects == null ? List.of() : List.copyOf(projects);
    }
  }

  private final Path file;

  public WorkspaceRepository(@Value("${devmap.home:${user.home}/.devmap}") Path home) {
    this.file = home.resolve("workspaces.yml");
  }

  private WorkspacesFile read() {
    return Files.exists(file) ? Yaml.read(file, WorkspacesFile.class) : new WorkspacesFile(null, null, null, null, null);
  }

  public List<Workspace> findAll() {
    return read().projects();
  }

  public synchronized void saveAll(List<Workspace> workspaces) {
    WorkspacesFile f = read();
    Yaml.write(file, new WorkspacesFile(f.dataDir(), f.machine(), f.sync(), f.repos(), workspaces));
  }

  public String dataDir() {
    return read().dataDir();
  }

  public synchronized void setDataDir(String dir) {
    WorkspacesFile f = read();
    Yaml.write(file, new WorkspacesFile(dir, f.machine(), f.sync(), f.repos(), f.projects()));
  }

  /** machine in workspaces.yml, else the host name. */
  public String machine() {
    String m = read().machine();
    if (m != null && !m.isBlank()) {
      return m;
    }
    String env = System.getenv("COMPUTERNAME");
    try {
      return env != null ? env : InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      return "may-khong-ten";
    }
  }

  public synchronized void setMachine(String name) {
    WorkspacesFile f = read();
    Yaml.write(file, new WorkspacesFile(f.dataDir(), name, f.sync(), f.repos(), f.projects()));
  }

  public boolean autoPush() {
    return read().sync().autoPush();
  }

  public synchronized void setAutoPush(boolean on) {
    WorkspacesFile f = read();
    Yaml.write(file, new WorkspacesFile(f.dataDir(), f.machine(), new SyncSettings(on), f.repos(), f.projects()));
  }

  public String repoPath(String key) {
    return read().repos().get(key);
  }

  public synchronized void putRepoPaths(Map<String, String> paths) {
    WorkspacesFile f = read();
    Map<String, String> all = new TreeMap<>(f.repos());
    all.putAll(paths);
    Yaml.write(file, new WorkspacesFile(f.dataDir(), f.machine(), f.sync(), all, f.projects()));
  }
}
