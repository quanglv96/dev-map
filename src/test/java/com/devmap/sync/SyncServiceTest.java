package com.devmap.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devmap.SampleRepos;
import com.devmap.config.Project;
import com.devmap.config.ProjectRepository;
import com.devmap.config.ProjectService;
import com.devmap.config.ProjectService.RepoInput;
import com.devmap.config.ServiceDetector;
import com.devmap.config.WorkspaceRepository;
import com.devmap.git.GitService;
import com.devmap.secret.SecretMasker;
import com.devmap.sync.DataRepo.Change;
import com.devmap.sync.SyncService.State;
import com.devmap.sync.SyncService.Status;
import com.devmap.ticket.RequestCleaner;
import com.devmap.ticket.TaskRepository;
import com.devmap.ticket.TaskService;
import com.devmap.ticket.TaskService.Actor;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** AC-13, AC-16, AC-17, AC-19, AC-20: two machines (own ~/.devmap and data folder each) sharing a local bare repo. */
@ExtendWith(OutputCaptureExtension.class)
class SyncServiceTest {

  @TempDir
  Path tmp;

  final GitService git = new GitService();
  String remote;

  /** One PMO machine: machineX/home is its ~/.devmap, machineX/data its data root. */
  final class Machine {
    final Path data;
    final WorkspaceRepository workspaces;
    final ProjectService projects;
    final TaskService tasks;
    final SyncService sync;
    final List<Object> events = new ArrayList<>();

    Machine(String name) {
      Path base = tmp.resolve(name);
      data = base.resolve("data");
      workspaces = new WorkspaceRepository(base.resolve("home"));
      workspaces.setMachine(name);
      projects = new ProjectService(workspaces, new ProjectRepository(), new ServiceDetector(), git);
      SecretMasker masker = new SecretMasker();
      tasks = new TaskService(projects, new TaskRepository(), masker, new RequestCleaner(), e -> { }, git);
      sync = new SyncService(workspaces, projects, masker, new DataRepo(git), events::add);
    }

    void identity() {
      git.run(data, "config", "user.name", "devmap-test");
      git.run(data, "config", "user.email", "devmap@example.invalid");
    }

    String file(String rel) throws Exception {
      return Files.readString(data.resolve(rel));
    }
  }

  @BeforeEach
  void setUp() {
    Path bare = tmp.resolve("remote/devmap-data.git");
    assertThat(git.run(tmp, "init", "-q", "--bare", "-b", "main", bare.toString()).ok()).isTrue();
    remote = bare.toString().replace('\\', '/');
  }

  /** Machine A with project shop (one repo with an origin), synced to the bare remote. */
  private Machine machineA() throws Exception {
    Path repos = SampleRepos.create(tmp.resolve("machineA/code"));
    Path order = repos.resolve("order-service");
    git.run(order, "remote", "add", "origin", "https://github.com/acme/order-service.git");
    Machine a = new Machine("machineA");
    Files.createDirectories(a.data);
    a.projects.create("shop", a.data, "#ff8800", List.of(new RepoInput(order.toString(), "test")));
    a.sync.initSync(remote, a.data);
    a.identity();
    return a;
  }

  private Machine machineB() {
    Machine b = new Machine("machineB");
    b.sync.cloneData(remote, b.data);
    b.identity();
    return b;
  }

  private String ticket(Machine m) {
    return m.tasks.create("shop", "FEAT", "NYX-231", "Đăng nhập Google", "Đăng nhập bằng Google ở order-service").task().id();
  }

  private static Path folder(Machine m) throws Exception {
    try (var dirs = Files.list(m.data.resolve("shop/tasks"))) {
      return dirs.filter(d -> d.getFileName().toString().startsWith("NYX-231-")).findFirst().orElseThrow();
    }
  }

  private String inRemote(String... args) {
    List<String> argv = new ArrayList<>(List.of(args));
    return git.run(Path.of(remote), argv.toArray(String[]::new)).output();
  }

