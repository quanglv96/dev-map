package com.devmap.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

/** ~/.devmap/workspaces.yml: which projects exist and where their data folders are. */
@Repository
public class WorkspaceRepository {

  record WorkspacesFile(List<Workspace> projects) {
    WorkspacesFile {
      projects = projects == null ? List.of() : List.copyOf(projects);
    }
  }

  private final Path file;

  public WorkspaceRepository(@Value("${devmap.home:${user.home}/.devmap}") Path home) {
    this.file = home.resolve("workspaces.yml");
  }

  public List<Workspace> findAll() {
    return Files.exists(file) ? Yaml.read(file, WorkspacesFile.class).projects() : List.of();
  }

  public void saveAll(List<Workspace> workspaces) {
    Yaml.write(file, new WorkspacesFile(workspaces));
  }
}
