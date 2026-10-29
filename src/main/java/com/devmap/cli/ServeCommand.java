package com.devmap.cli;

import com.devmap.DevMapApplication;
import com.devmap.agent.AgentService;
import com.devmap.web.ServerToken;
import java.util.concurrent.Callable;
import org.springframework.boot.Banner;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import sun.misc.Signal;

/** Starts the localhost UI/API. Nothing is scanned or indexed on startup (AIR-05). */
@Command(name = "serve", mixinStandardHelpOptions = true, description = "Start the DevMap web UI on 127.0.0.1.")
class ServeCommand implements Callable<Integer> {

  @Option(names = "--port", defaultValue = "7070", description = "Port (default: ${DEFAULT-VALUE}).")
  int port;

  @Override
  public Integer call() {
    ConfigurableApplicationContext ctx = new SpringApplicationBuilder(DevMapApplication.class)
        .bannerMode(Banner.Mode.OFF)
        .properties("server.address=127.0.0.1", "server.port=" + port)
        .run();
    String token = ctx.getBean(ServerToken.class).value();
    System.out.println();
    System.out.println("DevMap: http://localhost:" + port + "/?token=" + token);
    System.out.println("Ctrl+C to stop.");
    warnBeforeStop(ctx.getBean(AgentService.class));
    return 0;
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
