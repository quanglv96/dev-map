package com.devmap.rule;

import com.devmap.config.Yaml;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Repository;

/**
 * rules/_default.devrules.yml (project default) and rules/&lt;service&gt;.devrules.yml in the data folder, plus the
 * project's conventions.md. Rule files only hold what differs from the level above.
 */
@Repository
public class RuleRepository {

  public static final String DEFAULT = "_default";

  public JsonNode read(Path projectDir, String level) {
    return Yaml.readTree(file(projectDir, level));
  }

  public void write(Path projectDir, String level, JsonNode tree) {
    Yaml.write(file(projectDir, level), tree);
  }

  public void delete(Path projectDir, String level) {
    try {
      Files.deleteIfExists(file(projectDir, level));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public String readConventions(Path projectDir) {
    Path f = projectDir.resolve("conventions.md");
    try {
      return Files.exists(f) ? Files.readString(f).replace("\r\n", "\n") : "";
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public void writeConventions(Path projectDir, String text) {
    Yaml.writeText(projectDir.resolve("conventions.md"), text);
  }

  private static Path file(Path projectDir, String level) {
    return projectDir.resolve("rules").resolve(level + ".devrules.yml");
  }
}
