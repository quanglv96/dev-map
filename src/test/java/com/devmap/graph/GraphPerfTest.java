package com.devmap.graph;

import static org.assertj.core.api.Assertions.assertThat;

import com.devmap.SampleRepos;
import com.devmap.config.ProjectRepository;
import com.devmap.config.ProjectService;
import com.devmap.config.ProjectService.RepoInput;
import com.devmap.config.ServiceDetector;
import com.devmap.config.WorkspaceRepository;
import com.devmap.git.GitService;
import com.devmap.secret.SecretMasker;
import com.devmap.ticket.RequestCleaner;
import com.devmap.ticket.TaskRepository;
import com.devmap.ticket.TaskService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** NFR-11, opt-in (./mvnw test -Dtest=GraphPerfTest -Dperf=true): ~200,000 lines of Java, full scan < 3 min, incremental < 20 s. */
@EnabledIfSystemProperty(named = "perf", matches = "true")
class GraphPerfTest {

  @TempDir
  Path tmp;

  @Test
  void fullScanOf200kLines() throws Exception {
    Path repo = tmp.resolve("big-service");
    int files = 2000;
    for (int i = 0; i < files; i++) {
      StringBuilder sb = new StringBuilder("package com.big.p" + (i % 50) + ";\n\nimport java.util.List;\n\n");
      sb.append("@Service\npublic class Svc").append(i).append(" {\n  private final Svc").append((i + 1) % files).append(" next;\n");
      for (int m = 0; m < 12; m++) {
        sb.append("  public List<String> op").append(m).append("(String a, int b) {\n");
        for (int l = 0; l < 5; l++) {
          sb.append("    String v").append(l).append(" = a + b + \"").append(l).append("\";\n");
        }
        sb.append("    return next.op").append((m + 1) % 12).append("(a, b);\n  }\n\n");
      }
      sb.append("}\n");
      Path f = repo.resolve("src/main/java/com/big/p" + (i % 50) + "/Svc" + i + ".java");
      Files.createDirectories(f.getParent());
      Files.writeString(f, sb.toString());
    }
    Files.writeString(repo.resolve("pom.xml"), "<project/>");
    long lines;
    try (var s = Files.walk(repo)) {
      lines = s.filter(p -> p.toString().endsWith(".java")).mapToLong(p -> {
        try {
          return Files.readAllLines(p).size();
        } catch (Exception e) {
          return 0;
        }
      }).sum();
    }
    PersonIdent me = new PersonIdent("devmap", "devmap@example.invalid");
    try (Git git = Git.init().setDirectory(repo.toFile()).setInitialBranch("main").call()) {
      git.add().addFilepattern(".").call();
      git.commit().setAuthor(me).setCommitter(me).setMessage("big").call();
    }
    ProjectService projects = new ProjectService(new WorkspaceRepository(tmp.resolve("home")), new ProjectRepository(),
        new ServiceDetector(), new GitService());
    projects.create("big", tmp.resolve("data"), "#000000", List.of(new RepoInput(repo.toString(), "main")), false);
    TaskService tasks = new TaskService(projects, new TaskRepository(), new SecretMasker(), new RequestCleaner(), e -> { });
    GraphService graphs = new GraphService(projects, new GitService(), new GraphRepository(), new SourceIndexer(), new SecretMasker(),
        tasks, e -> { });

    long t0 = System.currentTimeMillis();
    graphs.update("big", true);
    Graph g = graphs.graph("big");
    long full = System.currentTimeMillis() - t0;

    SampleRepos.commit(repo, "main", Map.of("src/main/java/com/big/p0/Svc0.java", "package com.big.p0;\npublic class Svc0 { }\n"));
    long t1 = System.currentTimeMillis();
    graphs.update("big", false);
    graphs.graph("big");
    long incremental = System.currentTimeMillis() - t1;

    System.out.printf("NFR-11: %d lines, %d files: full scan + graph %d ms (%d nodes, %d edges); incremental %d ms%n",
        lines, files, full, g.nodeCount(), g.edgeCount(), incremental);
    assertThat(lines).isGreaterThanOrEqualTo(200_000);
    assertThat(full).isLessThan(180_000);
    assertThat(incremental).isLessThan(20_000);
  }
}
