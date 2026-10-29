package com.devmap.cli;

import com.devmap.DevMapApplication;
import com.devmap.web.ServerToken;
import java.util.concurrent.Callable;
import org.springframework.boot.Banner;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

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
    return 0;
  }
}
