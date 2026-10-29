package com.devmap.git;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.springframework.stereotype.Service;

/** Read-only git checks for project setup; pulling and worktrees come with the Dev flow. */
@Service
public class GitService {

  public boolean isRepository(Path dir) {
    return Files.exists(dir.resolve(".git"));
  }

  /** True when the branch exists locally or as origin/branch (fresh clones only have the remote one). */
  public boolean branchExists(Path repo, String branch) {
    try (Git git = Git.open(repo.toFile())) {
      Repository r = git.getRepository();
      return r.exactRef("refs/heads/" + branch) != null || r.exactRef("refs/remotes/origin/" + branch) != null;
    } catch (IOException e) {
      return false;
    }
  }

  public void init(Path dir) {
    try {
      Git.init().setDirectory(dir.toFile()).call().close();
    } catch (GitAPIException e) {
      throw new IllegalStateException("Không khởi tạo được git ở " + dir + ": " + e.getMessage(), e);
    }
  }
}
