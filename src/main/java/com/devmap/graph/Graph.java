package com.devmap.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The project's dependency graph, built from the index (never stored; the index and manual edges are).
 * Node ids: svc:&lt;service&gt;, api:&lt;service&gt;:&lt;METHOD path&gt;, cls:&lt;service&gt;:&lt;Class&gt;,
 * fn:&lt;service&gt;:&lt;Class.method|function&gt;, table:&lt;name&gt;, topic:&lt;name&gt;.
 */
public final class Graph {

  /** kind: service | api | class | function | table | topic. file/line point into the repo for snippets. */
  public record Node(String id, String kind, String label, String service, String repo, String file, int line, int endLine,
      String signature) {
  }

  /** confidence: high | medium | low (FR-20). source: where the edge was seen, or "manual". */
  public record Edge(String from, String to, String type, String confidence, String source) {
  }

  public record Subgraph(List<Node> nodes, List<Edge> edges, boolean truncated) {
  }

  public enum Direction { IN, OUT, BOTH }

  /** Edges that mean "depends on" for traversal; "contains" (service → class → method) is structure, not dependency. */
  private static final Set<String> STRUCTURE = Set.of("contains");
  private static final List<String> CONFIDENCE = List.of("low", "medium", "high");

  private final Map<String, Node> nodes;
  private final List<Edge> edges;
  private final Map<String, List<Edge>> out = new HashMap<>();
  private final Map<String, List<Edge>> in = new HashMap<>();