  /** AC-13 (+ AC-14 on the synced copy): what A writes B sees after cloning; what B edits A sees after syncing. */
  @Test
  void ticketAndDocumentsTravelBothWays() throws Exception {
    Machine a = machineA();
    String id = ticket(a);
    a.tasks.writeDoc("shop", id, "analysis.md", "# Phân tích của A\n", Actor.SA);
    Status st = a.sync.syncNow();
    assertThat(st.state()).as(st.message() + " " + st.blocked()).isEqualTo(State.SYNCED);
    assertThat(inRemote("log", "--format=%s", "main")).contains("data(shop/NYX-231):");

    Machine b = machineB();
    assertThat(b.projects.list()).extracting(Project::name).containsExactly("shop");
    assertThat(b.tasks.readDoc("shop", id, "analysis.md").content()).isEqualTo("# Phân tích của A\n");
    String config = b.file("shop/config.yml");
    assertThat(config).contains("remote: https://github.com/acme/order-service.git")
        .doesNotContain(tmp.toString().replace('\\', '/')).doesNotContainPattern("(?m)^  path:");
    assertThat(b.projects.get("shop").config().repos().get(0).path()).as("B picks its own folder (FR-51)").isNull();

    b.tasks.writeDoc("shop", id, "analysis.md", "# Phân tích sửa trên B\n", Actor.SA);
    assertThat(b.sync.syncNow().state()).isEqualTo(State.SYNCED);
    a.sync.syncNow();
    assertThat(a.tasks.readDoc("shop", id, "analysis.md").content()).isEqualTo("# Phân tích sửa trên B\n");
    assertThat(a.events).anySatisfy(e -> assertThat(((SyncService.SyncChanged) e).pulled()).isTrue());
  }

  /** FR-47: one ticket file → data(<project>/<ticket>): <action>; changes settle for one tick before they are committed. */
  @Test
  void commitMessageNamesProjectTicketAndAction() throws Exception {
    Machine a = machineA();
    String id = ticket(a);
    a.sync.syncNow();
    String folder = folder(a).getFileName().toString();
    assertThat(a.sync.message(a.data, List.of(new Change("shop/tasks/" + folder + "/analysis.md", false, false))))
        .isEqualTo("data(shop/NYX-231): cập nhật analysis.md");
    assertThat(a.sync.message(a.data, List.of(new Change("shop/config.yml", false, false),
        new Change("shop/rules/default.devrules.yml", true, false))))
        .isEqualTo("data(shop): cập nhật config.yml, thêm rules/default.devrules.yml");
    assertThat(a.sync.message(a.data, List.of(new Change(DataRepo.LOCK, false, false)))).isEqualTo("data(lock): heartbeat machineA");

    Files.writeString(a.data.resolve("shop/project.md"), "# shop\n\nđổi\n");
    assertThat(a.sync.commitPending(false)).as("first look: still being written").isFalse();
    assertThat(a.sync.commitPending(false)).as("same on the next tick: settled").isTrue();
  }

  /** AC-16: A holds the lock → B warned and read-only; B takes over → A read-only; 10 min without heartbeat = expired. */
  @Test
  void softLockWarnsTakesOverAndExpires() throws Exception {
    Machine a = machineA();
    a.sync.tick();
    assertThat(a.sync.status().lock()).as(a.sync.status().toString()).isNotNull();
    assertThat(a.sync.status().lock().machine()).isEqualTo("machineA");
    assertThat(inRemote("show", "main:" + DataRepo.LOCK)).contains("machineA");

    Machine b = machineB();
    b.sync.tick();
    Status sb = b.sync.status();
    assertThat(sb.state()).isEqualTo(State.LOCKED);
    assertThat(sb.readOnly()).isTrue();
    assertThat(sb.lockChoice()).isTrue();
    assertThat(sb.message()).contains("Đang mở ở máy machineA");

    b.sync.takeOver();
    assertThat(b.sync.readOnly()).isFalse();
    a.sync.heartbeat();
    assertThat(a.sync.readOnly()).isTrue();
    assertThat(a.sync.status().message()).contains("machineB đã chiếm quyền");

    Machine c = new Machine("machineC");
    c.sync.cloneData(remote, tmp.resolve("machineC/data"));
    c.identity();
    c.sync.setClock(Clock.offset(Clock.systemUTC(), Duration.ofMinutes(11)));
    assertThat(c.sync.expired(c.sync.status().lock())).isTrue();
    c.sync.tick();
    assertThat(c.sync.readOnly()).as("expired lock: no warning").isFalse();
    assertThat(c.sync.status().lock().machine()).isEqualTo("machineC");
  }

