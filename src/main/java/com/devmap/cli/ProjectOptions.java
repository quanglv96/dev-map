package com.devmap.cli;

import com.devmap.config.Project;
import com.devmap.config.ProjectService;
import com.devmap.config.ProjectService.RepoInput;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** Shared by `devmap init` and `devmap workspace add`. */
class ProjectOptions {

  @Parameters(index = "0", description = "Project name, e.g. nyxhub (lowercase, digits, '-').")
  String name;

  @Option(names = "--data", required = true, description = "Data root folder; project data goes to <data>/<name>.")
  Path dataRoot;

  @Option(names = "--repo", description = "Repo as path[@baseBranch] (default branch: main). Repeatable.")
  List<String> repos = new ArrayList<>();

  @Option(names = "--color", defaultValue = "#2563eb", description = "Project color #rrggbb.")
  String color;

  @Option(names = "--git", description = "Also make the data folder a git repo.")
  boolean git;

  int create() {
    List<RepoInput> inputs = repos.stream().map(RepoInput::parse).toList();
    Project p = Core.get(ProjectService.class).create(name, dataRoot, color, inputs, git);
    System.out.println("Đã đăng ký dự án " + p.name() + " -> " + p.dataDir());
    p.config().repos().forEach(r -> System.out.println("  " + r.name() + " @" + r.baseBranch() + ": "
        + r.services().stream().map(s -> s.name()).toList()));
    return 0;
  }
}