  Graph(Map<String, Node> nodes, Collection<Edge> edges) {
    this.nodes = nodes;
    Map<String, Edge> unique = new LinkedHashMap<>();
    for (Edge e : edges) {
      if (nodes.containsKey(e.from()) && nodes.containsKey(e.to()) && !e.from().equals(e.to())) {
        unique.merge(e.from() + "|" + e.to() + "|" + e.type(), e, Graph::stronger);
      }
    }
    this.edges = List.copyOf(unique.values());
    for (Edge e : this.edges) {
      out.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e);
      in.computeIfAbsent(e.to(), k -> new ArrayList<>()).add(e);
    }
  }

  public Node node(String id) {
    return nodes.get(id);
  }

  public int nodeCount() {
    return nodes.size();
  }

  public int edgeCount() {
    return edges.size();
  }

  public List<Edge> edges() {
    return edges;
  }

  /** Nodes declared in one file of a repo (class, function and API nodes), e.g. to find who calls what a diff changed. */
  public List<Node> nodesIn(String repo, String file) {
    return nodes.values().stream().filter(n -> repo.equals(n.repo()) && file.equals(n.file())).toList();
  }

  public List<Node> search(String query, int limit) {
    String q = query.toLowerCase(Locale.ROOT);
    return nodes.values().stream().filter(n -> n.label().toLowerCase(Locale.ROOT).contains(q))
        .sorted(Comparator.comparing((Node n) -> !n.label().equalsIgnoreCase(query)).thenComparing(n -> n.label().length()))
        .limit(limit).toList();
  }

  /** AIR-09: everything within {@code depth} (max 3) dependency hops of the seeds; IN = who depends on them (blast radius). */
  public Subgraph neighborhood(Collection<String> seeds, int depth, Direction dir, int limit) {
    int max = Math.max(1, Math.min(depth, 3));
    Set<String> seen = new LinkedHashSet<>();
    Deque<String[]> queue = new ArrayDeque<>();
    seeds.stream().filter(nodes::containsKey).forEach(s -> {
      seen.add(s);
      queue.add(new String[] {s, "0"});
    });
    boolean truncated = false;
    while (!queue.isEmpty()) {
      String[] cur = queue.poll();
      int d = Integer.parseInt(cur[1]);
      if (d == max) {
        continue;
      }
      for (Edge e : step(cur[0], dir)) {
        String next = e.from().equals(cur[0]) ? e.to() : e.from();
        if (seen.size() >= limit) {
          truncated = true;
          break;
        }
        if (seen.add(next)) {
          queue.add(new String[] {next, String.valueOf(d + 1)});
        }
      }
    }
    return sub(seen, truncated);
  }

  /** FR-23 level 1: services, topics and tables with the dependencies between them (method-level edges lifted). */
  public Subgraph serviceLevel() {
    Set<String> ids = new LinkedHashSet<>();
    Map<String, Edge> lifted = new LinkedHashMap<>();
    nodes.values().stream().filter(n -> Set.of("service", "topic", "table").contains(n.kind())).forEach(n -> ids.add(n.id()));
    for (Edge e : edges) {
      String a = lift(e.from());
      String b = lift(e.to());
      if (a != null && b != null && !a.equals(b) && !STRUCTURE.contains(e.type())) {
        lifted.merge(a + "|" + b + "|" + e.type(), new Edge(a, b, e.type(), e.confidence(), e.source()), Graph::stronger);
      }
    }
    return new Subgraph(ids.stream().map(nodes::get).toList(), List.copyOf(lifted.values()), false);
  }

  /** FR-23 level 2: one service's APIs, plus the services/APIs, topics and tables it talks to. */
  public Subgraph serviceDetail(String serviceId) {
    Node svc = nodes.get(serviceId);
    if (svc == null) {
      return new Subgraph(List.of(), List.of(), false);
    }
    String name = svc.label();
    Set<String> ids = new LinkedHashSet<>(List.of(serviceId));
    Map<String, Edge> lifted = new LinkedHashMap<>();
    for (Edge e : edges) {
      Node a = nodes.get(e.from());
      Node b = nodes.get(e.to());
      if (STRUCTURE.contains(e.type())) {
        if (e.from().equals(serviceId) && b.kind().equals("api")) {
          ids.add(e.to());
          lifted.put(e.from() + "|" + e.to(), e);
        }
        continue;
      }
      boolean fromHere = name.equals(a.service());
      boolean toHere = name.equals(b.service());
      if (fromHere == toHere) {
        continue; // internal to this service, or unrelated
      }
      String from = shown(a);
      String to = shown(b);
      if (from == null || to == null || from.equals(to)) {
        continue;
      }
      ids.add(from);
      ids.add(to);
      lifted.merge(from + "|" + to + "|" + e.type(), new Edge(from, to, e.type(), e.confidence(), e.source()), Graph::stronger);
    }
    return new Subgraph(ids.stream().map(nodes::get).toList(), List.copyOf(lifted.values()), false);
  }

  /** Service ids of the given service or repo names (repo name = every service in that repo). */
  public List<String> servicesNamed(Collection<String> names) {
    return nodes.values().stream().filter(n -> n.kind().equals("service"))
        .filter(n -> names.contains(n.label()) || names.contains(n.repo())).map(Node::id).toList();
  }

  private List<Edge> step(String id, Direction dir) {
    List<Edge> r = new ArrayList<>();
    if (dir != Direction.IN) {
      out.getOrDefault(id, List.of()).stream().filter(e -> !STRUCTURE.contains(e.type())).forEach(r::add);
    }
    if (dir != Direction.OUT) {
      in.getOrDefault(id, List.of()).stream().filter(e -> !STRUCTURE.contains(e.type())).forEach(r::add);
    }
    return r;
  }

  /** Level-2 display: APIs, topics and tables stay; classes/functions collapse into their service. */
  private String shown(Node n) {
    return switch (n.kind()) {
      case "api", "topic", "table" -> n.id();
      default -> n.service() == null ? null : "svc:" + n.service();
    };
  }

  /** The service-level node standing for a node: itself for service/topic/table, else its service. */
  private String lift(String id) {
    Node n = nodes.get(id);
    if (n == null) {
      return null;
    }
    return switch (n.kind()) {
      case "service", "topic", "table" -> id;
      default -> n.service() == null ? null : "svc:" + n.service();
    };
  }

  private Subgraph sub(Set<String> ids, boolean truncated) {
    List<Edge> inside = edges.stream().filter(e -> ids.contains(e.from()) && ids.contains(e.to())).toList();
    return new Subgraph(ids.stream().map(nodes::get).toList(), inside, truncated);
  }

  private static Edge stronger(Edge a, Edge b) {
    return CONFIDENCE.indexOf(b.confidence()) > CONFIDENCE.indexOf(a.confidence()) ? b : a;
  }
}