  /** AC-17: both edit the same file offline → nothing lost, both versions kept, the ticket shows a conflict, the PMO merges. */
  @Test
  void offlineEditsOfTheSameFileKeepBothVersions() throws Exception {
    Machine a = machineA();
    String id = ticket(a);
    a.sync.syncNow();
    Machine b = machineB();

    a.tasks.writeDoc("shop", id, "analysis.md", "# Bản của A\n", Actor.SA);
    b.tasks.writeDoc("shop", id, "analysis.md", "# Bản của B\n", Actor.SA);
    assertThat(a.sync.commitPending(true)).isTrue();
    assertThat(b.sync.commitPending(true)).isTrue();
    a.sync.syncNow();
    Status sb = b.sync.syncNow();

    String folder = "shop/tasks/" + folder(b).getFileName();
    assertThat(sb.state()).isEqualTo(State.CONFLICT);
    assertThat(b.file(folder + "/analysis.md")).isEqualTo("# Bản của A\n");
    assertThat(b.file(folder + "/analysis.conflict-machineB.md")).isEqualTo("# Bản của B\n");
    assertThat(b.sync.conflicts("shop")).anySatisfy(c -> {
      assertThat(c.ticket()).isEqualTo("NYX-231");
      assertThat(c.file()).isEqualTo(folder + "/analysis.md");
      assertThat(c.current()).isEqualTo("# Bản của A\n");
      assertThat(c.other()).isEqualTo("# Bản của B\n");
    });
    assertThat(b.tasks.readDoc("shop", id, "analysis.md").content()).isEqualTo("# Bản của A\n");

    a.sync.syncNow();
    assertThat(a.sync.conflicts("shop")).as("the other machine sees the conflict too").isNotEmpty();
    for (SyncService.Conflict c : b.sync.conflicts("shop")) {
      b.sync.resolve(c.conflictFile(), c.file().endsWith("analysis.md") ? "# Ghép A + B\n" : c.current());
    }
    assertThat(b.sync.conflicts("shop")).isEmpty();
    assertThat(b.file(folder + "/analysis.md")).isEqualTo("# Ghép A + B\n");
    assertThat(b.sync.syncNow().state()).isEqualTo(State.SYNCED);
  }

  /** AC-19 / FR-50: graph, cache, logs, sessions, temp and lock files never reach the data repo; export skips them too. */
  @Test
  void rebuildableAndMachineFilesAreNotSynced() throws Exception {
    Machine a = machineA();
    for (String f : List.of("shop/graph/order-service.json", "shop/logs/audit.jsonl", "shop/cache/x.bin", "sessions/s.log",
        "shop/config.yml.bak", "shop/tasks/q.tmp", "shop/build.lock")) {
      Files.createDirectories(a.data.resolve(f).getParent());
      Files.writeString(a.data.resolve(f), "x");
    }
    a.sync.syncNow();
    String files = inRemote("ls-tree", "-r", "--name-only", "main");
    assertThat(files).contains("shop/config.yml", ".gitignore")
        .doesNotContain("graph/", "logs/", "cache/", "sessions/", ".bak", ".tmp", "build.lock");

    ByteArrayOutputStream zip = new ByteArrayOutputStream();
    a.sync.export("shop", zip);
    Machine b = new Machine("machineB");
    Project imported = b.sync.importZip(new ByteArrayInputStream(zip.toByteArray()), b.data);
    assertThat(imported.config().project()).isEqualTo("shop");
    assertThat(b.data.resolve("shop/config.yml")).exists();
    assertThat(b.data.resolve("shop/graph/order-service.json")).doesNotExist();
    assertThatThrownBy(() -> b.sync.importZip(new ByteArrayInputStream(zip.toByteArray()), b.data))
        .hasMessageContaining("không ghi đè");
  }

  /** AC-20: a real-looking token written straight into history.md blocks the commit; file and line reported, value never logged. */
  @Test
  void secretInAChangedFileBlocksTheCommit(CapturedOutput output) throws Exception {
    Machine a = machineA();
    String id = ticket(a);
    a.sync.syncNow();
    String token = "ghp_" + "A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q7r8";
    Path history = folder(a).resolve("history.md");
    int line = Files.readAllLines(history).size() + 1;
    Files.writeString(history, Files.readString(history) + "token " + token + "\n");

    assertThat(a.sync.commitPending(true)).isFalse();
    Status st = a.sync.status();
    assertThat(st.state()).isEqualTo(State.ERROR);
    String rel = a.data.relativize(history).toString().replace('\\', '/');
    assertThat(st.blocked()).containsExactly(rel + ":" + line + " (TOKEN)");
    a.sync.syncNow();
    assertThat(inRemote("log", "-p", "--all")).doesNotContain(token);
    assertThat(output.getAll()).contains("Chặn commit dữ liệu").doesNotContain(token);

    Files.writeString(history, Files.readString(history).replace(token, "[MASKED_TOKEN]"));
    assertThat(a.sync.commitPending(true)).isTrue();
    assertThat(a.sync.status().blocked()).isEmpty();
  }

  @Test
  void remoteUrlNeverCarriesAToken() {
    assertThatThrownBy(() -> SyncService.requireSafeRemote("https://quang:ghp_x@github.com/acme/devmap-data.git"))
        .hasMessageContaining("credential manager");
    SyncService.requireSafeRemote("https://github.com/acme/devmap-data.git");
    SyncService.requireSafeRemote("git@github.com:acme/devmap-data.git");
  }
}
