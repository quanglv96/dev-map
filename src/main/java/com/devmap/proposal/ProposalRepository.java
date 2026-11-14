package com.devmap.proposal;

import com.devmap.config.Yaml;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.stereotype.Repository;

/** {@code proposals/<id>.json} in a ticket folder; the id is the file name, so the folder lists them in time order. */
@Repository
public class ProposalRepository {

  private static final ObjectMapper JSON = new ObjectMapper()
      .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
      .enable(SerializationFeature.INDENT_OUTPUT);

  public List<Proposal> list(Path ticketFolder) {
    Path dir = ticketFolder.resolve("proposals");
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    try (Stream<Path> files = Files.list(dir)) {
      return files.filter(f -> f.getFileName().toString().endsWith(".json")).sorted().map(ProposalRepository::read).toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public Optional<Proposal> find(Path ticketFolder, String id) {
    Path f = ticketFolder.resolve("proposals").resolve(id + ".json");
    return id.matches("[A-Za-z0-9_-]+") && Files.isRegularFile(f) ? Optional.of(read(f)) : Optional.empty();
  }

  public boolean exists(Path ticketFolder, String id) {
    return Files.exists(ticketFolder.resolve("proposals").resolve(id + ".json"));
  }

  public void write(Path ticketFolder, Proposal p) {
    try {
      Yaml.writeText(ticketFolder.resolve("proposals").resolve(p.id() + ".json"), JSON.writeValueAsString(p) + "\n");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Proposal read(Path f) {
    try {
      return JSON.readValue(Files.readAllBytes(f), Proposal.class);
    } catch (IOException e) {
      throw new UncheckedIOException("Đề xuất hỏng " + f.getFileName() + ": " + e.getMessage(), e);
    }
  }
}
