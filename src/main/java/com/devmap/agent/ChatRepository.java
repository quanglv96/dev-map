package com.devmap.agent;

import com.devmap.config.Yaml;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.stereotype.Repository;

/** chat.json of a ticket (kept: the PMO reads it back) and the session scratch folders outside the data folder (purged, FR-45). */
@Repository
public class ChatRepository {

  private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

  public List<ChatMessage> read(Path ticketFolder) {
    Path f = ticketFolder.resolve("chat.json");
    try {
      return Files.exists(f) ? JSON.readValue(f.toFile(), new TypeReference<List<ChatMessage>>() { }) : List.of();
    } catch (IOException e) {
      throw new UncheckedIOException("chat.json hỏng: " + e.getMessage(), e);
    }
  }

  public void write(Path ticketFolder, List<ChatMessage> messages) {
    try {
      Yaml.writeText(ticketFolder.resolve("chat.json"), JSON.writeValueAsString(messages) + "\n");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Appends to a session's input log (text is already masked by the caller). */
  public void log(Path log, String text) {
    try {
      Files.createDirectories(log.getParent());
      Files.writeString(log, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public void deleteTree(Path dir) {
    if (!Files.exists(dir)) {
      return;
    }
    try (Stream<Path> files = Files.walk(dir)) {
      for (Path f : files.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(f);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Không xoá được " + dir, e);
    }
  }
}
