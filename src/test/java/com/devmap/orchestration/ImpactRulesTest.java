package com.devmap.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** FR-79 hard rule (part of AC-34, AC-50): AC, business rule, scope and new repos need the PMO; a new test case does not. */
class ImpactRulesTest {

  static final String ANALYSIS = """
      # Phân tích

      ## Business rule
      - BR-1: chỉ hoàn tiền đơn đã thanh toán

      ## AC
      - AC-1: Given đơn đã trả When huỷ Then hoàn đủ tiền
      - AC-2: Given đơn chưa trả When huỷ Then không hoàn

      ## Ca kiểm thử
      | mã | AC | Given/When/Then | loại |
      | TC-1 | AC-1 | đơn 100k | đúng |
      """;
  static final List<String> REPOS = List.of("order-service", "payment-service", "notification-service");
  static final List<String> MINE = List.of("order-service");

  @Test
  void technicalWhenOnlyPlanOrTestCasesChange() {
    ImpactRules.Verdict v = ImpactRules.classify(ANALYSIS, ANALYSIS + "| TC-2 | AC-1 | đơn 0đ | biên |\n", "- Bước 1: A",
        "- Bước 1: A\n- Bước 2: "
        + "dùng lại MoneyFormatter", REPOS, MINE, MINE);
    assertThat(v.technical()).isTrue();
  }

  @Test
  void acBusinessRuleAndScopeNeedThePmo() {
    assertThat(ImpactRules.classify(ANALYSIS, ANALYSIS.replace("hoàn đủ tiền", "hoàn tối đa 50%"), "", "", REPOS, MINE, MINE).affects())
        .isEqualTo("AC");
    assertThat(ImpactRules.classify(ANALYSIS, ANALYSIS.replace("## Ca kiểm thử", "- AC-3: Given trả góp When huỷ Then hoàn kỳ đã trả\n\n"
        + "## Ca kiểm thử"), "", "", REPOS, MINE, MINE).affects()).isEqualTo("AC");
    assertThat(ImpactRules.classify(ANALYSIS, ANALYSIS.replace("đã thanh toán", "đã thanh toán đủ"), "", "", REPOS, MINE, MINE).affects())
        .isEqualTo("BUSINESS_RULE");
    ImpactRules.Verdict repo = ImpactRules.classify(ANALYSIS, ANALYSIS, "- order-service: X", "- order-service: X\n- payment-service: "
        + "thêm API hoàn", REPOS, MINE, MINE);
    assertThat(repo.affects()).isEqualTo("SCOPE");
    assertThat(repo.repoAdded()).isTrue();
    assertThat(ImpactRules.classify(ANALYSIS, ANALYSIS, "", "", REPOS, MINE, List.of("order-service", "notification-service"))
        .repoAdded()).isTrue();
  }
}
