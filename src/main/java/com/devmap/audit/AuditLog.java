package com.devmap.audit;

import com.devmap.secret.SecretMasker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * NFR-10: every action of the PMO, the agents and DevMap itself, one JSON line each in {@code <data folder>/logs/audit.jsonl}:
 * time, actor, ticket, repo, service, rule applied, action. Text is masked before it is written.
 */
@Component
public class AuditLog {

  public record Entry(String at, String actor, String ticket, String repo, String service, String rule, String action) {
  }

  /** Published by services that already have an event publisher (tickets, git, agents); written here. */
  public record Event(Path dataDir, String actor, String ticket, String repo, String service, String rule, String action) {
  }

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  private final SecretMasker masker;

  public AuditLog(SecretMasker masker) {
    this.masker = masker;
  }

  @EventListener
  public void on(Event e) {
    add(e.dataDir(), e.actor(), e.ticket(), e.repo(), e.service(), e.rule(), e.action());
  }

  /** ticket, repo, service and rule may be null. */
  public void add(Path dataDir, String actor, String ticket, String repo, String service, String rule, String action) {
    Entry e = new Entry(LocalDateTime.now().format(TIME), actor, ticket, repo, service, rule, masker.mask(action).text());
    try {
      String line = JSON.writeValueAsString(e) + "\n";
      synchronized (this) {
        Files.createDirectories(dataDir.resolve("logs"));
        Files.writeString(file(dataDir), line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
      }
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }

  /** Newest first; ticket null = all. */
  // ponytail: reads the whole file; rotate per month if a project's log grows past a few MB
  public List<Entry> read(Path dataDir, String ticket, int limit) {
    Path f = file(dataDir);
    if (!Files.exists(f)) {
      return List.of();
    }
    List<String> lines;
    try {
      lines = Files.readAllLines(f, StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
    List<Entry> out = new ArrayList<>();
    for (int i = lines.size() - 1; i >= 0 && out.size() < limit; i--) {
      try {
        Entry e = JSON.readValue(lines.get(i), Entry.class);
        if (ticket == null || ticket.equals(e.ticket())) {
          out.add(e);
        }
      } catch (JsonProcessingException ex) {
        // a line cut by a crash: skip it
      }
    }
    return out;
  }

  private static Path file(Path dataDir) {
    return dataDir.resolve("logs").resolve("audit.jsonl");
  }
}
