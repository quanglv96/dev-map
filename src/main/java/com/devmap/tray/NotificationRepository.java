package com.devmap.tray;

import com.devmap.config.Yaml;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

/** ~/.devmap/notifications.yml. A broken file falls back to the defaults: notifications are never worth stopping DevMap for. */
@Repository
public class NotificationRepository {

  private static final Logger LOG = LoggerFactory.getLogger(NotificationRepository.class);

  private final Path file;

  public NotificationRepository(@Value("${devmap.home:${user.home}/.devmap}") Path home) {
    this.file = home.resolve("notifications.yml");
  }

  public NotificationSettings read() {
    if (!Files.exists(file)) {
      return NotificationSettings.defaults();
    }
    try {
      return Yaml.read(file, NotificationSettings.class);
    } catch (UncheckedIOException e) {
      LOG.warn("Không đọc được {}, dùng cấu hình thông báo mặc định: {}", file, e.getMessage());
      return NotificationSettings.defaults();
    }
  }

  public void write(NotificationSettings settings) {
    Yaml.write(file, settings);
  }
}
