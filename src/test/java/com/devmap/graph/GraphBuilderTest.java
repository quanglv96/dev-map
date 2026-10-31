package com.devmap.graph;

import static org.assertj.core.api.Assertions.assertThat;

import com.devmap.config.ProjectConfig;
import com.devmap.config.RepoConfig;
import com.devmap.graph.FileIndex.HttpCall;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GraphBuilderTest {

  @Test
  void httpCallWithoutEnclosingSymbolStartsAtTheService() {
    ProjectConfig config = ProjectConfig.create("p", "#000000",
        List.of(new RepoConfig("web", "web", "main", List.of()), new RepoConfig("api", "api", "main", List.of())));
    FileIndex top = new FileIndex("js", List.of(), List.of(), List.of(new HttpCall("GET", "api", "/x", null)),
        List.of(), List.of(), List.of(), List.of());
    Graph g = GraphBuilder.build(config, List.of(new RepoIndex("web", "c", "t", Map.of("app.js", top))), List.of());
    assertThat(g.edges()).anyMatch(e -> e.from().equals("svc:web") && e.to().equals("svc:api"));
  }
}
