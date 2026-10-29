package com.devmap.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServiceDetectorTest {

  @TempDir
  Path repo;

  private void touch(String rel) throws Exception {
    Path f = repo.resolve(rel);
    Files.createDirectories(f.getParent());
    Files.writeString(f, "");
  }

  @Test
  void singleServiceRepoIsTheRepoItself() throws Exception {
    touch("pom.xml");
    touch("src/main/resources/static/package.json");
    assertThat(new ServiceDetector().detect(repo)).containsExactly(new ServiceInfo(repo.getFileName().toString(), "", "maven"));
  }

  @Test
  void aggregatorIsReplacedByItsModules() throws Exception {
    touch("pom.xml");
    touch("auth-service/pom.xml");
    touch("apps/gateway/package.json");
    touch("apps/gateway/node_modules/lib/package.json");
    touch("tools/worker/go.mod");
    touch(".github/x/pom.xml");
    assertThat(new ServiceDetector().detect(repo)).containsExactly(
        new ServiceInfo("gateway", "apps/gateway", "node"),
        new ServiceInfo("auth-service", "auth-service", "maven"),
        new ServiceInfo("worker", "tools/worker", "go"));
  }

  @Test
  void noBuildFileMeansNoService() throws Exception {
    touch("docs/readme.md");
    assertThat(new ServiceDetector().detect(repo)).isEmpty();
  }
}
