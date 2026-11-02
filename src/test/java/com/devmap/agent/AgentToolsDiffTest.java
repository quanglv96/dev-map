package com.devmap.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AgentToolsDiffTest {

  private static final String DIFF = "diff --git a/A.java b/A.java\n+a\ndiff --git a/my dir/B.java b/my dir/B.java\n+b\n";

  @Test
  void oneFileOfADiffIncludingAPathWithASpace() {
    assertThat(AgentTools.narrowDiff(DIFF, "my dir/B.java")).contains("+b").doesNotContain("+a");
    assertThat(AgentTools.narrowDiff(DIFF, "C.java")).contains("không có file");
  }

  @Test
  void aDiffAboutToBeCutStartsWithItsFiles() {
    assertThat(AgentTools.narrowDiff(DIFF, null)).isEqualTo(DIFF);
    String head = AgentTools.narrowDiff(DIFF + "x".repeat(50_000), null).substring(0, 200);
    assertThat(head).contains("2 file", "A.java", "my dir/B.java");
  }
}
