package com.devmap.graph;

import com.devmap.config.ProjectConfig;
import com.devmap.config.RepoConfig;
import com.devmap.config.ServiceInfo;
import com.devmap.graph.FileIndex.Endpoint;
import com.devmap.graph.FileIndex.HttpCall;
import com.devmap.graph.FileIndex.Ref;
import com.devmap.graph.FileIndex.Symbol;
import com.devmap.graph.FileIndex.TableRef;
import com.devmap.graph.FileIndex.TopicRef;
import com.devmap.graph.Graph.Edge;
import com.devmap.graph.Graph.Node;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;

/** Turns the per-file index of every repo plus the manual edges into one cross-repo graph (FR-19, FR-20, FR-22). */
final class GraphBuilder {

  private final Map<String, Node> nodes = new LinkedHashMap<>();
  private final List<Edge> edges = new ArrayList<>();
  /** service → symbol name → node id. */
  private final Map<String, Map<String, String>> symbols = new HashMap<>();
  /** service → normalized "METHOD path" → api node id. */
  private final Map<String, Map<String, String>> apis = new HashMap<>();
  /** entity class → table. */
  private final Map<String, String> entityTables = new HashMap<>();

  private record Located(String service, String repo, String path, FileIndex index) {
  }

  static Graph build(ProjectConfig config, List<RepoIndex> indexes, List<ManualEdge> manual) {
    return new GraphBuilder().run(config, indexes, manual);
  }

  private Graph run(ProjectConfig config, List<RepoIndex> indexes, List<ManualEdge> manual) {
    Map<String, List<ServiceInfo>> servicesByRepo = new HashMap<>();
    for (RepoConfig r : config.repos()) {
      List<ServiceInfo> svcs = r.services().isEmpty() ? List.of(new ServiceInfo(r.name(), "", "repo")) : r.services();
      servicesByRepo.put(r.name(), svcs.stream().sorted(Comparator.comparing((ServiceInfo s) -> s.path().length()).reversed()).toList());
      svcs.forEach(s -> nodes.put("svc:" + s.name(), new Node("svc:" + s.name(), "service", s.name(), s.name(), r.name(),
          s.path(), 0, 0, s.kind())));
    }
    List<Located> files = new ArrayList<>();
    for (RepoIndex ri : indexes) {
      for (Entry<String, FileIndex> f : ri.files().entrySet()) {
        owner(servicesByRepo.getOrDefault(ri.repo(), List.of()), f.getKey())
            .ifPresent(svc -> files.add(new Located(svc, ri.repo(), f.getKey(), f.getValue())));
      }
    }
    files.forEach(this::declare);
    files.forEach(this::link);
    for (ManualEdge m : manual) {
      edges.add(new Edge(m.from(), m.to(), m.type(), m.confidence(), "manual"));
    }
    return new Graph(nodes, edges);
  }

  /** Pass 1: nodes for symbols, APIs, tables. */
  private void declare(Located f) {
    String svc = f.service();
    Map<String, String> own = symbols.computeIfAbsent(svc, k -> new HashMap<>());
    for (Symbol s : f.index().symbols()) {
      boolean type = !s.kind().equals("method") && !s.kind().equals("function");
      String id = (type ? "cls:" : "fn:") + svc + ":" + s.name();
      nodes.putIfAbsent(id, new Node(id, type ? "class" : "function", s.name(), svc, f.repo(), f.path(), s.line(), s.endLine(),
          s.signature()));
      own.putIfAbsent(s.name(), id);
      int dot = s.name().lastIndexOf('.');
      String parent = dot > 0 ? "cls:" + svc + ":" + s.name().substring(0, dot) : "svc:" + svc;
      edges.add(new Edge(type ? "svc:" + svc : parent, id, "contains", "high", f.path()));
    }
    for (Endpoint e : f.index().endpoints()) {
      String key = e.method() + " " + normalize(e.path());
      String id = "api:" + svc + ":" + key;
      Node old = nodes.get(id);
      nodes.put(id, new Node(id, "api", e.method() + " " + e.path(), svc, f.repo(), old != null ? old.file() : f.path(),
          old != null ? old.line() : 0, 0, old != null && !old.signature().contains(e.source()) ? old.signature() + "+" + e.source()
          : e.source()));
      apis.computeIfAbsent(svc, k -> new HashMap<>()).put(key, id);
      edges.add(new Edge("svc:" + svc, id, "contains", "high", f.path()));
    }
    for (TableRef t : f.index().tables()) {
      entityTables.put(t.from(), t.table());
      nodes.putIfAbsent("table:" + t.table(), new Node("table:" + t.table(), "table", t.table(), null, null, null, 0, 0, ""));
    }
  }

