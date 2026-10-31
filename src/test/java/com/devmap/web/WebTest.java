package com.devmap.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devmap.SampleRepos;
import jakarta.servlet.http.Cookie;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class WebTest {

  static Path tmp;

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add("devmap.home", () -> tmp.resolve("home").toString());
  }

  @BeforeAll
  static void repos() throws Exception {
    tmp = Files.createTempDirectory("devmap-web");
    SampleRepos.create(tmp.resolve("repos"));
  }

  @Autowired
  MockMvc mvc;

  @Autowired
  ServerToken token;

  /** NFR-08: every request needs the run token. */
  @Test
  void rejectsRequestsWithoutToken() throws Exception {
    mvc.perform(get("/api/projects")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/projects").header(TokenFilter.HEADER, "wrong")).andExpect(status().isUnauthorized());
    mvc.perform(get("/")).andExpect(status().isUnauthorized());
  }

  @Test
  void tokenLinkSetsStrictHttpOnlyCookie() throws Exception {
    String setCookie = mvc.perform(get("/shop").param("token", token.value()))
        .andExpect(status().isFound())
        .andExpect(redirectedUrl("/shop"))
        .andExpect(cookie().httpOnly(TokenFilter.COOKIE, true))
        .andReturn().getResponse().getHeader("Set-Cookie");
    assertThat(setCookie).contains("SameSite=Strict");
    mvc.perform(get("/shop").cookie(new Cookie(TokenFilter.COOKIE, token.value())))
        .andExpect(status().isOk()).andExpect(forwardedUrl("/project.html"));
  }

  /** NFR-08: only 127.0.0.1 and only Host localhost (DNS rebinding). */
  @Test
  void rejectsNonLocalClientsEvenWithToken() throws Exception {
    mvc.perform(get("/api/projects").header(TokenFilter.HEADER, token.value()).with(r -> {
      r.setRemoteAddr("192.168.1.20");
      return r;
    })).andExpect(status().isForbidden());
    mvc.perform(get("/api/projects").header(TokenFilter.HEADER, token.value()).header("Host", "evil.example.com"))
        .andExpect(status().isForbidden());
  }

  /** The agent's MCP endpoint takes only a live session token; the run token alone or a made-up session gets nothing. */
  @Test
  void mcpNeedsALiveSession() throws Exception {
    String rpc = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";
    mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content(rpc)).andExpect(status().isUnauthorized());
    mvc.perform(post("/mcp").header(McpController.SESSION_HEADER, "made-up").contentType(MediaType.APPLICATION_JSON).content(rpc))
        .andExpect(status().isUnauthorized());
    mvc.perform(post("/mcp").header(TokenFilter.HEADER, token.value()).contentType(MediaType.APPLICATION_JSON).content(rpc))
        .andExpect(status().isUnauthorized());
    mvc.perform(post("/mcp").header(McpController.SESSION_HEADER, "made-up").contentType(MediaType.APPLICATION_JSON).content(rpc)
        .with(r -> {
          r.setRemoteAddr("192.168.1.20");
          return r;
        })).andExpect(status().isForbidden());
  }

  /** FR-01 → FR-04 through the API used by the UI. */
  @Test
  void projectLifecycleOverApi() throws Exception {
    String repo = tmp.resolve("repos").toString().replace('\\', '/');
    String body = """
        {"name":"webshop","color":"#00aa55","dataRoot":"%s",
         "repos":[{"path":"%s/order-service","baseBranch":"test"},{"path":"%s/payment-service","baseBranch":"main"}]}
        """.formatted(tmp.resolve("data").toString().replace('\\', '/'), repo, repo);
    mvc.perform(post("/api/projects").header(TokenFilter.HEADER, token.value())
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.config.repos[0].services[0].name").value("order-service"));

    mvc.perform(post("/api/projects").header(TokenFilter.HEADER, token.value())
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").exists());

    mvc.perform(get("/api/services/detect").param("path", repo + "/notification-service")
            .header(TokenFilter.HEADER, token.value()))
        .andExpect(jsonPath("$[0].name").value("notification-service"));

    mvc.perform(put("/api/projects/webshop/config").header(TokenFilter.HEADER, token.value())
            .contentType(MediaType.APPLICATION_JSON).content("{\"color\":\"#00aa55\",\"repos\":[{\"name\":\"x\",\"path\":\"/nope\"}]}"))
        .andExpect(status().isBadRequest());

    String h = token.value();
    mvc.perform(post("/api/projects/webshop/tasks").header(TokenFilter.HEADER, h).contentType(MediaType.APPLICATION_JSON)
            .content("{\"type\":\"FEAT\",\"title\":\"Refund\",\"request\":\"Hoàn tiền payment-service, key sk_live_abcdefghijklmnop\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.task.id").value("FEAT-001"))
        .andExpect(jsonPath("$.maskedCount").value(1));
    mvc.perform(post("/api/projects/webshop/tasks/FEAT-001/transition").header(TokenFilter.HEADER, h)
        .contentType(MediaType.APPLICATION_JSON).content("{\"to\":\"sa_analysis\"}")).andExpect(status().isOk());
    mvc.perform(post("/api/projects/webshop/tasks/FEAT-001/transition").header(TokenFilter.HEADER, h)
        .contentType(MediaType.APPLICATION_JSON).content("{\"to\":\"ready\"}")).andExpect(status().isBadRequest());
    for (String content : new String[] {"v1", "v2"}) {
      mvc.perform(put("/api/projects/webshop/tasks/FEAT-001/doc").param("name", "plan.md").header(TokenFilter.HEADER, h)
          .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"" + content + "\"}")).andExpect(status().isOk());
    }
    mvc.perform(get("/api/projects/webshop/tasks/FEAT-001/doc/diff").param("name", "plan.md").param("from", "1")
        .param("to", "2").header(TokenFilter.HEADER, h)).andExpect(jsonPath("$.diff").value(
            org.hamcrest.Matchers.containsString("+v2")));
    mvc.perform(get("/api/projects/webshop/tasks/FEAT-001/doc").param("name", "spec.json").header(TokenFilter.HEADER, h))
        .andExpect(jsonPath("$.content").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("sk_live"))));
    mvc.perform(get("/api/projects/webshop/tasks/FEAT-001").header(TokenFilter.HEADER, h))
        .andExpect(jsonPath("$.actions[0].to").value("awaiting_approval"));
    mvc.perform(get("/api/overview").header(TokenFilter.HEADER, h))
        .andExpect(jsonPath("$[0].running[0].id").value("FEAT-001"));
    mvc.perform(get("/api/events").header(TokenFilter.HEADER, h)).andExpect(status().isOk());

    mvc.perform(get("/api/projects/webshop/graph/services").header(TokenFilter.HEADER, h))
        .andExpect(jsonPath("$.nodes.length()").value(2));
    mvc.perform(get("/api/projects/webshop/graph/estimate").header(TokenFilter.HEADER, h))
        .andExpect(jsonPath("$.files").value(org.hamcrest.Matchers.greaterThan(5)));
    mvc.perform(get("/api/projects/webshop/graph/neighborhood").param("node", "svc:order-service").param("dir", "sideways")
        .header(TokenFilter.HEADER, h)).andExpect(status().isBadRequest());
    mvc.perform(post("/api/projects/webshop/graph/update").header(TokenFilter.HEADER, h)
        .contentType(MediaType.APPLICATION_JSON).content("{\"full\":false}")).andExpect(status().isAccepted());

    // NFR-10: PMO actions outside a ticket (index run) and ticket actions are both in the audit log; reads are not
    mvc.perform(get("/api/projects/webshop/audit").header(TokenFilter.HEADER, h))
        .andExpect(jsonPath("$[0].action").value("POST chạy cập nhật chỉ mục"))
        .andExpect(jsonPath("$[0].actor").value("PMO"))
        .andExpect(jsonPath("$[?(@.action =~ /PUT.*/)]").isEmpty()) // the config PUT above was refused (400): not an action
        .andExpect(jsonPath("$[?(@.ticket == 'FEAT-001')]").isNotEmpty())
        .andExpect(jsonPath("$[?(@.action =~ /GET.*/)]").isEmpty());
    mvc.perform(get("/api/projects/webshop/audit").param("ticket", "FEAT-001").header(TokenFilter.HEADER, h))
        .andExpect(jsonPath("$[?(@.ticket != 'FEAT-001')]").isEmpty());
    // tab title and icon of the project page
    mvc.perform(get("/api/projects/webshop/agents").header(TokenFilter.HEADER, h))
        .andExpect(jsonPath("$.running").isEmpty()).andExpect(jsonPath("$.queueRunning").value(false));
    mvc.perform(post("/api/projects/webshop/queue/run").header(TokenFilter.HEADER, h)
        .contentType(MediaType.APPLICATION_JSON).content("{\"on\":true}")).andExpect(status().isBadRequest());

    mvc.perform(get("/api/projects/nope").header(TokenFilter.HEADER, token.value())).andExpect(status().isNotFound());
    mvc.perform(delete("/api/projects/webshop").header(TokenFilter.HEADER, token.value())).andExpect(status().isNoContent());
    assertThat(tmp.resolve("data/webshop/config.yml")).exists();
  }

  @Autowired
  com.devmap.sync.SyncService sync;

  /** FR-53: "Chỉ xem" while another machine holds the project's lock: its writes get 423, reads and its sync endpoints still work. */
  @Test
  void viewOnlyRefusesWrites() throws Exception {
    mvc.perform(post("/api/projects").header(TokenFilter.HEADER, token.value()).contentType(MediaType.APPLICATION_JSON)
        .content("""
            {"name":"ro","dataRoot":"%s","repos":[{"path":"%s","baseBranch":"test"}]}
            """.formatted(tmp.resolve("data").toString().replace('\\', '/'),
            tmp.resolve("repos/order-service").toString().replace('\\', '/'))))
        .andExpect(status().isCreated());
    sync.viewOnly("ro");
    try {
      mvc.perform(put("/api/projects/ro/config").header(TokenFilter.HEADER, token.value()).contentType(MediaType.APPLICATION_JSON)
          .content("{}")).andExpect(status().is(423))
          .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("chỉ xem")));
      mvc.perform(get("/api/projects/ro").header(TokenFilter.HEADER, token.value())).andExpect(status().isOk());
      mvc.perform(get("/api/projects/ro/sync").header(TokenFilter.HEADER, token.value()))
          .andExpect(status().isOk()).andExpect(jsonPath("$.readOnly").value(true));
      mvc.perform(post("/api/projects/ro/sync/lock/view").header(TokenFilter.HEADER, token.value())).andExpect(status().isOk());
    } finally {
      mvc.perform(delete("/api/projects/ro").header(TokenFilter.HEADER, token.value()));
    }
  }
}
