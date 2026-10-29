package com.devmap.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * FR-03: finds services in a repo by their build file. Only looks at file names, never reads contents,
 * so secret files are never opened.
 */
@Component
public class ServiceDetector {

  private static final Map<String, String> BUILD_FILES = new LinkedHashMap<>();

  static {
    BUILD_FILES.put("pom.xml", "maven");
    BUILD_FILES.put("build.gradle", "gradle");
    BUILD_FILES.put("build.gradle.kts", "gradle");
    BUILD_FILES.put("go.mod", "go");
    BUILD_FILES.put("pyproject.toml", "python");
    BUILD_FILES.put("requirements.txt", "python");
    BUILD_FILES.put("package.json", "node");
  }

  private static final Set<String> SKIP = Set.of("node_modules", "target", "build", "dist", "vendor", "out", "bin", "src");
  private static final int MAX_DEPTH = 3;

  public List<ServiceInfo> detect(Path repo) {
    List<ServiceInfo> found = new ArrayList<>();
    walk(repo, repo, 0, found);
    // A folder holding other services is an aggregator (parent pom, monorepo root), not a service itself.
    return found.stream().filter(a -> found.stream().noneMatch(b -> isAncestor(a.path(), b.path()))).toList();
  }

  private void walk(Path root, Path dir, int depth, List<ServiceInfo> out) {
    String rel = root.relativize(dir).toString().replace('\\', '/');
    BUILD_FILES.entrySet().stream()
        .filter(e -> Files.isRegularFile(dir.resolve(e.getKey())))
        .findFirst()
        .ifPresent(e -> out.add(new ServiceInfo(dir.getFileName().toString(), rel, e.getValue())));
    if (depth == MAX_DEPTH) {
      return;
    }
    try (Stream<Path> children = Files.list(dir)) {
      children.filter(Files::isDirectory)
          .filter(p -> !p.getFileName().toString().startsWith(".") && !SKIP.contains(p.getFileName().toString()))
          .sorted()
          .forEach(p -> walk(root, p, depth + 1, out));
    } catch (IOException e) {
      throw new UncheckedIOException("Không đọc được thư mục " + dir, e);
    }
  }

  private static boolean isAncestor(String parent, String child) {
    return !parent.equals(child) && (parent.isEmpty() || child.startsWith(parent + "/"));
  }
}
