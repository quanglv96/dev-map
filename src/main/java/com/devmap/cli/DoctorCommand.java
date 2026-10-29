package com.devmap.cli;

import com.devmap.config.Project;
import com.devmap.config.ProjectService;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;

/** Checks the machine (Java 17+, git, agent CLI) and every registered project. */
@Command(name = "doctor", mixinStandardHelpOptions = true, description = "Check environment and project configs.")
class DoctorCommand implements Callable<Integer> {

  private boolean failed;

  @Override
  public Integer call() {
    int java = Runtime.version().feature();
    report(java >= 17, true, "Java " + java + (java >= 17 ? "" : " (cần 17+)"));
    String git = firstLine("git", "--version");
    report(git != null, true, git != null ? git : "không tìm thấy git trên PATH");
    String claude = firstLine("claude", "--version");
    if (claude == null) {
      claude = firstLine("claude.cmd", "--version");
    }
    report(claude != null, false, claude != null ? "Claude Code " + claude : "không tìm thấy Claude Code (cần cho agent SA/Dev)");

    ProjectService projects = Core.get(ProjectService.class);
    for (Project p : projects.list()) {
      List<String> problems = p.error() != null ? List.of(p.error()) : projects.problems(p.config());
      report(problems.isEmpty(), true, "Dự án " + p.name() + (problems.isEmpty() ? "" : ": " + String.join("; ", problems)));
    }
    return failed ? 1 : 0;
  }

  private void report(boolean ok, boolean required, String text) {
    failed |= !ok && required;
    System.out.println((ok ? "[OK]   " : required ? "[FAIL] " : "[WARN] ") + text);
  }

  private static String firstLine(String... cmd) {
    try {
      Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
      try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
        String line = r.readLine();
        return p.waitFor() == 0 ? line : null;
      }
    } catch (IOException e) {
      return null;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    }
  }
}
