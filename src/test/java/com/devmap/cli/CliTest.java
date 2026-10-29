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

    assertThat(run(0, "task", "new", "shop", "--type", "BUG", "--request", "Đơn hàng lỗi ở order-service, ip 10.1.2.3"))
        .contains("Đã tạo BUG-001", "bugfix/BUG-001-don-hang-loi-o-order-service-ip", "đã che 1");
    assertThat(run(0, "task", "list", "shop")).contains("BUG-001\tBUG\tnew");
    assertThat(run(0, "task", "show", "shop", "BUG-001")).contains("repo: [order-service]", "spec.json", "sa_analysis");

    String doctor = run(0, "doctor");
    assertThat(doctor).contains("[OK]   Java", "[OK]   Dự án shop");

    assertThat(run(0, "workspace", "remove", "shop")).contains("giữ nguyên");
    assertThat(run(0, "workspace", "list")).isEmpty();
  }
}