  /** Pass 2: edges, now that every symbol and API of every service is known. */
  private void link(Located f) {
    String svc = f.service();
    FileIndex idx = f.index();
    for (Endpoint e : idx.endpoints()) {
      resolve(svc, e.handler()).ifPresent(h -> edges.add(new Edge("api:" + svc + ":" + e.method() + " " + normalize(e.path()), h,
          "handles", "high", f.path())));
    }
    for (TableRef t : idx.tables()) {
      resolve(svc, t.from()).ifPresent(c -> edges.add(new Edge(c, "table:" + t.table(), "maps", "high", f.path())));
    }
    for (Ref r : idx.refs()) {
      Optional<String> from = resolve(svc, r.from());
      if (from.isEmpty()) {
        continue;
      }
      if (r.type().equals("entity")) {
        Optional.ofNullable(entityTables.get(r.to()))
            .ifPresent(t -> edges.add(new Edge(from.get(), "table:" + t, "uses_table", "high", f.path())));
        resolve(svc, r.to()).ifPresent(to -> edges.add(new Edge(from.get(), to, "depends", "high", f.path())));
        continue;
      }
      Optional<String> to = resolve(svc, r.to());
      boolean exact = to.isPresent();
      if (to.isEmpty() && r.to().contains(".")) {
        to = resolve(svc, r.to().substring(0, r.to().lastIndexOf('.')));
      }
      String conf = exact && (r.to().contains(".") || r.type().equals("depends") || r.type().equals("extends")) ? "high" : "medium";
      to.ifPresent(t -> edges.add(new Edge(from.get(), t, r.type(), conf, f.path())));
    }
    for (TopicRef t : idx.topics()) {
      String topic = "topic:" + t.topic();
      nodes.putIfAbsent(topic, new Node(topic, "topic", t.topic(), null, null, null, 0, 0, ""));
      String at = resolve(svc, t.from()).orElse("svc:" + svc);
      edges.add(t.direction().equals("publish") ? new Edge(at, topic, "publishes", "high", f.path())
          : new Edge(topic, at, "consumes", "high", f.path()));
    }
    for (HttpCall h : idx.httpCalls()) {
      http(svc, f.path(), h);
    }
    for (String dep : idx.composeDeps()) {
      String[] ab = dep.split("->", 2);
      edges.add(new Edge("svc:" + ab[0], "svc:" + ab[1], "compose", "low", f.path()));
    }
  }

  /** FR-20: a call becomes service→API (path matched), service→service (host only) or a guess by path alone. */
  private void http(String svc, String file, HttpCall h) {
    // top-level JS/Python calls have no enclosing symbol; NON_EMPTY also reads a stored "" back as null
    String from = h.from() == null ? "" : h.from();
    String origin = from.startsWith("compose:") ? "svc:" + from.substring(8) : from.startsWith("config:")
        ? "svc:" + svc : resolve(svc, from).orElse("svc:" + svc);
    String type = from.startsWith("config:") ? "config" : from.startsWith("compose:") ? "compose" : "http";
    String path = h.path() == null ? "" : normalize(h.path());
    String target = h.host() == null ? null : serviceForHost(h.host());
    if (target != null && target.equals(svc) && !type.equals("compose")) {
      return;
    }
    if (target != null) {
      Optional<String> api = matchApi(target, h.method(), path);
      edges.add(api.isPresent() ? new Edge(origin, api.get(), type, "high", file)
          : new Edge(origin, "svc:" + target, type, "medium", file));
      return;
    }
    if (path.length() > 1) {
      List<String> candidates = apis.keySet().stream().filter(s -> !s.equals(svc))
          .map(s -> matchApi(s, h.method(), path)).flatMap(Optional::stream).toList();
      String conf = candidates.size() == 1 ? "medium" : "low";
      candidates.stream().limit(3).forEach(api -> edges.add(new Edge(origin, api, type, conf, file)));
    }
  }

  private String serviceForHost(String host) {
    String h = host.toLowerCase(Locale.ROOT);
    return nodes.values().stream().filter(n -> n.kind().equals("service"))
        .filter(n -> h.equals(n.label().toLowerCase(Locale.ROOT)) || h.equals(String.valueOf(n.repo()).toLowerCase(Locale.ROOT))
            || h.startsWith(n.label().toLowerCase(Locale.ROOT) + "."))
        .map(Node::label).findFirst().orElse(null);
  }

  private Optional<String> matchApi(String svc, String method, String path) {
    Map<String, String> own = apis.getOrDefault(svc, Map.of());
    if (method != null && own.containsKey(method + " " + path)) {
      return Optional.of(own.get(method + " " + path));
    }
    return own.entrySet().stream().filter(e -> e.getKey().endsWith(" " + path)
        && (method == null || e.getKey().startsWith("ANY ") || e.getKey().startsWith(method + " "))).map(Entry::getValue).findFirst();
  }

  /** Same service first, then any other service (shared libraries); also accepts "Class.method" ↔ "method" suffixes. */
  private Optional<String> resolve(String svc, String name) {
    if (name == null || name.isEmpty()) {
      return Optional.empty();
    }
    String local = symbols.getOrDefault(svc, Map.of()).get(name);
    if (local != null) {
      return Optional.of(local);
    }
    if (!name.contains(".")) {
      List<String> bySuffix = symbols.getOrDefault(svc, Map.of()).entrySet().stream()
          .filter(e -> e.getKey().endsWith("." + name)).map(Entry::getValue).limit(2).toList();
      if (bySuffix.size() == 1) {
        return Optional.of(bySuffix.get(0));
      }
    }
    return symbols.entrySet().stream().filter(e -> !e.getKey().equals(svc)).map(e -> e.getValue().get(name))
        .filter(v -> v != null && name.contains(".")).findFirst();
  }

  /** "/orders/{id}/", "/orders/:id", "/orders/&lt;int:id&gt;" → "/orders/{}". */
  static String normalize(String path) {
    String p = path.replaceAll("\\{[^}]*}|:[A-Za-z_]\\w*|<[^>]*>", "{}").replaceAll("\\?.*$", "").replaceAll("/+$", "");
    return p.isEmpty() ? "/" : p.startsWith("/") ? p : "/" + p;
  }

  /** The service a file belongs to: the one with the longest matching folder (services are sorted that way). */
  private static Optional<String> owner(List<ServiceInfo> services, String path) {
    return services.stream().filter(s -> s.path().isEmpty() || path.startsWith(s.path() + "/")).map(ServiceInfo::name).findFirst();
  }
}
