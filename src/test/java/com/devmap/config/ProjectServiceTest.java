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
  void scanMarksUsedFolders() throws Exception {
    service.create("shop", dataRoot, "#ff8800", shopRepos());
    Files.createDirectories(dataRoot.resolve("old/graph"));
    Files.writeString(dataRoot.resolve("old/graph/x.json"), "{}");

    ProjectService.Folders scan = service.folders(dataRoot.toString());
    assertThat(scan.dirs()).extracting(ProjectService.Folder::name, ProjectService.Folder::usedBy)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("old", null), org.assertj.core.groups.Tuple.tuple("shop", "shop"));
    assertThat(service.folders(tmp.toString()).dirs()).filteredOn(f -> f.name().equals("repos"))
        .singleElement().satisfies(f -> assertThat(f.usedBy()).as("repos inside").isEqualTo("shop"));
    assertThat(dataRoot.resolve("old/graph/x.json")).as("scan never deletes").exists();
  }

  /** Project folder = parent of the service repos: each repo found with its checked-out branch and services. */
  @Test
  void scanReposFindsRepoChildrenOrTheFolderItself() throws Exception {
    Files.createDirectories(repos.resolve("not-a-repo"));
    assertThat(service.scanRepos(repos.toString())).extracting(RepoConfig::name)
        .containsExactly("notification-service", "order-service", "payment-service");
    assertThat(service.scanRepos(repos.resolve("order-service").toString())).singleElement().satisfies(r -> {
      assertThat(r.baseBranch()).isEqualTo(new GitService().currentBranch(repos.resolve("order-service")));
      assertThat(r.services()).singleElement().satisfies(sv -> assertThat(sv.name()).isEqualTo("order-service"));
    });
    assertThat(dataRoot).doesNotExist();
  }

  /** AC-15 / FR-52: a config.yml with absolute repo paths is migrated once: remote in config.yml, path in workspaces.yml, .bak kept. */
  @Test
  void legacyConfigWithAbsolutePathsIsMigratedOnce() throws Exception {
    GitService git = new GitService();
    Path order = repos.resolve("order-service");
    git.run(order, "remote", "add", "origin", "git@github.com:acme/order-service.git");
    Path dir = dataRoot.resolve("shop");
    Files.createDirectories(dir);
    String legacy = "project: shop\ncolor: '#ff8800'\nrepos:\n"
        + "- name: order-service\n  path: " + order.toString().replace('\\', '/') + "\n  base_branch: test\n"
        + "- name: payment-service\n  path: " + repos.resolve("payment-service").toString().replace('\\', '/') + "\n  base_branch: main\n";
    Files.writeString(dir.resolve("config.yml"), legacy);
    service.create("shop", dataRoot, null, List.of());

    Project p = service.get("shop");
    String yml = Files.readString(dir.resolve("config.yml"));
    assertThat(yml).doesNotContainPattern("(?m)^  path:").doesNotContain(tmp.toString().replace('\\', '/'))
        .contains("remote: git@github.com:acme/order-service.git");
    assertThat(Files.readString(dir.resolve("config.yml.bak"))).isEqualTo(legacy);
    assertThat(Files.readString(tmp.resolve("home/workspaces.yml")))
        .contains("github.com/acme/order-service: " + order.toString().replace('\\', '/'))
        .contains("shop/payment-service: ");
    assertThat(p.config().repos()).extracting(RepoConfig::path)
        .containsExactly(order.toString().replace('\\', '/'), repos.resolve("payment-service").toString().replace('\\', '/'));

    Files.writeString(dir.resolve("config.yml.bak"), "touched");
    service.get("shop");
    assertThat(Files.readString(dir.resolve("config.yml.bak"))).as("runs once").isEqualTo("touched");
  }

  /** AC-14 / FR-51: another machine has no path for a repo until the PMO picks a folder or lets DevMap clone it. */
  @Test
  void otherMachinePicksOrClonesMissingRepos() throws Exception {
    GitService git = new GitService();
    Path bare = tmp.resolve("origin/order-service.git");
    git.run(tmp, "clone", "-q", "--bare", repos.resolve("order-service").toString(), bare.toString());
    git.run(repos.resolve("order-service"), "remote", "add", "origin", bare.toString());
    service.create("shop", dataRoot, "#ff8800", shopRepos().subList(0, 2));
    assertThat(Files.readString(dataRoot.resolve("shop/config.yml"))).doesNotContainPattern("(?m)^  path:");

    ProjectService b = new ProjectService(new WorkspaceRepository(tmp.resolve("homeB")), new ProjectRepository(),
        new ServiceDetector(), git);
    b.create("shop", dataRoot, null, List.of());
    assertThat(b.get("shop").config().repos()).allSatisfy(r -> assertThat(r.path()).isNull());
    assertThat(b.problems(b.get("shop").config())).anySatisfy(m -> assertThat(m).contains("chưa có đường dẫn trên máy này"));
    assertThatThrownBy(() -> b.get("shop").config().repos().get(0).dir()).hasMessageContaining("chưa có đường dẫn");

    Project cloned = b.cloneRepo("shop", "order-service", tmp.resolve("machineB").toString());
    Path orderB = tmp.resolve("machineB/order-service");
    assertThat(orderB.resolve(".git")).exists();
    assertThat(cloned.config().repos().get(0).path()).isEqualTo(orderB.toString().replace('\\', '/'));
    assertThatThrownBy(() -> b.locateRepo("shop", "order-service", repos.resolve("payment-service").toString()))
        .as("payment has no origin, order's remote does not match").isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("remote");
    Project located = b.locateRepo("shop", "payment-service", repos.resolve("payment-service").toString());
    assertThat(b.problems(located.config())).isEmpty();
    assertThat(service.get("shop").config().repos().get(0).path()).as("machine A keeps its own path")
        .isEqualTo(repos.resolve("order-service").toString().replace('\\', '/'));
  }
}
