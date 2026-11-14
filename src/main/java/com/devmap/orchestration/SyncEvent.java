package com.devmap.orchestration;

import java.util.Map;

/**
 * One entry of {@code events.json} in a ticket folder (FR-82): stored before anything runs, so a restart picks it up again.
 * status: PENDING → RUNNING → DONE | FAILED | SKIPPED. origin/round: discussion and hop count of the proposal behind it (FR-81).
 * payload: proposal, doc, from, to, text, source (PMO|DEV|SA), case (A1, A2, B1…), skip, fingerprint.
 */
public record SyncEvent(String id, TriggerRules.Kind kind, String status, String created, String started, String finished,
    Map<String, String> payload, String origin, int round, String note) {

  public SyncEvent {
    payload = payload == null ? Map.of() : Map.copyOf(payload);
  }

  public String get(String key) {
    return payload.get(key);
  }

  SyncEvent with(String newStatus, String newStarted, String newFinished, String newNote) {
    return new SyncEvent(id, kind, newStatus, created, newStarted, newFinished, payload, origin, round, newNote);
  }
}
