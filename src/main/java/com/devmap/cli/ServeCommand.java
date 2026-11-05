package com.devmap.cli;

import com.devmap.DevMapApplication;
import com.devmap.agent.AgentService;
import com.devmap.graph.GraphService;
import com.devmap.sync.SyncService;
import com.devmap.tray.BrowserOpener;
import com.devmap.tray.TrayService;
import com.devmap.web.EventController;
import com.devmap.web.ServerToken;
import com.devmap.web.TabWatcher;
import com.devmap.web.TokenFilter;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.swing.JOptionPane;
import org.springframework.boot.Banner;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import sun.misc.Signal;

/**
 * Starts the localhost UI/API and opens it in the browser; plain `devmap` runs this too.
 * Nothing is scanned or indexed on startup (AIR-05); the data repo (if synced) is pulled in the background (FR-48).
 */
@Command(name = "serve", mixinStandardHelpOptions = true, description = "Start the DevMap web UI on 127.0.0.1.")
class ServeCommand implements Callable<Integer> {

  private static final long TAB_CHECK_SECONDS = 5;
  private static final int START_WAIT_SECONDS = 60;
  private static final long MAX_LOG_BYTES = 5_000_000;
  private static FileLock instanceLock;

  @Option(names = "--port", defaultValue = "7070", description = "Port (default: ${DEFAULT-VALUE}).")
  int port;

  @Option(names = "--no-open", description = "Do not open the browser.")
  boolean noOpen;

  @Option(names = "--keep-running", description = "Keep running after the last DevMap tab is closed.")
  boolean keepRunning;

  @Option(names = "--no-tray", description = "No system tray icon or notifications.")
  boolean noTray;

  @Option(names = "--background", hidden = true, description = "Started with javaw: no console, so output goes to a log file.")
  boolean background;

  @Override
  public Integer call() throws IOException {
    Path urlFile = home().resolve("serve.url");
    if (listening(port) && Files.exists(urlFile)) {
      return openRunning(urlFile);
    }
    if (!holdSingleInstanceLock()) {
      return waitForRunning(urlFile);
    }
    if (background) {
      logToFile();
    }
    ConfigurableApplicationContext ctx;
    try {
      ctx = new SpringApplicationBuilder(DevMapApplication.class)
          .bannerMode(Banner.Mode.OFF)
          .headless(noTray || noDisplay()) // Spring Boot defaults to headless, where SystemTray does not exist
          // the browser revalidates every page and module, so a rebuilt devmap.jar never shows the previous UI
          .properties("server.address=127.0.0.1", "server.port=" + port, "spring.web.resources.cache.cachecontrol.no-cache=true")
          .run();
    } catch (RuntimeException e) {
      tell("DevMap không khởi động được ở cổng " + port + ": " + e.getMessage());
      throw e;
    }
    String url = "http://localhost:" + port + "/";
    Files.createDirectories(urlFile.getParent());
    // the token stays in serve.url only, for the next `devmap` to ask this server to open the page (D-67)
    Files.writeString(urlFile, url + "?token=" + ctx.getBean(ServerToken.class).value() + "\n" + build() + "\n",
        StandardCharsets.UTF_8);
    Runtime.getRuntime().addShutdownHook(new Thread(() -> urlFile.toFile().delete()));
    ctx.getBean(SyncService.class).start(); // FR-48: pull, lock, auto-commit in the background; the UI is already up
    TrayService tray = ctx.getBean(TrayService.class);
    boolean hasTray = !noTray && tray.start(() -> System.exit(0));
    boolean stopsWithTabs = !keepRunning && !hasTray; // D-61: with an icon DevMap must still notify after the browser is closed
    System.out.println();
    System.out.println("DevMap: " + url);
    System.out.println(hasTray ? "Ctrl+C or the tray menu to stop."
        : stopsWithTabs ? "Ctrl+C to stop, or close every DevMap tab." : "Ctrl+C to stop.");
    if (!noOpen) {
      tray.open("/");
    }
    warnBeforeStop(ctx.getBean(AgentService.class));
    if (stopsWithTabs) {
      exitWhenTabsClosed(ctx);
    }
    return 0;
  }

  /** The run token only lives in the running process; it wrote its link to serve.url, and it opens the page itself (FR-68). */
  private int openRunning(Path urlFile) throws IOException {
    List<String> lines = Files.readAllLines(urlFile, StandardCharsets.UTF_8);
    String url = lines.isEmpty() ? "" : lines.get(0).trim();
    if (lines.size() < 2 || !lines.get(1).trim().equals(build())) {
      // an older build (or one from another folder) would show its old UI: say so instead of opening it
      tell("DevMap khác bản này đang chạy: " + url.replaceAll("\\?.*", "")
          + "\nTắt DevMap đó (menu khay hoặc Ctrl+C) rồi chạy lại `devmap` để dùng bản " + build());
      return 1;
    }
    System.out.println("DevMap đang chạy: " + url.replaceAll("[?].*", ""));
    if (!noOpen && !askToOpen(url)) {
      BrowserOpener.open(url.replaceAll("[?].*", "")); // works while the browser still holds this run's cookie
    }
    return 0;
  }

