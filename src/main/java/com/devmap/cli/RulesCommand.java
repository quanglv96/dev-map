package com.devmap.cli;

import com.devmap.config.ProjectService;
import com.devmap.config.RepoConfig;
import com.devmap.config.Yaml;
import com.devmap.rule.QualityGate;
import com.devmap.rule.RuleService;
import java.nio.file.Path;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** FR-42 `devmap rules`: show effective rules, run the local quality gate (FR-31). */
@Command(name = "rules", mixinStandardHelpOptions = true, description = "Show rules and run the quality gate.")
class RulesCommand implements Runnable {

  @Spec
  CommandSpec spec;

  @Override
  public void run() {
    spec.commandLine().usage(System.out);
  }

  @Command(name = "show", description = "Print the effective rules of a service, or the project default.")
  int show(@Parameters(paramLabel = "project") String project, @Parameters(paramLabel = "service", arity = "0..1") String service) {
    RuleService rules = Core.get(RuleService.class);
    System.out.print(Yaml.toText(service == null ? rules.projectDefault(project) : rules.effective(project, service)));
    System.out.println("protected_branches (không tắt được): " + rules.protectedBranches(project));
    return 0;
  }

  @Command(name = "check", description = "Run layering check, static analysis and tests of every service (exit 1 on failure).")
  int check(@Parameters(paramLabel = "project") String project,
      @Option(names = "--repo", description = "Only this repo.") String repo,
      @Option(names = "--path", description = "Run in this folder (e.g. a ticket worktree) instead of the repo path.") Path path) {
    RuleService rules = Core.get(RuleService.class);
    List<RepoConfig> repos = Core.get(ProjectService.class).get(project).config().repos().stream()
        .filter(r -> repo == null || r.name().equals(repo)).toList();
    if (repos.isEmpty()) {
      throw new IllegalArgumentException("Không có repo " + repo);
    }
    boolean ok = true;
    for (RepoConfig r : repos) {
      for (QualityGate.Result res : rules.runGate(project, r.name(), path != null ? path : Path.of(r.path()))) {
        System.out.println(r.name() + " / " + res.service() + ": " + (res.passed() ? "ĐẠT" : "KHÔNG ĐẠT"));
        for (QualityGate.Step s : res.steps()) {
          System.out.println("  " + (s.passed() ? "✓" : "✗") + " " + s.name() + "  " + s.command() + "  (" + s.millis() + " ms)");
          if (!s.passed() && !s.output().isBlank()) {
            s.output().lines().forEach(l -> System.out.println("      " + l));
          }
        }
        ok &= res.passed();
      }
    }
    return ok ? 0 : 1;
  }
}
