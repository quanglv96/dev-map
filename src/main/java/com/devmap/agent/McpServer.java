package com.devmap.agent;

import com.devmap.ticket.PmoOnlyException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.UncheckedIOException;
import java.util.NoSuchElementException;
import org.springframework.stereotype.Component;

/**
 * The local MCP server the agent talks to (CLAUDE.md §4): JSON-RPC 2.0 over HTTP POST (MCP "streamable HTTP" with plain JSON
 * replies). Methods: initialize, ping, tools/list, tools/call; notifications get no reply. Each request carries the session token,
 * which fixes project, ticket and role. Every call and result is written to the session's input log.
 */
@Component
public class McpServer {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String PROTOCOL = "2025-06-18";

  private final AgentService agents;
  private final AgentTools tools;

  McpServer(AgentService agents, AgentTools tools) {
    this.agents = agents;
    this.tools = tools;
  }

  /** Reply to one JSON-RPC message, or null for a notification. Unknown session token: NoSuchElementException (HTTP 401). */
  public JsonNode handle(String token, JsonNode req) {
    AgentSession s = agents.session(token);
    if (!req.has("id")) {
      return null;
    }
    ObjectNode res = JSON.createObjectNode().put("jsonrpc", "2.0").set("id", req.get("id"));
    String method = req.path("method").asText();
    switch (method) {
      case "initialize" -> {
        ObjectNode r = res.putObject("result").put("protocolVersion", req.path("params").path("protocolVersion").asText(PROTOCOL));
        r.putObject("capabilities").putObject("tools");
        r.putObject("serverInfo").put("name", "devmap").put("version", "0.1.0");
      }
      case "ping" -> res.putObject("result");
      case "tools/list" -> {
        var list = res.putObject("result").putArray("tools");
        tools.list(s.role).forEach(t -> list.addObject().put("name", t.name()).put("description", t.description())
            .set("inputSchema", t.schema()));
      }
      case "tools/call" -> {
        String name = req.path("params").path("name").asText();
        JsonNode args = req.path("params").path("arguments");
        String text;
        boolean error = false;
        try {
          text = tools.call(s, name, args.isObject() ? args : JSON.createObjectNode());
        } catch (IllegalArgumentException | IllegalStateException | NoSuchElementException | PmoOnlyException | UncheckedIOException e) {
          text = tools.mask(s, "Lỗi: " + e.getMessage());
          error = true;
        }
        agents.logTool(s, name, args, text);
        ObjectNode r = res.putObject("result").put("isError", error);
        r.putArray("content").addObject().put("type", "text").put("text", text);
      }
      default -> res.putObject("error").put("code", -32601).put("message", "Method not found: " + method);
    }
    return res;
  }
}
