package com.devmap.cli;

import com.devmap.config.Project;
import com.devmap.config.ProjectService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Parameters;

@Command(name = "workspace", mixinStandardHelpOptions = true, description = "Manage registered projects (~/.devmap/workspaces.yml).")
class WorkspaceCommand implements Runnable {

  @Spec
  CommandSpec spec;

  @Override
  public void run() {
    spec.commandLine().usage(System.out);
  }


  @Command(name = "add", mixinStandardHelpOptions = true,
      description = "Register a project; an existing data folder with config.yml is registered as is.")
  int add(@Mixin ProjectOptions options) {
    return options.create();
  }

  @Command(name = "list", description = "List registered projects.")
  int list() {
    for (Project p : Core.get(ProjectService.class).list()) {
      String detail = p.error() != null ? "LỖI: " + p.error() : p.config().repos().size() + " repo";
      System.out.println(p.name() + "\t" + p.dataDir() + "\t" + detail);
    }
    return 0;
  }

  @Command(name = "remove", description = "Unregister a project. The data folder is kept.")
  int remove(@Parameters(paramLabel = "name") String name) {
    Core.get(ProjectService.class).remove(name);
    System.out.println("Đã bỏ đăng ký " + name + " (folder dữ liệu giữ nguyên)");
    return 0;
  }
}
