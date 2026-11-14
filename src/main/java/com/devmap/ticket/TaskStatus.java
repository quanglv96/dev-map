package com.devmap.ticket;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Map;
import java.util.Set;

/**
 * The 8 states of a ticket (PROMPT_ROOM FR-85, D-74), replacing the D-13 codes. Status only moves at controlled points: technical
 * rounds between SA and Dev change the runtime activity, never the status. Cancelled (D-66) stays outside the flow: only
 * {@code TaskService.cancel} enters it, and only the PMO takes it back to New. Old codes in task.md files are read as their new
 * state (FR-85 migration), see {@link #parse(String)}.
 */
public enum TaskStatus {
  NEW, ANALYSIS, WAIT_APPROVAL, READY, IMPLEMENT, VERIFY, WAIT_CONFIRM, DONE, CANCELLED;

  /** D-13 codes and the old "Mở lại" state; "reopened" also raises the reopened counter (TaskRepository). */
  static final Map<String, TaskStatus> LEGACY = Map.of(
      "sa_analysis", ANALYSIS, "reopened", ANALYSIS, "awaiting_approval", WAIT_APPROVAL, "in_dev", IMPLEMENT,
      "sa_verify", VERIFY, "sa_review", VERIFY, "awaiting_push", WAIT_CONFIRM);

  /** FR-86: every pair outside this table is refused by the server. */
  private static final Map<TaskStatus, Set<TaskStatus>> NEXT = Map.of(
      NEW, Set.of(ANALYSIS),
      ANALYSIS, Set.of(WAIT_APPROVAL),
      WAIT_APPROVAL, Set.of(READY, IMPLEMENT, ANALYSIS),
      READY, Set.of(IMPLEMENT),
      IMPLEMENT, Set.of(VERIFY, ANALYSIS),
      VERIFY, Set.of(IMPLEMENT, ANALYSIS, WAIT_CONFIRM),
      WAIT_CONFIRM, Set.of(IMPLEMENT, DONE),
      DONE, Set.of(ANALYSIS),
      CANCELLED, Set.of(NEW));

  @JsonValue
  public String code() {
    return name().toLowerCase();
  }

  public Set<TaskStatus> next() {
    return NEXT.get(this);
  }

  /** True when moving from this state to {@code to} is a decision only the PMO may take (approve, re-analyse, fix, push). */
  public boolean isPmoGate(TaskStatus to) {
    return this == WAIT_APPROVAL || this == WAIT_CONFIRM || this == DONE || this == CANCELLED;
  }

  /** FR-86: a change that touches AC, business rule or scope sends the ticket back to analysis; DevMap or the PMO only, never an agent. */
  public boolean isReanalysis(TaskStatus to) {
    return to == ANALYSIS && (this == IMPLEMENT || this == VERIFY);
  }

  /** Shown with a dot in the task list: the ticket is blocked until the PMO decides. */
  public boolean waitsForPmo() {
    return this == WAIT_APPROVAL || this == WAIT_CONFIRM;
  }

  /** Done or Cancelled: no more work, the session context is cleared (FR-45). */
  public boolean isClosed() {
    return this == DONE || this == CANCELLED;
  }

  public boolean isRunning() {
    return this == ANALYSIS || this == IMPLEMENT || this == VERIFY;
  }

  /** The PMO approved the plan at least once in this round: Dev may change code (FR-73). */
  public boolean isApproved() {
    return this == READY || this == IMPLEMENT || this == VERIFY || this == WAIT_CONFIRM;
  }

  @JsonCreator
  public static TaskStatus parse(String code) {
    for (TaskStatus s : values()) {
      if (s.code().equals(code)) {
        return s;
      }
    }
    TaskStatus old = LEGACY.get(code);
    if (old != null) {
      return old;
    }
    throw new IllegalArgumentException("Trạng thái không hợp lệ: " + code);
  }
}
