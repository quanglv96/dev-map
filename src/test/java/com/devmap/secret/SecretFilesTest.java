package com.devmap.secret;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SecretFilesTest {

  /** D-71: env templates are open, real env files stay secret. */
  @Test
  void envTemplatesAreNotSecret() {
    assertThat(SecretFiles.isSecret(".env.example")).isFalse();
    assertThat(SecretFiles.isSecret("sbok-service/.env.infra.example")).isFalse();
    assertThat(SecretFiles.isSecret("a\\.env.SAMPLE")).isFalse();
    assertThat(SecretFiles.isSecret(".env.template")).isFalse();
    assertThat(SecretFiles.isSecret(".env.dist")).isFalse();
    assertThat(SecretFiles.isSecret(".env")).isTrue();
    assertThat(SecretFiles.isSecret("svc/.env.local")).isTrue();
    assertThat(SecretFiles.isSecret(".env.example.bak")).isTrue();
    assertThat(SecretFiles.isSecret("id_rsa")).isTrue();
  }
}
