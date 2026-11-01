package com.devmap.tray;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Default browser: Desktop.browse where it works, the OS launcher otherwise (a JVM started headless has no Desktop). */
public final class BrowserOpener {

  private static final Logger LOG = LoggerFactory.getLogger(BrowserOpener.class);

  private BrowserOpener() {
  }

  /** On its own thread, never the AWT or a server thread: Desktop.browse can take seconds. */
  public static void open(String url) {
    new Thread(() -> browse(url), "devmap-browser").start();
  }

  private static void browse(String url) {
    try {
      if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
        Desktop.getDesktop().browse(URI.create(url));
        return;
      }
    } catch (IOException | UnsupportedOperationException | SecurityException e) {
      LOG.debug("Desktop.browse không dùng được, thử lệnh hệ điều hành: {}", e.toString());
    }
    String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
    String[] cmd = os.contains("win") ? new String[] {"rundll32", "url.dll,FileProtocolHandler", url}
        : os.contains("mac") ? new String[] {"open", url} : new String[] {"xdg-open", url};
    try {
      new ProcessBuilder(cmd).start();
    } catch (IOException e) {
      LOG.warn("Không mở được trình duyệt"); // never the URL: it logs the browser in
    }
  }
}
