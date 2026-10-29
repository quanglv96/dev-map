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

  /** FR-01 → FR-04 through the API used by the UI. */
  @Test
  void projectLifecycleOverApi() throws Exception {
    String repo = tmp.resolve("repos").toString().replace('\\', '/');
    String body = """
        {"name":"webshop","color":"#00aa55","dataRoot":"%s","git":false,
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

    mvc.perform(get("/api/projects/nope").header(TokenFilter.HEADER, token.value())).andExpect(status().isNotFound());
    mvc.perform(delete("/api/projects/webshop").header(TokenFilter.HEADER, token.value())).andExpect(status().isNoContent());
    assertThat(tmp.resolve("data/webshop/config.yml")).exists();
  }
}
