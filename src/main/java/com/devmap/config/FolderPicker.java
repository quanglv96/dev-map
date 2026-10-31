package com.devmap.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Opens the operating system's own folder dialog on the PMO's machine (the server only listens on 127.0.0.1,
 * so it is the same machine as the browser) and returns the absolute path the browser cannot give out.
 */
@Service
public class FolderPicker {

  /** Chosen folder with '/' separators, or null when the PMO cancels. Blocks until the dialog closes. */
  public synchronized String pick(String start) {
    ProcessBuilder pb = new ProcessBuilder(command(start == null ? "" : start));
    pb.environment().put("DEVMAP_START", start == null ? "" : start);
    pb.redirectError(ProcessBuilder.Redirect.DISCARD);
    try {
      Process p = pb.start();
      String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
      if (p.waitFor() != 0 || out.isEmpty()) {
        return null;
      }
      String path = out.replace('\\', '/');
      return path.length() > 1 && path.endsWith("/") && !path.endsWith(":/") ? path.substring(0, path.length() - 1) : path;
    } catch (IOException e) {
      throw new IllegalStateException("Không mở được hộp thoại chọn folder của hệ điều hành: " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    }
  }

  private static List<String> command(String start) {
    String os = System.getProperty("os.name").toLowerCase();
    if (os.contains("win")) {
      return List.of("powershell", "-NoProfile", "-STA", "-NonInteractive", "-EncodedCommand", windowsScript());
    }
    if (os.contains("mac")) {
      return List.of("osascript", "-e", "POSIX path of (choose folder)");
    }
    // ponytail: zenity only (GNOME and most desktops); add kdialog if a KDE-only machine shows up
    return List.of("zenity", "--file-selection", "--directory", "--filename=" + (start.isEmpty() ? "" : start + "/"));
  }

  private static String windowsScript() {
    try (InputStream in = FolderPicker.class.getResourceAsStream("/pick-folder.ps1")) {
      String script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      return Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
