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
      return MAPPER.readValue(file.toFile(), type);
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
      JsonNode n = Files.exists(file) ? MAPPER.readTree(file.toFile()) : null;
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

  public static void writeText(Path file, String text) {
    try {
      Files.createDirectories(file.getParent());
      Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
      Files.writeString(tmp, text);
      Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      throw new UncheckedIOException("Không ghi được " + file + ": " + e.getMessage(), e);
    }
  }
}
