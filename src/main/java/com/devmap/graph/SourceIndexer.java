package com.devmap.graph;

import com.devmap.graph.FileIndex.Endpoint;
import com.devmap.graph.FileIndex.HttpCall;
import com.devmap.secret.SecretFiles;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Picks the extractor for a path: Java, tree-sitter languages, Spring config, docker-compose, OpenAPI, .proto. */
@Component
public class SourceIndexer {

  private static final int MAX_BYTES = 1_000_000;
  private static final Pattern SKIP_DIR = Pattern.compile(
      "(^|.*/)(\\.[^/]+|node_modules|target|build|dist|out|bin|vendor|generated|coverage|__pycache__|venv|"
          + "src/test|test|tests|__tests__|e2e)/.*");
  private static final Pattern SKIP_FILE = Pattern.compile(
      ".*(_test\\.go|\\.(test|spec)\\.[jt]sx?|/test_[^/]*\\.py|_test\\.py|\\.min\\.js|\\.d\\.ts)$");
  private static final Pattern CONFIG = Pattern.compile("(^|.*/)(application|bootstrap)[^/]*\\.(ya?ml|properties)$");
  private static final Pattern COMPOSE = Pattern.compile("(^|.*/)(docker-)?compose[^/]*\\.ya?ml$");
  private static final Pattern API_SPEC = Pattern.compile("(^|.*/)[^/]*(openapi|swagger)[^/]*\\.(ya?ml|json)$");
  private static final Pattern URL = Pattern.compile("https?://([^/:\\s\"'$}]+)(?::\\d+)?(/[^\\s\"'$}]*)?");
  private static final Pattern PROTO_SERVICE = Pattern.compile("service\\s+(\\w+)\\s*\\{(.*?)\\n\\}", Pattern.DOTALL);
  private static final Pattern PROTO_RPC = Pattern.compile("rpc\\s+(\\w+)\\s*\\(");
  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

  /** True when the file is worth indexing; secret files and tests are never selected, so never read. */
  public boolean indexable(String path) {
    String p = path.replace('\\', '/');
    if (SecretFiles.isSecret(p) || SKIP_DIR.matcher(p).matches() || SKIP_FILE.matcher("/" + p).matches()) {
      return false;
    }
    String lower = p.toLowerCase(Locale.ROOT);
    return lower.endsWith(".java") || lower.endsWith(".proto") || TreeSitterExtractor.forFile(lower) != null
        || CONFIG.matcher(lower).matches() || COMPOSE.matcher(lower).matches() || API_SPEC.matcher(lower).matches();
  }

  /** null when nothing useful was found. */
  public FileIndex extract(String path, byte[] bytes) {
    if (bytes.length > MAX_BYTES) {
      return null;
    }
    String lower = path.toLowerCase(Locale.ROOT);
    String text = new String(bytes, StandardCharsets.UTF_8);
    if (lower.endsWith(".java")) {
      return new JavaExtractor().extract(text);
    }
    TreeSitterExtractor ts = TreeSitterExtractor.forFile(lower);
    if (ts != null) {
      return ts.extract(bytes);
    }
    if (lower.endsWith(".proto")) {
      return proto(text);
    }
    if (API_SPEC.matcher(lower).matches()) {
      return openApi(text);
    }
    if (COMPOSE.matcher(lower).matches()) {
      return compose(text);
    }
    return config(text);
  }

  /** application.yml / .properties: every URL value is a candidate service dependency, keyed by its property. */
  private static FileIndex config(String text) {
    FileIndex.Builder out = new FileIndex.Builder("config");
    for (String line : text.split("\\R")) {
      Matcher m = URL.matcher(line);
      if (m.find() && !line.trim().startsWith("#")) {
        String key = line.replaceAll("^\\s*-?\\s*([\\w.\\-\\[\\]]+)\\s*[:=].*$", "$1").trim();
        out.httpCalls.add(new HttpCall(null, m.group(1), m.group(2) == null ? "" : m.group(2), "config:" + key));
      }
    }
    return out.build();
  }

  /** docker-compose: depends_on gives "a->b"; URLs in environment give calls from compose:<service>. */
  private static FileIndex compose(String text) {
    FileIndex.Builder out = new FileIndex.Builder("compose");
    try {
      JsonNode services = YAML.readTree(text).path("services");
      for (Iterator<Map.Entry<String, JsonNode>> it = services.fields(); it.hasNext();) {
        Map.Entry<String, JsonNode> svc = it.next();
        JsonNode deps = svc.getValue().path("depends_on");
        if (deps.isArray()) {
          deps.forEach(d -> out.composeDeps.add(svc.getKey() + "->" + d.asText()));
        } else {
          deps.fieldNames().forEachRemaining(d -> out.composeDeps.add(svc.getKey() + "->" + d));
        }
        Matcher m = URL.matcher(svc.getValue().path("environment").toString());
        while (m.find()) {
          out.httpCalls.add(new HttpCall(null, m.group(1), m.group(2) == null ? "" : m.group(2), "compose:" + svc.getKey()));
        }
      }
    } catch (Exception e) {
      return out.build();
    }
    return out.build();
  }

  private static FileIndex openApi(String text) {
    FileIndex.Builder out = new FileIndex.Builder("openapi");
    ParseOptions options = new ParseOptions();
    options.setResolve(false);
    OpenAPI api = new OpenAPIV3Parser().readContents(text, null, options).getOpenAPI();
    if (api == null || api.getPaths() == null) {
      return out.build();
    }
    api.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((verb, op) -> out.endpoints.add(
        new Endpoint(verb.name(), path, op.getOperationId() == null ? "" : op.getOperationId(), "openapi"))));
    return out.build();
  }

  private static FileIndex proto(String text) {
    FileIndex.Builder out = new FileIndex.Builder("proto");
    Matcher s = PROTO_SERVICE.matcher(text);
    while (s.find()) {
      Matcher rpc = PROTO_RPC.matcher(s.group(2));
      while (rpc.find()) {
        out.endpoints.add(new Endpoint("RPC", s.group(1) + "/" + rpc.group(1), "", "proto"));
      }
    }
    return out.build();
  }
}
