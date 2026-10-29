package com.devmap.cli;

import com.devmap.DevMapApplication;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** CLI commands use the same core beans as the server, from a lazily started non-web context. */
final class Core {

  private static ConfigurableApplicationContext context;

  private Core() {
  }

  static synchronized <T> T get(Class<T> type) {
    if (context == null) {
      context = new SpringApplicationBuilder(DevMapApplication.class)
          .web(WebApplicationType.NONE)
          .bannerMode(Banner.Mode.OFF)
          .logStartupInfo(false)
          .properties("logging.level.root=WARN")
          .run();
    }
    return context.getBean(type);
  }

  static synchronized void close() {
    if (context != null) {
      context.close();
      context = null;
    }
  }
}