  /** Another DevMap holds the lock: it may still be starting, so give it time to write its link. */
  private int waitForRunning(Path urlFile) throws IOException {
    for (int i = 0; i < START_WAIT_SECONDS * 2; i++) {
      if (Files.exists(urlFile)) {
        return openRunning(urlFile);
      }
      try {
        Thread.sleep(500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    tell("DevMap khác đang khởi động nhưng chưa sẵn sàng. Thử lại sau ít phút.");
    return 1;
  }

  /** FR-68: the one server; an OS file lock also goes away if that server crashes, unlike a marker file. Held until the JVM exits. */
  private static boolean holdSingleInstanceLock() throws IOException {
    Files.createDirectories(home());
    FileChannel channel = FileChannel.open(home().resolve("serve.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    try {
      instanceLock = channel.tryLock();
    } catch (OverlappingFileLockException e) {
      instanceLock = null;
    }
    return instanceLock != null;
  }

  private static boolean askToOpen(String url) {
    try {
      URI link = URI.create(url);
      String token = link.getQuery().substring("token=".length());
      HttpRequest request = HttpRequest.newBuilder(link.resolve("/api/tray/open")).header(TokenFilter.HEADER, token)
          .timeout(Duration.ofSeconds(5)).POST(HttpRequest.BodyPublishers.noBody()).build();
      return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 204;
    } catch (IOException | RuntimeException e) {
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /** FR-69: javaw has no console. One file, UTF-8, started over at 5 MB keeping the previous one. */
  private static void logToFile() throws IOException {
    Path logs = home().resolve("logs");
    Files.createDirectories(logs);
    Path log = logs.resolve("devmap.log");
    if (Files.exists(log) && Files.size(log) > MAX_LOG_BYTES) {
      Files.move(log, logs.resolve("devmap.log.1"), StandardCopyOption.REPLACE_EXISTING);
    }
    PrintStream out = new PrintStream(new FileOutputStream(log.toFile(), true), true, StandardCharsets.UTF_8);
    System.setOut(out);
    System.setErr(out);
  }

  /** Linux without a display: asking AWT for a tray would throw. Windows and macOS always have one. */
  private static boolean noDisplay() {
    return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("linux")
        && System.getenv("DISPLAY") == null && System.getenv("WAYLAND_DISPLAY") == null;
  }

  /** A message the PMO must see: in the background there is no console, so it needs a dialog. */
  private void tell(String message) {
    System.out.println(message);
    if (background && !GraphicsEnvironment.isHeadless()) {
      JOptionPane.showMessageDialog(null, message, "DevMap", JOptionPane.WARNING_MESSAGE);
    }
  }

  /** Closing the last tab stops DevMap, but only once agents and index jobs are done (SRS: closing a tab does not stop agents). */
  private static void exitWhenTabsClosed(ConfigurableApplicationContext ctx) {
    EventController events = ctx.getBean(EventController.class);
    AgentService agents = ctx.getBean(AgentService.class);
    GraphService graph = ctx.getBean(GraphService.class);
    TabWatcher watcher = new TabWatcher();
    Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "devmap-tabs");
      t.setDaemon(true);
      return t;
    }).scheduleWithFixedDelay(() -> {
      if (watcher.shouldExit(events.pingTabs(), agents.runningCount() > 0 || graph.indexing(), System.currentTimeMillis())) {
        System.out.println("Đã đóng hết tab DevMap: tắt server.");
        System.exit(0);
      }
    }, TAB_CHECK_SECONDS, TAB_CHECK_SECONDS, TimeUnit.SECONDS);
  }

  /** This build: the jar (first class path entry) and its modification time. */
  static String build() {
    Path jar = Path.of(System.getProperty("java.class.path").split(File.pathSeparator)[0]).toAbsolutePath();
    try {
      return jar + "@" + Files.getLastModifiedTime(jar).toInstant();
    } catch (IOException e) {
      return jar.toString();
    }
  }

  private static Path home() {
    return Path.of(System.getProperty("devmap.home", System.getProperty("user.home") + "/.devmap"));
  }

  private static boolean listening(int port) {
    try (Socket s = new Socket()) {
      s.connect(new InetSocketAddress("127.0.0.1", port), 300);
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  /** SRS: stopping `devmap serve` while agents run needs a warning; a second Ctrl+C within 10 s stops anyway. */
  private static void warnBeforeStop(AgentService agents) {
    long[] warned = {0};
    try {
      Signal.handle(new Signal("INT"), sig -> {
        int n = agents.runningCount();
        if (n > 0 && System.currentTimeMillis() - warned[0] > 10_000) {
          warned[0] = System.currentTimeMillis();
          System.out.println();
          System.out.println("Còn " + n + " phiên agent đang chạy hoặc chờ lượt; tắt bây giờ sẽ dừng giữa chừng."
              + " Bấm Ctrl+C lần nữa trong 10 giây để vẫn tắt.");
          return;
        }
        System.exit(0);
      });
    } catch (IllegalArgumentException e) {
      // the JVM does not let us handle Ctrl+C here: default stop, no warning
    }
  }
}
