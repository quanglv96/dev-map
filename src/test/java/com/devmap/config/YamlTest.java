package com.devmap.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class YamlTest {

  @TempDir
  Path tmp;

  /** chat.json is rewritten on every agent step while the UI reads it: an open reader must not make the write fail (Windows). */
  @Test
  void rewriteSucceedsWhileTheFileIsOpenForReading() throws Exception {
    Path f = tmp.resolve("chat.json");
    Yaml.writeText(f, "[]");
    try (InputStream in = Files.newInputStream(f)) { // how DevMap's readers open files (Files.readAllBytes)
      Yaml.writeText(f, "[1]");
    }
    assertThat(Files.readString(f)).isEqualTo("[1]");
    try (Stream<Path> files = Files.list(tmp)) {
      assertThat(files).containsExactly(f); // no temp file left behind
    }
  }
}
