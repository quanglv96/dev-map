package com.devmap.tray;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * FR-70: DevMap.lnk in the current user's Startup folder, which needs neither the registry nor admin rights. IShellLinkW writes the
 * .lnk without a new library: WScript.Shell squeezes paths into the ANSI code page and fails on Vietnamese ones. The paths reach
 * PowerShell as environment variables so no path can break the script.
 */
@Component
public class StartupShortcut {

  /** The empty methods only keep the COM vtable order; they are never called. */
  private static final String SCRIPT = """
      $ErrorActionPreference='Stop'
      Add-Type -TypeDefinition '
      using System.Runtime.InteropServices;
      using System.Runtime.InteropServices.ComTypes;
      [ComImport, Guid("00021401-0000-0000-C000-000000000046")] class ShellLink {}
      [ComImport, Guid("000214F9-0000-0000-C000-000000000046"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
      interface IShellLinkW {
        void GetPath(); void GetIDList(); void SetIDList();
        void GetDescription(); void SetDescription([MarshalAs(UnmanagedType.LPWStr)] string value);
        void GetWorkingDirectory(); void SetWorkingDirectory([MarshalAs(UnmanagedType.LPWStr)] string value);
        void GetArguments(); void SetArguments([MarshalAs(UnmanagedType.LPWStr)] string value);
        void GetHotkey(); void SetHotkey(); void GetShowCmd(); void SetShowCmd();
        void GetIconLocation(); void SetIconLocation(); void SetRelativePath(); void Resolve();
        void SetPath([MarshalAs(UnmanagedType.LPWStr)] string value);
      }
      public static class DevMapLink {
        public static void Save(string link, string target, string arguments, string directory) {
          IShellLinkW shell = (IShellLinkW) new ShellLink();
          shell.SetPath(target);
          shell.SetArguments(arguments);
          shell.SetWorkingDirectory(directory);
          shell.SetDescription("DevMap");
          ((IPersistFile) shell).Save(link, true);
        }
      }'
      [DevMapLink]::Save($env:DEVMAP_LNK,$env:DEVMAP_TARGET,$env:DEVMAP_ARGS,$env:DEVMAP_DIR)
      """;

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
    // Encoded because the script holds double quotes, which do not survive the Windows command line.
    String encoded = Base64.getEncoder().encodeToString(SCRIPT.getBytes(StandardCharsets.UTF_16LE));
    ProcessBuilder pb = new ProcessBuilder(List.of("powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded));
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
