package com.devmap.cli;

import com.devmap.config.Project;
import com.devmap.sync.SyncService;
import com.devmap.sync.SyncService.Setup;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(name = "data", mixinStandardHelpOptions = true, description = "The DevMap data folder on several machines (FR-46, FR-57).")
class DataCommand implements Runnable {

  @Spec
  CommandSpec spec;

  @Override
  public void run() {
    spec.commandLine().usage(System.out);
  }

  @Command(name = "init-sync", mixinStandardHelpOptions = true,
      description = "Turn the data folder into a git repo, set its remote and push. Use a PRIVATE repo.")
  int initSync(@Parameters(paramLabel = "remote-url") String remote,
      @Option(names = "--dir", description = "Data root (parent of the project folders); default: the common parent.") Path dir) {
    print(Core.get(SyncService.class).initSync(remote, dir));
    return 0;
  }

  @Command(name = "clone", mixinStandardHelpOptions = true, description = "New machine: clone the data repo and register its projects.")
  int cloneData(@Parameters(index = "0", paramLabel = "remote-url") String remote,
      @Parameters(index = "1", arity = "0..1", paramLabel = "path", description = "Default: ~/devmap-data.") Path path) {
    Path target = path != null ? path : Path.of(System.getProperty("user.home"), "devmap-data");
    print(Core.get(SyncService.class).cloneData(remote, target));
    return 0;
  }

  @Command(name = "export", mixinStandardHelpOptions = true, description = "Project data as a zip, without graph/cache/logs.")
  int export(@Parameters(paramLabel = "project") String project,
      @Option(names = "--out", description = "Default: devmap-<project>.zip") Path out) throws IOException {
    Path file = out != null ? out : Path.of("devmap-" + project + ".zip");
    try (OutputStream os = Files.newOutputStream(file)) {
      Core.get(SyncService.class).export(project, os);
    }
    System.out.println("Đã xuất " + project + " → " + file.toAbsolutePath());
    return 0;
  }

  @Command(name = "import", mixinStandardHelpOptions = true, description = "Import a project zip made by `devmap data export`.")
  int importZip(@Parameters(paramLabel = "file.zip") Path file,
      @Option(names = "--data", description = "Data root to import into; default: the synced data root.") Path dataRoot)
      throws IOException {
    try (InputStream in = Files.newInputStream(file)) {
      Project p = Core.get(SyncService.class).importZip(in, dataRoot);
      System.out.println("Đã nhập " + p.name() + " → " + p.dataDir()
          + "\nMở dự án để chọn folder (hoặc clone) cho các repo chưa có trên máy này.");
    }
    return 0;
  }

  private static void print(Setup s) {
    SyncCommand.print(s.status());
    if (!s.projects().isEmpty()) {
      System.out.println("Đã đăng ký dự án: " + String.join(", ", s.projects()));
    }
  }
}
