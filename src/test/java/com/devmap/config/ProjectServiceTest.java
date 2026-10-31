package com.devmap.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devmap.SampleRepos;
import com.devmap.config.ProjectService.RepoInput;
import com.devmap.git.GitService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectServiceTest {

  @TempDir
  Path tmp;

  Path repos;
  Path dataRoot;
  ProjectService service;

  @BeforeEach
  void setUp() throws Exception {
    repos = SampleRepos.create(tmp.resolve("repos"));
    dataRoot = tmp.resolve("data");
    service = new ProjectService(new WorkspaceRepository(tmp.resolve("home")), new ProjectRepository(),
        new ServiceDetector(), new GitService());
  }

  private List<RepoInput> shopRepos() {
    return List.of(
        new RepoInput(repos.resolve("order-service").toString(), "test"),
        new RepoInput(repos.resolve("payment-service").toString(), "main"),
        new RepoInput(repos.resolve("notification-service").toString(), "main"));
  }

  /** AC-01: data folder chosen, several repos with base branches, services listed, nothing scanned. */
  @Test
  void createsDataFolderWithReposAndServices() throws Exception {
    Project p = service.create("shop", dataRoot, "#ff8800", shopRepos());

    Path dir = dataRoot.resolve("shop");
    assertThat(p.dataDir()).isEqualTo(dir.toAbsolutePath().toString().replace('\\', '/'));
    for (String f : List.of("config.yml", "project.md", "conventions.md", "rules", "graph", "tasks")) {
      assertThat(dir.resolve(f)).exists();
    }
    assertThat(p.config().repos()).extracting(RepoConfig::name, RepoConfig::baseBranch)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("order-service", "test"),
            org.assertj.core.groups.Tuple.tuple("payment-service", "main"),
            org.assertj.core.groups.Tuple.tuple("notification-service", "main"));
    assertThat(p.config().repos()).allSatisfy(r -> assertThat(r.services()).singleElement()
        .satisfies(s -> assertThat(s.name()).isEqualTo(r.name())));
    assertThat(Files.readString(dir.resolve("config.yml"))).contains("base_branch: test", "scan_full_project: false");
    assertThat(Files.readString(dir.resolve("project.md"))).contains("**payment-service** (nhánh nền `main`)");
    try (Stream<Path> graph = Files.list(dir.resolve("graph"))) {
      assertThat(graph).as("no scan/index on project creation").isEmpty();
    }
    assertThat(service.list()).extracting(Project::name).containsExactly("shop");
  }

  @Test
  void rejectsMissingBaseBranchAndBadNames() {
    List<RepoInput> bad = List.of(new RepoInput(repos.resolve("payment-service").toString(), "develop"));
    assertThatThrownBy(() -> service.create("shop", dataRoot, "#ff8800", bad))
        .hasMessageContaining("không có nhánh nền 'develop'");
    assertThatThrownBy(() -> service.create("Shop!", dataRoot, "#ff8800", List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.create("api", dataRoot, "#ff8800", List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.create("x", dataRoot, "#ff8800",
        List.of(new RepoInput(tmp.toString(), "main")))).hasMessageContaining("không phải git repo");
    assertThat(dataRoot.resolve("shop")).doesNotExist();
  }

  @Test
  void removeKeepsDataAndCopiedFolderCanBeRegisteredAgain() {
    service.create("shop", dataRoot, "#ff8800", shopRepos());
    assertThatThrownBy(() -> service.create("shop", dataRoot, "#ff8800", List.of())).hasMessageContaining("đã tồn tại");

    service.remove("shop");
    assertThat(service.list()).isEmpty();
    assertThat(dataRoot.resolve("shop/config.yml")).exists();
    assertThatThrownBy(() -> service.get("shop")).isInstanceOf(NoSuchElementException.class);

    Project again = service.create("shop", dataRoot, null, List.of());
    assertThat(again.config().repos()).hasSize(3);
    assertThat(again.config().color()).isEqualTo("#ff8800");
  }

  @Test
  void updateValidatesAndKeepsPmoTextInProjectMd() throws Exception {
    Project p = service.create("shop", dataRoot, "#ff8800", shopRepos());
    Path md = dataRoot.resolve("shop/project.md");
    Files.writeString(md, Files.readString(md).replace("_(PMO bổ sung)_", "Hệ thống bán hàng."));

    ProjectConfig c = p.config();
    ProjectConfig onlyOrder = new ProjectConfig("ignored", "#123456", "auto", "^SHOP-\\d+$", c.branchPatterns(),
        c.repos().subList(0, 1), List.of("staging"), false, null);
    Project updated = service.update("shop", onlyOrder);

    assertThat(updated.config().project()).isEqualTo("shop");
    assertThat(updated.config().ticketCode()).isEqualTo("auto");
    assertThat(Files.readString(md)).contains("Hệ thống bán hàng.", "order-service").doesNotContain("payment-service");

    ProjectConfig broken = new ProjectConfig("shop", "red", "sometimes", "([", java.util.Map.of("FEAT", "feature/x"),
        c.repos(), List.of(), false, new com.devmap.secret.SecretsConfig(List.of("["), List.of()));
    assertThatThrownBy(() -> service.update("shop", broken))
        .hasMessageContaining("#rrggbb").hasMessageContaining("ticket_code")
        .hasMessageContaining("regex").hasMessageContaining("MAINT").hasMessageContaining("secrets");
  }

  @Test
  void scanMarksUsedFoldersAndDeletesOnlyFreeOnes() throws Exception {
    service.create("shop", dataRoot, "#ff8800", shopRepos());
    Files.createDirectories(dataRoot.resolve("old/graph"));
    Files.writeString(dataRoot.resolve("old/graph/x.json"), "{}");

    ProjectService.Folders scan = service.folders(dataRoot.toString());
    assertThat(scan.dirs()).extracting(ProjectService.Folder::name, ProjectService.Folder::usedBy)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("old", null), org.assertj.core.groups.Tuple.tuple("shop", "shop"));
    assertThat(service.folders(tmp.toString()).dirs()).filteredOn(f -> f.name().equals("repos"))
        .singleElement().satisfies(f -> assertThat(f.usedBy()).as("repos inside").isEqualTo("shop"));

    assertThatThrownBy(() -> service.deleteFolder(dataRoot.resolve("shop").toString())).hasMessageContaining("shop");
    assertThatThrownBy(() -> service.deleteFolder(tmp.toString())).hasMessageContaining("shop");
    service.deleteFolder(dataRoot.resolve("old").toString());
    assertThat(dataRoot.resolve("old")).doesNotExist();
    assertThat(dataRoot.resolve("shop/config.yml")).exists();
  }
}
