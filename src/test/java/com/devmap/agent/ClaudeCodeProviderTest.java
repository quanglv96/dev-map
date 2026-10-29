package com.devmap.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.devmap.agent.AgentProvider.Event;
import com.devmap.agent.AgentProvider.Result;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClaudeCodeProviderTest {

  /** Lines in the shape `claude -p --output-format stream-json --verbose` prints (2.1.x). */
  @Test
  void parsesStreamJson() {
    List<Event> events = new ArrayList<>();
    assertThat(ClaudeCodeProvider.parseLine("{\"type\":\"system\",\"subtype\":\"init\",\"tools\":[]}", events::add)).isNull();
    assertThat(ClaudeCodeProvider.parseLine("{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"Đang xem\"},"
        + "{\"type\":\"tool_use\",\"name\":\"mcp__devmap__search_graph\",\"input\":{\"query\":\"Order\"}}]}}", events::add)).isNull();
    assertThat(ClaudeCodeProvider.parseLine("not json", events::add)).isNull();
    Result r = ClaudeCodeProvider.parseLine("{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"pong\","
        + "\"total_cost_usd\":0.0057,\"usage\":{\"input_tokens\":2,\"cache_creation_input_tokens\":702,\"cache_read_input_tokens\":10,"
        + "\"output_tokens\":4}}", events::add);
    assertThat(events).extracting(Event::kind, Event::text)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("text", "Đang xem"),
            org.assertj.core.groups.Tuple.tuple("tool", "search_graph {\"query\":\"Order\"}"));
    assertThat(r.ok()).isTrue();
    assertThat(r.text()).isEqualTo("pong");
    assertThat(r.usage()).isEqualTo(new AgentProvider.Usage(2, 4, 10, 702, 0.0057));
    assertThat(ClaudeCodeProvider.parseLine("{\"type\":\"result\",\"subtype\":\"error_max_turns\",\"is_error\":true}", e -> { }).ok())
        .isFalse();
  }

  /** CLAUDE.md §4 / FR-34: no built-in tools at all, only DevMap's MCP server, nothing else allowed or persisted. */
  @Test
  void runsWithoutBuiltInToolsOrUserSettings() {
    List<String> argv = new ClaudeCodeProvider("claude", "", 30).argv(Path.of("w"));
    assertThat(String.join(" ", argv)).contains("-p --output-format stream-json --verbose --no-session-persistence",
        "--setting-sources project --strict-mcp-config", "--allowed-tools mcp__devmap --permission-mode dontAsk");
    assertThat(argv.get(argv.indexOf("--tools") + 1)).isEmpty();
  }
}
