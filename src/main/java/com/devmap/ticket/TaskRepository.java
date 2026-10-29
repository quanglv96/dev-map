package com.devmap.ticket;

import com.devmap.config.Yaml;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.stereotype.Repository;

/**
 * Files of the tickets in {@code <data>/<project>/tasks/}. Writes text as given: callers (TaskService) mask secrets first.
 * Old versions of analysis.md / plan.md are kept as {@code versions/<doc>.v<N>.md}.
 */
@Repository
public class TaskRepository {

  private static final ObjectMapper JSON = new ObjectMapper()
      .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
      .enable(SerializationFeature.INDENT_OUTPUT);
  private static final String FRONT = "---\n";
  private static final Pattern BUG_DOC = Pattern.compile("bugs/[A-Z]+-\\d{2,}\\.md");

  record Queue(List<String> order) {
    Queue {
      order = order == null ? List.of() : List.copyOf(order);
    }
  }

  /** A ticket and the folder it lives in. */
  public record Stored(Path folder, Task task) {
  }

  public List<Stored> list(Path projectDir) {
    Path tasks = projectDir.resolve("tasks");
    if (!Files.isDirectory(tasks)) {
      return List.of();
    }
    try (Stream<Path> dirs = Files.list(tasks)) {
      return dirs.filter(d -> Files.isRegularFile(d.resolve("task.md"))).sorted()
          .map(d -> new Stored(d, readTask(d))).toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public Optional<Stored> find(Path projectDir, String id) {
    return list(projectDir).stream().filter(s -> s.task().id().equals(id)).findFirst();
  }

  public Task readTask(Path folder) {
    String text = read(folder.resolve("task.md"));
    int end = text.indexOf("\n" + FRONT.trim(), FRONT.length());
    if (!text.startsWith(FRONT) || end < 0) {
      throw new IllegalStateException("task.md thiếu phần đầu YAML: " + folder);
    }
    return Yaml.parse(text.substring(FRONT.length(), end + 1), Task.class);
  }

  /** The Markdown body of task.md (the original request), without the YAML header. */
  public String readTaskBody(Path folder) {
    String text = read(folder.resolve("task.md"));
    int end = text.indexOf("\n" + FRONT, FRONT.length());
    return end < 0 ? "" : text.substring(end + 1 + FRONT.length());
  }

  public void writeTask(Path folder, Task task, String body) {
    Yaml.writeText(folder.resolve("task.md"), FRONT + Yaml.toText(task) + FRONT + body);
  }

  public void writeSpec(Path folder, Spec spec) {
    try {
      Yaml.writeText(folder.resolve("spec.json"), JSON.writeValueAsString(spec) + "\n");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public Spec readSpec(Path folder) {
    try {
      return JSON.readValue(folder.resolve("spec.json").toFile(), Spec.class);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public DevState readDevState(Path folder) {
    Path f = folder.resolve("dev.json");
    try {
      return Files.exists(f) ? JSON.readValue(f.toFile(), DevState.class) : DevState.EMPTY;
    } catch (IOException e) {
      throw new UncheckedIOException("dev.json hỏng: " + e.getMessage(), e);
    }
  }

  public void writeDevState(Path folder, DevState state) {
    try {
      Yaml.writeText(folder.resolve("dev.json"), JSON.writeValueAsString(state) + "\n");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Document names that exist in the ticket folder, e.g. analysis.md, bugs/BUG-01.md. */
  public List<String> docs(Path folder) {
    List<String> out = new ArrayList<>();
    for (String name : TaskDocs.ALL) {
      if (Files.isRegularFile(folder.resolve(name))) {
        out.add(name);
      }
    }
    Path bugs = folder.resolve("bugs");
    if (Files.isDirectory(bugs)) {
      try (Stream<Path> files = Files.list(bugs)) {
        files.map(f -> "bugs/" + f.getFileName()).filter(n -> BUG_DOC.matcher(n).matches()).sorted().forEach(out::add);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return out;
  }

  public static boolean isDocName(String name) {
    return TaskDocs.ALL.contains(name) || BUG_DOC.matcher(name).matches();
  }

  public Optional<String> readDoc(Path folder, String name) {
    Path f = folder.resolve(name);
    return Files.isRegularFile(f) ? Optional.of(read(f)) : Optional.empty();
  }

  public void writeDoc(Path folder, String name, String content) {
    Yaml.writeText(folder.resolve(name), content);
  }

  public void appendDoc(Path folder, String name, String line) {
    writeDoc(folder, name, readDoc(folder, name).orElse("") + line);
  }

  /** Stores content as the next version of doc and returns its number (1, 2, ...). */
  public int saveVersion(Path folder, String doc, String content) {
    int n = versionCount(folder, doc) + 1;
    writeDoc(folder, versionName(doc, n), content);
    return n;
  }

  public int versionCount(Path folder, String doc) {
    int n = 0;
    while (Files.exists(folder.resolve(versionName(doc, n + 1)))) {
      n++;
    }
    return n;
  }

  public Optional<String> readVersion(Path folder, String doc, int n) {
    return readDoc(folder, versionName(doc, n));
  }

  public List<String> readQueue(Path projectDir) {
    Path f = projectDir.resolve("tasks/queue.yml");
    return Files.exists(f) ? Yaml.read(f, Queue.class).order() : List.of();
  }

  public void writeQueue(Path projectDir, List<String> order) {
    Yaml.write(projectDir.resolve("tasks/queue.yml"), new Queue(order));
  }

  private static String versionName(String doc, int n) {
    return "versions/" + doc.replace(".md", "") + ".v" + n + ".md";
  }

  private static String read(Path f) {
    try {
      return Files.readString(f).replace("\r\n", "\n");
    } catch (IOException e) {
      throw new UncheckedIOException("Không đọc được " + f, e);
    }
  }
}
