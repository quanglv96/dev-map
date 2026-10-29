package com.devmap.ticket;

import java.util.List;

/** Fixed document names of a ticket folder (SRS "Lưu trữ dữ liệu"). */
public final class TaskDocs {

  public static final List<String> ALL = List.of("task.md", "spec.json", "analysis.md", "plan.md", "summary.md",
      "history.md", "changes.md", "tests.md", "verify.md");

  /** Every save creates a new version with a viewable diff (FR-09). */
  public static final List<String> VERSIONED = List.of("analysis.md", "plan.md");

  /** Documents the PMO may edit by hand in the Documents tab. */
  public static final List<String> PMO_EDITABLE = List.of("analysis.md", "plan.md", "summary.md");

  private TaskDocs() {
  }
}
