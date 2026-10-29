package com.devmap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;

/** Same as samples/shop/init-repos.sh, in Java so tests run without a shell: 3 repos with main and test branches. */
public final class SampleRepos {

  public static final List<String> SERVICES = List.of("order-service", "payment-service", "notification-service");
  private static final PersonIdent AUTHOR = new PersonIdent("devmap", "devmap@example.invalid");

  private SampleRepos() {
  }

  public static Path create(Path target) throws Exception {
    for (String svc : SERVICES) {
      Path dest = target.resolve(svc);
      copy(Path.of("samples/shop", svc), dest);
      try (Git git = Git.init().setDirectory(dest.toFile()).setInitialBranch("main").call()) {
        git.add().addFilepattern(".").call();
        git.commit().setAuthor(AUTHOR).setCommitter(AUTHOR).setMessage("initial " + svc).call();
        git.checkout().setCreateBranch(true).setName("test").call();
        Files.writeString(dest.resolve("README.md"), "# " + svc + "\n");
        git.add().addFilepattern("README.md").call();
        git.commit().setAuthor(AUTHOR).setCommitter(AUTHOR).setMessage("test: add README").call();
        git.checkout().setName("main").call();
      }
    }
    return target;
  }

  /** Commits file changes on a branch (null content = delete), leaving main checked out. */
  public static void commit(Path repo, String branch, java.util.Map<String, String> files) throws Exception {
    try (Git git = Git.open(repo.toFile())) {
      git.checkout().setName(branch).call();
      for (var e : files.entrySet()) {
        Path f = repo.resolve(e.getKey());
        if (e.getValue() == null) {
          git.rm().addFilepattern(e.getKey()).call();
        } else {
          Files.createDirectories(f.getParent());
          Files.writeString(f, e.getValue());
          git.add().addFilepattern(e.getKey()).call();
        }
      }
      git.commit().setAuthor(AUTHOR).setCommitter(AUTHOR).setMessage("change on " + branch).call();
      git.checkout().setName("main").call();
    }
  }

  private static void copy(Path from, Path to) throws IOException {
    try (Stream<Path> files = Files.walk(from)) {
      for (Path f : files.toList()) {
        Path dest = to.resolve(from.relativize(f).toString());
        if (Files.isDirectory(f)) {
          Files.createDirectories(dest);
        } else {
          Files.copy(f, dest);
        }
      }
    }
  }
}
