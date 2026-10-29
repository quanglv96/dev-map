package com.devmap.secret;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class SecretMaskerTest {

  /** Every fake secret planted in samples/shop. */
  static final List<String> SAMPLE_SECRETS = List.of(
      "10.12.0.45", "10.12.0.46", "10.12.0.47", "10.12.0.50", "203.0.113.10", "192.168.50.25",
      "FakeOrderPass!2026", "FakePayPass!2026", "FakeSmtpPass!2026",
      "tok_fake_7c1e9a2b4d6f8a0c2e4b6d8f0a1c3e5b", "sk_fake_51HxQ2bLkZ9fAKEfAKEfAKE0000000000",
      "jdbc:postgresql://", "pay_app&password");

  final SecretMasker masker = new SecretMasker();

  /** NFR-02 / AC-06 (document side): no planted value survives masking of any sample file. */
  @Test
  void masksEverySecretInSampleRepos() throws Exception {
    try (Stream<Path> files = Files.walk(Path.of("samples/shop"))) {
      for (Path f : files.filter(Files::isRegularFile).filter(p -> !p.toString().contains("repos")).toList()) {
        String masked = masker.mask(Files.readString(f)).text();
        for (String secret : SAMPLE_SECRETS) {
          assertThat(masked).as(f + " leaks " + secret).doesNotContain(secret);
        }
      }
    }
  }

  @Test
  void masksCommonFormats() {
    String text = """
        DB_PASSWORD=hunter2
        password: "s3cr3t value"
        token là ghp_abcdefghijklmnopqrstuvwxyz0123456789 nhé
        Authorization: Bearer abcdefghijklmnopqrstuvwxyz.123
        aws AKIAABCDEFGHIJKLMNOP
        url mongodb://user:pw@db.internal:27017/app
        https://admin:pa55@git.example.com/repo.git
        jwt eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U
        -----BEGIN RSA PRIVATE KEY-----
        MIIEpAIBAAKCAQEA
        -----END RSA PRIVATE KEY-----
        host 10.0.0.12
        """;
    SecretMasker.Result r = masker.mask(text);
    assertThat(r.text()).doesNotContain("hunter2", "s3cr3t", "ghp_", "abcdefghijklmnopqrstuvwxyz.123", "AKIA",
        "user:pw", "pa55", "eyJhbGci", "MIIEpAIB", "10.0.0.12");
    assertThat(r.text()).contains("token là [MASKED_TOKEN] nhé", "[MASKED_KEY]", "[MASKED_IP]");
    assertThat(r.count()).isGreaterThanOrEqualTo(10);
  }

  @Test
  void leavesCodeAndAllowedValuesAlone() {
    String text = """
        password: ${DB_PASSWORD}
        String password = request.getPassword();
        server.address=127.0.0.1
        commit 3f2a9c1d8e7b6a5f4e3d2c1b0a9f8e7d6c5b4a39
        OrderService.createOrder(customerId, amount)
        """;
    SecretMasker.Result r = masker.mask(text);
    assertThat(r.text()).isEqualTo(text);
    assertThat(r.count()).isZero();
  }

  @Test
  void projectRulesAddPatternsAndAllowValues() {
    SecretsConfig cfg = new SecretsConfig(List.of("NYX-INTERNAL-\\w+"), List.of("10\\.0\\.0\\.1"));
    SecretMasker.Result r = masker.mask("gw 10.0.0.1, db 10.0.0.2, code NYX-INTERNAL-abc", cfg);
    assertThat(r.text()).isEqualTo("gw 10.0.0.1, db [MASKED_IP], code [MASKED_CUSTOM]");
  }
}
