package com.devmap.rule;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * AIR-12 / FR-31: Controller → Service → Repository, no shortcut. A controller (@Controller, @RestController or *Controller)
 * that mentions a repository type (@Repository, *Repository, *Dao, or extending a Spring Data repository) is a violation.
 * Reads Java sources of the working tree (main code only); nothing leaves the machine.
 */
public final class LayerChecker {

  public record Violation(String file, int line, String controller, String repository) {
    @Override
    public String toString() {
      return file + ":" + line + " " + controller + " → " + repository;
    }
  }

  private static final Set<String> SKIP = Set.of("target", "build", "node_modules", "out", "bin", "test", ".git");

  private LayerChecker() {
  }

  public static List<Violation> check(Path root) {
    Map<String, CompilationUnit> units = new TreeMap<>();
    JavaParser parser = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));
    for (Path f : javaFiles(root)) {
      try {
        parser.parse(Files.readString(f)).getResult().ifPresent(cu -> units.put(root.relativize(f).toString().replace('\\', '/'), cu));
      } catch (IOException | RuntimeException e) {
        // unreadable file: the compiler step of the gate reports it
      }
    }
    Set<String> repositories = new HashSet<>();
    units.values().forEach(cu -> cu.findAll(ClassOrInterfaceDeclaration.class).stream().filter(LayerChecker::isRepository)
        .forEach(c -> repositories.add(c.getNameAsString())));

    List<Violation> out = new ArrayList<>();
    units.forEach((file, cu) -> cu.findAll(ClassOrInterfaceDeclaration.class).stream().filter(LayerChecker::isController)
        .forEach(c -> {
          Set<String> seen = new LinkedHashSet<>();
          for (ClassOrInterfaceType t : c.findAll(ClassOrInterfaceType.class)) {
            String name = t.getNameAsString();
            if ((repositories.contains(name) || name.endsWith("Repository")) && seen.add(name)) {
              out.add(new Violation(file, t.getBegin().map(p -> p.line).orElse(0), c.getNameAsString(), name));
            }
          }
        }));
    return out;
  }

  private static boolean isController(ClassOrInterfaceDeclaration c) {
    return !c.isInterface() && (c.isAnnotationPresent("RestController") || c.isAnnotationPresent("Controller")
        || c.getNameAsString().endsWith("Controller"));
  }

  private static boolean isRepository(ClassOrInterfaceDeclaration c) {
    String n = c.getNameAsString();
    return c.isAnnotationPresent("Repository") || n.endsWith("Repository") || n.endsWith("Dao")
        || c.getExtendedTypes().stream().anyMatch(t -> t.getNameAsString().endsWith("Repository"));
  }

  private static List<Path> javaFiles(Path root) {
    if (!Files.isDirectory(root)) {
      return List.of();
    }
    try (Stream<Path> files = Files.walk(root)) {
      return files.filter(f -> f.toString().endsWith(".java"))
          .filter(f -> Stream.of(root.relativize(f).toString().split("[\\\\/]")).noneMatch(SKIP::contains))
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
