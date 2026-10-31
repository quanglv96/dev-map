package com.devmap.graph;

import com.devmap.config.Yaml;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * graph/&lt;repo&gt;.json per repo (regenerable cache, compact JSON) and manual-edges.json at the project root
 * (PMO data, kept out of graph/ so deleting the cache never loses it).
 */
@Repository
public class GraphRepository {

  private static final ObjectMapper JSON = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

  public Optional<RepoIndex> load(Path projectDir, String repo) {
    Path f = file(projectDir, repo);
    if (!Files.exists(f)) {
      return Optional.empty();
    }
    try {
      return Optional.of(JSON.readValue(Files.readAllBytes(f), RepoIndex.class));
    } catch (IOException e) {
      return Optional.empty(); // unreadable cache = not indexed; the PMO can rescan
    }
  }

  public void save(Path projectDir, RepoIndex index) {
    try {
      Yaml.writeText(file(projectDir, index.repo()), JSON.writeValueAsString(index));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public List<ManualEdge> manualEdges(Path projectDir) {
    Path f = projectDir.resolve("manual-edges.json");
    try {
      return Files.exists(f) ? JSON.readValue(Files.readAllBytes(f), new TypeReference<List<ManualEdge>>() { }) : List.of();
    } catch (IOException e) {
      throw new UncheckedIOException("manual-edges.json hỏng: " + e.getMessage(), e);
    }
  }

  public void saveManualEdges(Path projectDir, List<ManualEdge> edges) {
    try {
      Yaml.writeText(projectDir.resolve("manual-edges.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(edges));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Path file(Path projectDir, String repo) {
    return projectDir.resolve("graph").resolve(repo + ".json");
  }
}
