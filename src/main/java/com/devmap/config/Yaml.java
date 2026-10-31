package com.devmap.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** snake_case YAML (config.yml, workspaces.yml, task.md front matter), written atomically so a crash never leaves a half-written config. */
public final class Yaml {

  private static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory()
      .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
      .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES))
      .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
      .setSerializationInclusion(JsonInclude.Include.NON_NULL);

  private Yaml() {
  }

  public static <T> T read(Path file, Class<T> type) {
    try {
      return MAPPER.readValue(Files.readAllBytes(file), type);
    } catch (IOException e) {
      throw new UncheckedIOException("Không đọc được " + file + ": " + e.getMessage(), e);
    }
  }

  public static void write(Path file, Object value) {
    writeText(file, toText(value));
  }

  public static String toText(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static JsonNode readTree(Path file) {
    try {
      JsonNode n = Files.exists(file) ? MAPPER.readTree(Files.readAllBytes(file)) : null;
      return n == null || n.isMissingNode() || n.isNull() ? MAPPER.createObjectNode() : n;
    } catch (IOException e) {
      throw new UncheckedIOException("Không đọc được " + file + ": " + e.getMessage(), e);
    }
  }

  public static JsonNode readTree(InputStream in) {
    try {
      return MAPPER.readTree(in);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Object ↔ tree with the same snake_case naming as the files. */
  public static <T> T convert(Object value, Class<T> type) {
    return MAPPER.convertValue(value, type);
  }

  public static JsonNode toTree(Object value) {
    return MAPPER.valueToTree(value);
  }

  public static <T> T parse(String text, Class<T> type) {
    try {
      return MAPPER.readValue(text, type);
    } catch (IOException e) {
      throw new UncheckedIOException("YAML không hợp lệ: " + e.getMessage(), e);
    }
  }

  /**
   * Own temp file per call (two writers never share one), then an atomic replace. Windows refuses to replace a file while anyone
   * has it open (the UI reading chat.json during a session, antivirus, an editor): retried briefly, then the content is written
   * in place, which open readers allow. Losing an agent's answer is worse than a non-atomic write.
   */
  public static void writeText(Path file, String text) {
    Path tmp = null;
    try {
      Files.createDirectories(file.getParent());
      tmp = Files.createTempFile(file.getParent(), file.getFileName() + ".", ".tmp");
      Files.writeString(tmp, text);
      for (int attempt = 1; ; attempt++) {
        try {
          Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
          return;
        } catch (java.nio.file.FileSystemException e) {
          if (attempt == 5) {
            Files.writeString(file, text);
            return;
          }
          Thread.sleep(40L * attempt);
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Không ghi được " + file + ": " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new UncheckedIOException("Không ghi được " + file + ": bị ngắt", new IOException(e));
    } finally {
      if (tmp != null) {
        try {
          Files.deleteIfExists(tmp);
        } catch (IOException ignored) {
          // a stray .tmp is harmless; the target is already written or the error above says why not
        }
      }
    }
  }
}
