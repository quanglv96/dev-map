package com.devmap.ticket;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import java.util.Set;

/**
 * The 8 states of a ticket (SRS "Luồng một ticket", see DECISIONS D-13). Two transitions are PMO gates
 * that no agent may pass: approving the solution and confirming the push.
 */
public enum TaskStatus {
  @JsonProperty("new") NEW,
  @JsonProperty("sa_analysis") SA_ANALYSIS,
  @JsonProperty("awaiting_approval") AWAITING_APPROVAL,
  @JsonProperty("ready") READY,
  @JsonProperty("in_dev") IN_DEV,
  @JsonProperty("sa_verify") SA_VERIFY,
  @JsonProperty("awaiting_push") AWAITING_PUSH,
  @JsonProperty("done") DONE;

  private static final Map<TaskStatus, Set<TaskStatus>> NEXT = Map.of(
      NEW, Set.of(SA_ANALYSIS),
      SA_ANALYSIS, Set.of(AWAITING_APPROVAL),
      AWAITING_APPROVAL, Set.of(SA_ANALYSIS, READY),
      READY, Set.of(IN_DEV),
      IN_DEV, Set.of(SA_VERIFY),
      SA_VERIFY, Set.of(IN_DEV, AWAITING_PUSH),
      AWAITING_PUSH, Set.of(IN_DEV, DONE),
      DONE, Set.of());

  public String code() {
    return name().toLowerCase();
  }

  public Set<TaskStatus> next() {
    return NEXT.get(this);
  }

  /** True when moving from this state to {@code to} is a decision only the PMO may take. */
  public boolean isPmoGate(TaskStatus to) {
    return this == AWAITING_APPROVAL && to == READY || this == AWAITING_PUSH && to == DONE;
  }

  /** Shown with a dot in the task list: the ticket is blocked until the PMO decides. */
  public boolean waitsForPmo() {
    return this == AWAITING_APPROVAL || this == AWAITING_PUSH;
  }

  public boolean isRunning() {
    return this == SA_ANALYSIS || this == IN_DEV || this == SA_VERIFY;
  }

  public static TaskStatus parse(String code) {
    for (TaskStatus s : values()) {
      if (s.code().equals(code)) {
        return s;
      }
    }
    throw new IllegalArgumentException("Trạng thái không hợp lệ: " + code);
  }
}
