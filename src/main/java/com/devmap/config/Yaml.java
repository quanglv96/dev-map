package com.devmap.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** snake_case YAML files, written atomically so a crash never leaves a half-written config. */
final class Yaml {

  private static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory()
      .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
      .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES))
      .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
      .setSerializationInclusion(JsonInclude.Include.NON_NULL);

  private Yaml() {
  }

  static <T> T read(Path file, Class<T> type) {
    try {
      return MAPPER.readValue(file.toFile(), type);
    } catch (IOException e) {
      throw new UncheckedIOException("Không đọc được " + file + ": " + e.getMessage(), e);
    }
  }

  static void write(Path file, Object value) {
    try {
      writeText(file, MAPPER.writeValueAsString(value));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static void writeText(Path file, String text) {
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
