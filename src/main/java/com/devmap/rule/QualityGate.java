package com.devmap.rule;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * FR-31 / AIR-13 / CLAUDE.md §5.9: the local quality gate of one service folder. Layering check built in, then the
 * configured static analysis, unit and integration test commands. Every step runs even after a failure so the report is complete.
 * Output is returned raw; whoever writes it to a document masks it first.
 */
@Component
public class QualityGate {

  /** One step; exitCode -1 = could not run or timed out. */
  public record Step(String name, String command, boolean passed, int exitCode, long millis, String output) {
  }

  public record Result(String service, List<Step> steps) {
    public boolean passed() {
      return steps.stream().allMatch(Step::passed);
    }
  }

  private static final int TAIL_LINES = 60;

  private static String lines(List<LayerChecker.Violation> v) {
    return String.join("\n", v.stream().map(LayerChecker.Violation::toString).toList());
  }
  private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");

  public Result run(String service, Path dir, Rules.Quality q) {
    return run(service, dir, q, null);
  }

  /**
   * changed: files (relative to dir) a ticket touched; layering then fails only on those, so a violation already on the base
   * branch does not block every ticket of the repo (it is still listed). null = the whole tree (`devmap rules check`).
   */
  public Result run(String service, Path dir, Rules.Quality q, Collection<String> changed) {
    List<Step> steps = new ArrayList<>();
    if (q.layering()) {
      long t0 = System.currentTimeMillis();
      List<LayerChecker.Violation> all = LayerChecker.check(dir);
      List<LayerChecker.Violation> v = changed == null ? all : all.stream().filter(x -> changed.contains(x.file())).toList();
      List<LayerChecker.Violation> old = all.stream().filter(x -> !v.contains(x)).toList();
      String out = (v.isEmpty() ? "" : "Controller gọi thẳng Repository:\n" + lines(v))
          + (old.isEmpty() ? "" : (v.isEmpty() ? "" : "\n\n")
              + "Vi phạm có sẵn ở file ticket không sửa (không chặn, nên làm ticket riêng):\n" + lines(old));
      steps.add(new Step("layering", "Controller → Service → Repository", v.isEmpty(), v.isEmpty() ? 0 : 1,
          System.currentTimeMillis() - t0, out));
    }
    if (q.staticAnalysis()) {
      if (q.staticCommands().isEmpty()) {
        steps.add(missing("static"));
      }
      q.staticCommands().forEach(c -> steps.add(command("static", c, dir, q.timeoutMinutes())));
    }
    if (q.unitTest()) {
      steps.add(blank(q.unitTestCommand()) ? missing("unit_test") : command("unit_test", q.unitTestCommand(), dir, q.timeoutMinutes()));
    }
    if (q.integrationTest()) {
      steps.add(blank(q.integrationTestCommand()) ? missing("integration_test")
          : command("integration_test", q.integrationTestCommand(), dir, q.timeoutMinutes()));
    }
    return new Result(service, steps);
  }

  private static Step missing(String name) {
    return new Step(name, "", false, -1, 0, "Rule bật bước này nhưng chưa cấu hình lệnh chạy");
  }

  // ponytail: wrapper looked up in the service folder only, walk up to the repo root if a multi-module repo needs it
  /** mvn/gradle → the repo's wrapper when it has one, so the gate runs on a machine without Maven/Gradle installed. */
  static String withWrapper(String command, Path dir) {
    for (String tool : List.of("mvn", "gradle")) {
      String wrapper = tool + "w";
      String file = WINDOWS ? wrapper + (tool.equals("mvn") ? ".cmd" : ".bat") : wrapper;
      if (command.startsWith(tool + " ") && Files.exists(dir.resolve(file))) {
        return (WINDOWS ? wrapper : "./" + wrapper) + command.substring(tool.length());
      }
    }
    return command;
  }

  /** Runs one configured command through the OS shell, output to a temp file (no pipe to drain, no deadlock). */
  static Step command(String name, String configured, Path dir, int timeoutMinutes) {
    String command = withWrapper(configured, dir);
    long t0 = System.currentTimeMillis();
    Path log = null;
    try {
      log = Files.createTempFile("devmap-gate", ".log");
      List<String> argv = WINDOWS ? List.of("cmd", "/c", command) : List.of("sh", "-c", command);
      ProcessBuilder pb = new ProcessBuilder(argv).directory(dir.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
      pb.environment().put("CI", "true"); // jest/vitest/karma etc. run once instead of watching
      Process p = pb.start();
      if (!p.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
        return new Step(name, command, false, -1, System.currentTimeMillis() - t0,
            "Quá " + timeoutMinutes + " phút, đã dừng\n" + tail(log));
      }
      // cmd: 9009, sh: 127 = command not found
      String hint = p.exitValue() == 9009 || p.exitValue() == 127 ? "\nKhông tìm thấy lệnh trên máy chạy DevMap: cài công cụ "
          + "(có trong PATH khi chạy devmap) hoặc sửa lệnh ở trang Quy tắc" : "";
      return new Step(name, command, p.exitValue() == 0, p.exitValue(), System.currentTimeMillis() - t0, tail(log) + hint);
    } catch (IOException e) {
      return new Step(name, command, false, -1, System.currentTimeMillis() - t0, "Không chạy được: " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Step(name, command, false, -1, System.currentTimeMillis() - t0, "Bị ngắt");
    } finally {
      if (log != null) {
        try {
          Files.deleteIfExists(log);
        } catch (IOException e) {
          // temp file, the OS cleans it later
        }
      }
    }
  }

  private static String tail(Path log) {
    try {
      List<String> lines = new String(Files.readAllBytes(log), StandardCharsets.UTF_8).lines().toList();
      return String.join("\n", lines.subList(Math.max(0, lines.size() - TAIL_LINES), lines.size()));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }
}
