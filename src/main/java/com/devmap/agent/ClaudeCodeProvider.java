package com.devmap.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Claude Code in headless mode (`claude -p --output-format stream-json`), flags checked against `claude --help` 2.1.x:
 * no built-in tools (--tools ""), no user/plugin settings or hooks (--setting-sources project in an empty folder), only DevMap's
 * MCP server (--strict-mcp-config) whose tools are pre-allowed while anything else is denied (--permission-mode dontAsk),
 * nothing saved on disk (--no-session-persistence). The system prompt is static per project and role, so Claude caches it (AIR-11).
 */
@Component
public class ClaudeCodeProvider implements AgentProvider {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final String command;
  private final String model;
  private final int timeoutMinutes;

  public ClaudeCodeProvider(@Value("${devmap.agent.command:}") String command, @Value("${devmap.agent.model:}") String model,
      @Value("${devmap.agent.timeout-minutes:30}") int timeoutMinutes) {
    boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    this.command = command.isBlank() ? windows ? "claude.cmd" : "claude" : command;
    this.model = model;
    this.timeoutMinutes = timeoutMinutes;
  }

  List<String> argv(Path workDir) {
    List<String> argv = new ArrayList<>(List.of(command, "-p", "--output-format", "stream-json", "--verbose",
        "--no-session-persistence", "--setting-sources", "project", "--strict-mcp-config",
        "--mcp-config", workDir.resolve("mcp.json").toString(), "--system-prompt-file", workDir.resolve("system.md").toString(),
        "--tools", "", "--allowed-tools", "mcp__devmap", "--permission-mode", "dontAsk"));
    if (!model.isBlank()) {
      argv.addAll(List.of("--model", model));
    }
    return argv;
  }

  @Override
  public Result run(Call call, Consumer<Event> sink) {
    Path dir = call.workDir();
    try {
      ObjectNode server = JSON.createObjectNode().put("type", "http").put("url", call.mcpUrl());
      server.putObject("headers").put("X-DevMap-Session", call.sessionToken());
      ObjectNode cfg = JSON.createObjectNode();
      cfg.putObject("mcpServers").set("devmap", server);
      Files.writeString(dir.resolve("mcp.json"), cfg.toString());
      Files.writeString(dir.resolve("system.md"), call.systemPrompt());
      Process p = new ProcessBuilder(argv(dir)).directory(dir.toFile())
          .redirectError(dir.resolve("stderr.txt").toFile()).start();
      CompletableFuture.delayedExecutor(timeoutMinutes, TimeUnit.MINUTES).execute(p::destroyForcibly);
      try (OutputStream in = p.getOutputStream()) {
        in.write(call.prompt().getBytes(StandardCharsets.UTF_8));
      }
      Result result = null;
      try (BufferedReader out = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
        for (String line = out.readLine(); line != null; line = out.readLine()) {
          Result r = parseLine(line, sink);
          result = r != null ? r : result;
        }
      }
      int code = p.waitFor();
      if (result == null) {
        String err = Files.exists(dir.resolve("stderr.txt")) ? Files.readString(dir.resolve("stderr.txt")).strip() : "";
        return new Result(false, "Agent dừng (mã " + code + ") không có kết quả. " + tail(err), Usage.ZERO);
      }
      return result;
    } catch (IOException e) {
      return new Result(false, "Không chạy được `" + command + "`: " + e.getMessage() + ". Kiểm tra bằng `devmap doctor`.", Usage.ZERO);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Result(false, "Phiên agent bị ngắt", Usage.ZERO);
    }
  }

  /** One stream-json line: assistant text and tool calls go to sink; the final "result" line is returned, others give null. */
  static Result parseLine(String line, Consumer<Event> sink) {
    JsonNode n;
    try {
      n = JSON.readTree(line);
    } catch (IOException e) {
      return null; // not JSON (warnings on stdout): ignore
    }
    switch (n.path("type").asText()) {
      case "assistant" -> n.path("message").path("content").forEach(c -> {
        if (c.path("type").asText().equals("text") && !c.path("text").asText().isBlank()) {
          sink.accept(new Event("text", c.path("text").asText()));
        } else if (c.path("type").asText().equals("tool_use")) {
          sink.accept(new Event("tool", c.path("name").asText().replaceFirst("^mcp__devmap__", "") + " " + c.path("input")));
        }
      });
      case "result" -> {
        JsonNode u = n.path("usage");
        Usage usage = new Usage(u.path("input_tokens").asLong(), u.path("output_tokens").asLong(),
            u.path("cache_read_input_tokens").asLong(), u.path("cache_creation_input_tokens").asLong(),
            n.path("total_cost_usd").asDouble());
        boolean error = n.path("is_error").asBoolean() || !n.path("subtype").asText("success").equals("success");
        return new Result(!error, n.path("result").asText(error ? n.path("subtype").asText() : ""), usage);
      }
      // subscription quota (status, rateLimitType, resetsAt, utilization), passed on as JSON for the token/quota box
      case "rate_limit_event" -> sink.accept(new Event("quota", n.path("rate_limit_info").toString()));
      default -> {
        // system/init, user (tool results, logged by DevMap itself)
      }
    }
    return null;
  }

  private static String tail(String s) {
    return s.length() <= 1500 ? s : "…" + s.substring(s.length() - 1500);
  }
}
