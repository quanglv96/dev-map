package com.devmap.tray;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * FR-70: DevMap.lnk in the current user's Startup folder, which needs neither the registry nor admin rights. WScript.Shell writes the
 * .lnk without a new library; the paths reach PowerShell as environment variables so no path can break the script.
 */
@Component
public class StartupShortcut {

  private static final String SCRIPT = "$l=(New-Object -ComObject WScript.Shell).CreateShortcut($env:DEVMAP_LNK);"
      + "$l.TargetPath=$env:DEVMAP_TARGET;$l.Arguments=$env:DEVMAP_ARGS;$l.WorkingDirectory=$env:DEVMAP_DIR;"
      + "$l.Description='DevMap';$l.Save()";

  private final Path folder;

  @Autowired
  public StartupShortcut() {
    this(windowsStartupFolder());
  }

  StartupShortcut(Path folder) {
    this.folder = folder;
  }

  /** Null off Windows: the menu item is then disabled. */
  private static Path windowsStartupFolder() {
    String appData = System.getenv("APPDATA");
    boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    return windows && appData != null ? Path.of(appData, "Microsoft", "Windows", "Start Menu", "Programs", "Startup") : null;
  }

  public boolean supported() {
    return folder != null;
  }

  public boolean enabled() {
    return folder != null && Files.exists(link());
  }

  /** Starts in the background and without opening a browser: after logging in the PMO gets the icon, not a tab. */
  static String arguments(String jar, int port) {
    return "-jar \"" + jar + "\" serve --background --no-open --port " + port;
  }

  public void set(boolean on, int port) throws IOException {
    if (folder == null) {
      throw new IOException("Chỉ có trên Windows");
    }
    if (!on) {
      Files.deleteIfExists(link());
      return;
    }
    String jar = Path.of(System.getProperty("java.class.path").split(File.pathSeparator)[0]).toAbsolutePath().toString();
    if (!jar.endsWith(".jar")) {
      throw new IOException("DevMap không chạy từ devmap.jar nên không tạo được shortcut");
    }
    write(jar, Path.of(System.getProperty("java.home"), "bin", "javaw.exe").toString(), port);
  }

  void write(String jar, String javaw, int port) throws IOException {
    Files.createDirectories(folder);
    ProcessBuilder pb = new ProcessBuilder(List.of("powershell", "-NoProfile", "-NonInteractive", "-Command", SCRIPT));
    Map<String, String> env = pb.environment();
    env.put("DEVMAP_LNK", link().toString());
    env.put("DEVMAP_TARGET", javaw);
    env.put("DEVMAP_ARGS", arguments(jar, port));
    env.put("DEVMAP_DIR", System.getProperty("user.home"));
    pb.redirectErrorStream(true);
    pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
    Process p = pb.start();
    try {
      if (!p.waitFor(30, TimeUnit.SECONDS)) {
        p.destroyForcibly();
        throw new IOException("Tạo shortcut quá lâu");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Bị ngắt khi tạo shortcut", e);
    }
    if (p.exitValue() != 0 || !Files.exists(link())) {
      throw new IOException("PowerShell không tạo được shortcut (mã " + p.exitValue() + ")");
    }
  }

  private Path link() {
    return folder.resolve("DevMap.lnk");
  }
}
