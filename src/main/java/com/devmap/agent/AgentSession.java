package com.devmap.agent;

import com.devmap.ticket.TaskService.Actor;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One agent session = one PMO question (FR-37). The token is what the agent's MCP client presents; it names the project, ticket
 * and role, so the tools can never reach another ticket or project (NFR-06). log: everything the agent received (AC-10, AC-12).
 */
final class AgentSession {

  enum Role {
    SA(Actor.SA), DEV(Actor.DEV);

    final Actor actor;

    Role(Actor actor) {
      this.actor = actor;
    }
  }

  /** ask: answer the PMO; verify: SA checks Dev's diff against the request (FR-43); sync: started by an event (FR-77). */
  enum Mode { ASK, VERIFY, SYNC }

  final String token;
  final String project;
  final String taskId;
  final Role role;
  final Mode mode;
  final int messageId;
  final Path workDir;
  final Path log;
  /** Id of the sync event that started this session (FR-77), null for a PMO question. */
  final String trigger;
  /** FR-94: set when DevMap asks the session to stop after its current file edit; every later tool call is refused. */
  volatile String stop;
  /** Ticket documents the agent wrote in this session. */
  final Set<String> written = ConcurrentHashMap.newKeySet();
  /** Code files Dev wrote, edited or deleted in this session, per repo (worktree-relative, forward slashes). */
  final Map<String, Set<String>> edited = new ConcurrentHashMap<>();

  AgentSession(String token, String project, String taskId, Role role, Mode mode, int messageId, Path workDir, Path log) {
    this(token, project, taskId, role, mode, messageId, workDir, log, null);
  }

  AgentSession(String token, String project, String taskId, Role role, Mode mode, int messageId, Path workDir, Path log,
      String trigger) {
    this.trigger = trigger;
    this.token = token;
    this.project = project;
    this.taskId = taskId;
    this.role = role;
    this.mode = mode;
    this.messageId = messageId;
    this.workDir = workDir;
    this.log = log;
  }
}
