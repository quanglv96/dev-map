package com.devmap.agent;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * NFR-14: the one seam between DevMap and an AI agent. One call = one fresh session (FR-37). The agent gets no built-in file or
 * shell tools: everything it reads or changes goes through DevMap's MCP tools ({@link AgentTools}), behind the secret filter.
 */
public interface AgentProvider {

  /**
   * systemPrompt: static rules (cached by the provider, AIR-11). prompt: the dynamic context of this question. workDir: an empty
   * per-session folder, deleted afterwards. mcpUrl/sessionToken: where the DevMap tools live and the token naming this session.
   */
  record Call(String systemPrompt, String prompt, Path workDir, String mcpUrl, String sessionToken) {
  }

  /** kind: text (agent message), tool (agent called a tool), error, quota (JSON of the account's rate limit window). */
  record Event(String kind, String text) {
  }

  record Usage(long input, long output, long cacheRead, long cacheWrite, double costUsd) {

    public static final Usage ZERO = new Usage(0, 0, 0, 0, 0);

    public long total() {
      return input + output + cacheRead + cacheWrite;
    }

    public Usage plus(Usage o) {
      return new Usage(input + o.input, output + o.output, cacheRead + o.cacheRead, cacheWrite + o.cacheWrite, costUsd + o.costUsd);
    }
  }

  record Result(boolean ok, String text, Usage usage) {
  }

  /** Runs the session to the end, streaming steps to sink. Blocks; never throws for agent failures (ok=false instead). */
  Result run(Call call, Consumer<Event> sink);
}
