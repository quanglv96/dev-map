package com.devmap.web;

import com.devmap.graph.Graph;
import com.devmap.graph.Graph.Direction;
import com.devmap.graph.GraphService;
import com.devmap.graph.ManualEdge;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Graph screen API (FR-17, FR-22, FR-23). Reads only the local index; nothing here calls an agent. */
@RestController
@RequestMapping("/api/projects/{project}/graph")
public class GraphController {

  public record UpdateRequest(boolean full) {
  }

  public record EdgeRequest(String from, String to, String type, String confidence, String note) {
  }

  private static final int LIMIT = 300;

  private final GraphService graphs;

  public GraphController(GraphService graphs) {
    this.graphs = graphs;
  }

  @GetMapping("/status")
  public GraphService.Status status(@PathVariable String project) {
    return graphs.status(project);
  }

  @GetMapping("/estimate")
  public GraphService.Estimate estimate(@PathVariable String project) {
    return graphs.estimate(project);
  }

  /** Starts a background fetch + index: incremental by git diff, or the full scan the PMO confirmed. */
  @PostMapping("/update")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public void update(@PathVariable String project, @RequestBody UpdateRequest req) {
    graphs.start(project, req.full());
  }

  @GetMapping("/services")
  public Graph.Subgraph services(@PathVariable String project) {
    return graphs.graph(project).serviceLevel();
  }

  @GetMapping("/service")
  public Graph.Subgraph service(@PathVariable String project, @RequestParam String id) {
    return graphs.graph(project).serviceDetail(id);
  }

  @GetMapping("/neighborhood")
  public Graph.Subgraph neighborhood(@PathVariable String project, @RequestParam List<String> node,
      @RequestParam(defaultValue = "2") int depth, @RequestParam(defaultValue = "both") String dir) {
    return graphs.graph(project).neighborhood(node, depth, Direction.valueOf(dir.toUpperCase(Locale.ROOT)), LIMIT);
  }

  @GetMapping("/search")
  public List<Graph.Node> search(@PathVariable String project, @RequestParam String q) {
    return graphs.graph(project).search(q, 20);
  }

  @GetMapping("/ticket/{id}")
  public Map<String, List<String>> ticket(@PathVariable String project, @PathVariable String id) {
    return Map.of("services", graphs.ticketServices(project, id));
  }

  @GetMapping("/edges")
  public List<ManualEdge> manualEdges(@PathVariable String project) {
    return graphs.manualEdges(project);
  }

  @PostMapping("/edges")
  @ResponseStatus(HttpStatus.CREATED)
  public ManualEdge addEdge(@PathVariable String project, @RequestBody EdgeRequest req) {
    return graphs.addManualEdge(project, req.from(), req.to(), req.type(), req.confidence(), req.note());
  }

  @DeleteMapping("/edges/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void removeEdge(@PathVariable String project, @PathVariable String id) {
    graphs.removeManualEdge(project, id);
  }
}
