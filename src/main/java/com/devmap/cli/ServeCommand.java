package com.devmap.cli;

import com.devmap.DevMapApplication;
import com.devmap.agent.AgentService;
import com.devmap.sync.SyncService;
import com.devmap.web.ServerToken;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.Callable;
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

  @Option(names = "--port", defaultValue = "7070", description = "Port (default: ${DEFAULT-VALUE}).")
  int port;

  @Option(names = "--no-open", description = "Do not open the browser.")
  boolean noOpen;

  @Override
  public Integer call() throws IOException {
    Path urlFile = home().resolve("serve.url");
    if (listening(port) && Files.exists(urlFile)) {
      // already running: the run token only lives in that process, so reuse the link it wrote
      String url = Files.readString(urlFile, StandardCharsets.UTF_8).trim();
      System.out.println("DevMap đang chạy: " + url);
      browse(url);
      return 0;
    }
    ConfigurableApplicationContext ctx = new SpringApplicationBuilder(DevMapApplication.class)
        .bannerMode(Banner.Mode.OFF)
        .properties("server.address=127.0.0.1", "server.port=" + port)
        .run();
    String url = "http://localhost:" + port + "/?token=" + ctx.getBean(ServerToken.class).value();
    Files.createDirectories(urlFile.getParent());
    Files.writeString(urlFile, url, StandardCharsets.UTF_8);
    Runtime.getRuntime().addShutdownHook(new Thread(() -> urlFile.toFile().delete()));
    ctx.getBean(SyncService.class).start(); // FR-48: pull, lock, auto-commit in the background; the UI is already up
    System.out.println();
    System.out.println("DevMap: " + url);
    System.out.println("Ctrl+C to stop.");
    browse(url);
    warnBeforeStop(ctx.getBean(AgentService.class));
    return 0;
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

  private void browse(String url) {
    if (noOpen) {
      return;
    }
    String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
    String[] cmd = os.contains("win") ? new String[] {"rundll32", "url.dll,FileProtocolHandler", url}
        : os.contains("mac") ? new String[] {"open", url} : new String[] {"xdg-open", url};
    try {
      new ProcessBuilder(cmd).start();
    } catch (IOException e) {
      // no browser launcher on this machine: the link above is enough
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
