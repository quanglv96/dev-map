package com.devmap.graph;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * What the indexer keeps from one source/config file (AIR-07): signatures and references, never bodies.
 * Symbol names are "Class", "Class.method" or "function"; {@code from} fields name the enclosing symbol.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record FileIndex(
    String lang,
    List<Symbol> symbols,
    List<Endpoint> endpoints,
    List<HttpCall> httpCalls,
    List<TopicRef> topics,
    List<TableRef> tables,
    List<Ref> refs,
    List<String> composeDeps) {

  /** kind: class | interface | enum | method | function. line/endLine let the agent ask for a snippet later. */
  public record Symbol(String kind, String name, String signature, int line, int endLine) {
  }

  /** An API this file serves: REST mapping, OpenAPI operation, route, or gRPC rpc (method "RPC"). */
  public record Endpoint(String method, String path, String handler, String source) {
  }

  /** An outgoing call: host may be null (relative path with a configured base URL), method may be null. */
  public record HttpCall(String method, String host, String path, String from) {
  }

  /** direction: publish | consume. */
  public record TopicRef(String topic, String direction, String from) {
  }

  /** A class mapped to (entity) or using (repository) a DB table. */
  public record TableRef(String table, String from) {
  }

  /** from depends on / calls to; both are symbol names, to may live in another file. */
  public record Ref(String from, String to, String type) {
  }

  public FileIndex {
    symbols = symbols == null ? List.of() : symbols;
    endpoints = endpoints == null ? List.of() : endpoints;
    httpCalls = httpCalls == null ? List.of() : httpCalls;
    topics = topics == null ? List.of() : topics;
    tables = tables == null ? List.of() : tables;
    refs = refs == null ? List.of() : refs;
    composeDeps = composeDeps == null ? List.of() : composeDeps;
  }

  /** Mutable accumulator used by the extractors. */
  static final class Builder {
    final String lang;
    final List<Symbol> symbols = new ArrayList<>();
    final List<Endpoint> endpoints = new ArrayList<>();
    final List<HttpCall> httpCalls = new ArrayList<>();
    final List<TopicRef> topics = new ArrayList<>();
    final List<TableRef> tables = new ArrayList<>();
    final List<Ref> refs = new ArrayList<>();
    final List<String> composeDeps = new ArrayList<>();

    Builder(String lang) {
      this.lang = lang;
    }

    FileIndex build() {
      return new FileIndex(lang, symbols, endpoints, httpCalls, topics, tables, refs, composeDeps);
    }
  }

  /** FR-36: the index is written into the data folder, so signatures and URLs go through the masker first. */
  FileIndex mask(UnaryOperator<String> m) {
    return new FileIndex(lang,
        symbols.stream().map(s -> new Symbol(s.kind(), s.name(), m.apply(s.signature()), s.line(), s.endLine())).toList(),
        endpoints, httpCalls.stream().map(h -> new HttpCall(h.method(), h.host() == null ? null : m.apply(h.host()),
            h.path() == null ? null : m.apply(h.path()), h.from())).toList(),
        topics, tables, refs, composeDeps);
  }
}
