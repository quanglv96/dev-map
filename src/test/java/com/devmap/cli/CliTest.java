package com.devmap.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.devmap.SampleRepos;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** FR-42 (Phase 1 commands): init, workspace add/list/remove, doctor run against the shared core. */
class CliTest {

  @TempDir
  Path tmp;

  @AfterEach
  void tearDown() {
    Core.close();
    System.clearProperty("devmap.home");
  }

  private String run(int expectedCode, String... args) {
    PrintStream original = System.out;
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
    try {
      assertThat(new CommandLine(new DevMapCommand()).execute(args)).isEqualTo(expectedCode);
    } finally {
      System.setOut(original);
    }
    return out.toString(StandardCharsets.UTF_8);
  }

  @Test
  void initListDoctorRemove() throws Exception {
    System.setProperty("devmap.home", tmp.resolve("home").toString());
    Path repos = SampleRepos.create(tmp.resolve("repos"));
    String data = tmp.resolve("data").toString();

    String init = run(0, "init", "shop", "--data", data,
        "--repo", repos.resolve("order-service") + "@test",
        "--repo", repos.resolve("payment-service").toString());
    assertThat(init).contains("shop", "order-service @test", "payment-service @main");

    assertThat(run(0, "workspace", "list")).contains("shop", "2 repo");
    assertThat(run(1, "workspace", "add", "shop", "--data", data)).isEmpty();

    String doctor = run(0, "doctor");
    assertThat(doctor).contains("[OK]   Java", "[OK]   Dự án shop");

    assertThat(run(0, "workspace", "remove", "shop")).contains("giữ nguyên");
    assertThat(run(0, "workspace", "list")).isEmpty();
  }
}
