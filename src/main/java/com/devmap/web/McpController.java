package com.devmap.web;

import com.devmap.agent.McpServer;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * The agent's MCP endpoint (127.0.0.1 only, like everything else). It needs a live session token instead of the run token, so a
 * running agent can use exactly its own ticket's tools and nothing of the PMO's API.
 */
@RestController
public class McpController {

  public static final String SESSION_HEADER = "X-DevMap-Session";

  private final McpServer mcp;

  public McpController(McpServer mcp) {
    this.mcp = mcp;
  }

  @PostMapping(path = "/mcp", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<JsonNode> call(@RequestHeader(name = SESSION_HEADER, required = false) String session, @RequestBody JsonNode body) {
    try {
      JsonNode reply = mcp.handle(session, body);
      return reply == null ? ResponseEntity.accepted().build() : ResponseEntity.ok(reply);
    } catch (NoSuchElementException e) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
  }

  /** No server-initiated stream: the MCP client falls back to plain request/response. */
  @GetMapping("/mcp")
  public ResponseEntity<Void> stream() {
    return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
  }
}
