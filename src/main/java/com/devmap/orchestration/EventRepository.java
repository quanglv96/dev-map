package com.devmap.orchestration;

import com.devmap.config.Yaml;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Repository;

/** events.json of a ticket: the durable per-ticket event queue (FR-82). Written atomically like every data file. */
@Repository
public class EventRepository {

  private static final ObjectMapper JSON = new ObjectMapper()
      .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
      .enable(SerializationFeature.INDENT_OUTPUT);
  // ponytail: done events stay in the file; trim to the last N when a ticket ever collects thousands
  static final String FILE = "events.json";

  public List<SyncEvent> read(Path ticketFolder) {
    Path f = ticketFolder.resolve(FILE);
    try {
      return Files.exists(f) ? JSON.readValue(Files.readAllBytes(f), new TypeReference<List<SyncEvent>>() { }) : List.of();
    } catch (IOException e) {
      throw new UncheckedIOException("events.json hỏng: " + e.getMessage(), e);
    }
  }

  public synchronized void append(Path ticketFolder, SyncEvent e) {
    List<SyncEvent> all = new ArrayList<>(read(ticketFolder));
    all.add(e);
    write(ticketFolder, all);
  }

  /** Replaces the event with this id; returns the new one, or null when it is gone. */
  public synchronized SyncEvent update(Path ticketFolder, String id, UnaryOperator<SyncEvent> change) {
    List<SyncEvent> all = new ArrayList<>(read(ticketFolder));
    SyncEvent out = null;
    for (int i = 0; i < all.size(); i++) {
      if (all.get(i).id().equals(id)) {
        out = change.apply(all.get(i));
        all.set(i, out);
      }
    }
    if (out != null) {
      write(ticketFolder, all);
    }
    return out;
  }

  private static void write(Path ticketFolder, List<SyncEvent> all) {
    try {
      Yaml.writeText(ticketFolder.resolve(FILE), JSON.writeValueAsString(all) + "\n");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
