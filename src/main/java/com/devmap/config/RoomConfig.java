package com.devmap.config;

import java.util.List;

/**
 * {@code room:} in config.yml (PROMPT_ROOM §2): who may do what in a ticket room, the local router's keywords and the limits of the
 * automatic sync between agents. Every field has a default, so an old config.yml without the section behaves as documented.
 */
public record RoomConfig(
    Boolean devCanProposeBeforeApproval,
    Boolean skipReapprovalForPmoChanges,
    Integer maxRounds,
    Integer maxAutoSessionsPerHour,
    Integer safeStopSeconds,
    List<String> saKeywords,
    List<String> devKeywords,
    List<String> bugKeywords,
    List<String> changeKeywords) {

  /** FR-72 (2): business, analysis, root cause → SA. */
  public static final List<String> SA_WORDS = List.of("nghiệp vụ", "quy tắc", "business", "rule", "phân tích", "nguyên nhân",
      "vì sao", "tại sao", "là gì", "yêu cầu", "phạm vi", "ac-", "tiêu chí", "luồng", "logic", "chính sách", "khi nào",
      "trường hợp", "impact", "ảnh hưởng", "giải pháp", "plan", "analysis");
  /** FR-72 (2): code, tests, technical logs → Dev. */
  public static final List<String> DEV_WORDS = List.of("sửa code", "code", "chạy test", "chạy lại test", "test", "unit test",
      "log", "stack trace", "stacktrace", "exception", "lỗi biên dịch", "compile", "build", "commit", "gate", "refactor",
      "null pointer", "npe", "dò log", "debug", "lint", "checkstyle");
  /** §2.5 case B: words of a bug report. */
  public static final List<String> BUG_WORDS = List.of("bug", "lỗi", "sai", "không đúng", "hỏng", "không chạy", "trùng",
      "crash", "fail");
  /** §2.5 case A: words of a logic change. */
  public static final List<String> CHANGE_WORDS = List.of("đổi", "thay", "thêm", "bỏ", "tối đa", "tối thiểu", "chỉ cho",
      "không cho", "dùng lại", "bổ sung", "phải", "nên");

  public RoomConfig {
    devCanProposeBeforeApproval = devCanProposeBeforeApproval == null || devCanProposeBeforeApproval;
    skipReapprovalForPmoChanges = skipReapprovalForPmoChanges != null && skipReapprovalForPmoChanges;
    maxRounds = maxRounds == null || maxRounds < 1 ? 2 : maxRounds;
    maxAutoSessionsPerHour = maxAutoSessionsPerHour == null || maxAutoSessionsPerHour < 1 ? 10 : maxAutoSessionsPerHour;
    safeStopSeconds = safeStopSeconds == null || safeStopSeconds < 1 ? 60 : safeStopSeconds;
    saKeywords = saKeywords == null || saKeywords.isEmpty() ? SA_WORDS : List.copyOf(saKeywords);
    devKeywords = devKeywords == null || devKeywords.isEmpty() ? DEV_WORDS : List.copyOf(devKeywords);
    bugKeywords = bugKeywords == null || bugKeywords.isEmpty() ? BUG_WORDS : List.copyOf(bugKeywords);
    changeKeywords = changeKeywords == null || changeKeywords.isEmpty() ? CHANGE_WORDS : List.copyOf(changeKeywords);
  }

  public static final RoomConfig DEFAULT = new RoomConfig(null, null, null, null, null, null, null, null, null);

  public RoomConfig withSkipReapproval(boolean v) {
    return new RoomConfig(devCanProposeBeforeApproval, v, maxRounds, maxAutoSessionsPerHour, safeStopSeconds, saKeywords,
        devKeywords, bugKeywords, changeKeywords);
  }

  public RoomConfig withSafeStopSeconds(int s) {
    return new RoomConfig(devCanProposeBeforeApproval, skipReapprovalForPmoChanges, maxRounds, maxAutoSessionsPerHour, s,
        saKeywords, devKeywords, bugKeywords, changeKeywords);
  }

  public RoomConfig withDevCanPropose(boolean v) {
    return new RoomConfig(v, skipReapprovalForPmoChanges, maxRounds, maxAutoSessionsPerHour, safeStopSeconds, saKeywords,
        devKeywords, bugKeywords, changeKeywords);
  }
}
