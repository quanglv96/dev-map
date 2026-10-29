package com.devmap.graph;

import com.devmap.graph.FileIndex.Endpoint;
import com.devmap.graph.FileIndex.HttpCall;
import com.devmap.graph.FileIndex.Ref;
import com.devmap.graph.FileIndex.Symbol;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TreeSitterGo;
import org.treesitter.TreeSitterJavascript;
import org.treesitter.TreeSitterPython;
import org.treesitter.TreeSitterTypescript;

/**
 * TypeScript, JavaScript, Python and Go via tree-sitter: functions, classes, methods with their signatures, calls by
 * name, HTTP routes (Express/Fastify-style, FastAPI/Flask decorators, net/http and gin) and outgoing HTTP calls.
 */
// ponytail: route/client detection by common call shapes; NestJS decorators and dynamic URLs are left to the SA agent
class TreeSitterExtractor {

  private static final Set<String> VERBS = Set.of("get", "post", "put", "delete", "patch", "route", "handlefunc", "handle", "any");
  private static final Set<String> CLIENTS = Set.of("axios", "http", "https", "requests", "httpx", "session", "client",
      "fetch", "got", "superagent", "resty", "$http", "ky");
  private static final Pattern URL = Pattern.compile("^(https?)://([^/:\"'`]+)(?::\\d+)?(/[^\"'`]*)?$");

  private final String lang;
  private final TSLanguage language;

  private TreeSitterExtractor(String lang, TSLanguage language) {
    this.lang = lang;
    this.language = language;
  }

  /** null when the extension is not one of the tree-sitter languages. */
  static TreeSitterExtractor forFile(String path) {
    String p = path.toLowerCase(Locale.ROOT);
    if (p.endsWith(".ts") || p.endsWith(".tsx")) {
      return new TreeSitterExtractor("typescript", new TreeSitterTypescript());
    }
    if (p.endsWith(".js") || p.endsWith(".jsx") || p.endsWith(".mjs") || p.endsWith(".cjs")) {
      return new TreeSitterExtractor("javascript", new TreeSitterJavascript());
    }
    if (p.endsWith(".py")) {
      return new TreeSitterExtractor("python", new TreeSitterPython());
    }
    if (p.endsWith(".go")) {
      return new TreeSitterExtractor("go", new TreeSitterGo());
    }
    return null;
  }

  FileIndex extract(byte[] source) {
    TSParser parser = new TSParser();
    parser.setLanguage(language);
    String text = new String(source, StandardCharsets.UTF_8);
    FileIndex.Builder out = new FileIndex.Builder(lang);
    walk(parser.parseString(null, text).getRootNode(), source, null, null, out);
    return out.build();
  }

  /** owner = enclosing class/type name, fn = enclosing function symbol. */
  private void walk(TSNode node, byte[] src, String owner, String fn, FileIndex.Builder out) {
    String type = node.getType();
    String nextOwner = owner;
    String nextFn = fn;
    switch (type) {
      case "class_declaration", "abstract_class_declaration", "interface_declaration", "class_definition" -> {
        String name = text(node.getChildByFieldName("name"), src);
        if (!name.isEmpty()) {
          out.symbols.add(symbol(type.contains("interface") ? "interface" : "class", name, node, src));
          nextOwner = name;
        }
      }
      case "type_spec" -> {
        TSNode t = node.getChildByFieldName("type");
        if (!t.isNull() && (t.getType().equals("struct_type") || t.getType().equals("interface_type"))) {
          out.symbols.add(symbol(t.getType().startsWith("struct") ? "class" : "interface", text(node.getChildByFieldName("name"), src),
              node, src));
        }
      }
      case "function_declaration", "generator_function_declaration", "function_definition", "method_definition" -> {
        nextFn = qualify(owner, text(node.getChildByFieldName("name"), src));
        out.symbols.add(symbol(owner == null ? "function" : "method", nextFn, node, src));
      }
      case "method_declaration" -> {
        String receiver = text(node.getChildByFieldName("receiver"), src).replaceAll("[()*]", " ").trim().replaceAll(".*\\s", "");
        nextFn = qualify(receiver, text(node.getChildByFieldName("name"), src));
        out.symbols.add(symbol("method", nextFn, node, src));
      }
      case "variable_declarator" -> {
        TSNode value = node.getChildByFieldName("value");
        if (!value.isNull() && (value.getType().equals("arrow_function") || value.getType().equals("function_expression"))) {
          nextFn = qualify(owner, text(node.getChildByFieldName("name"), src));
          out.symbols.add(new Symbol("function", nextFn, signature(node, value.getChildByFieldName("body"), src),
              row(node), node.getEndPoint().getRow() + 1));
        }
      }
      case "decorated_definition" -> decorators(node, src, owner, out);
      case "call_expression", "call" -> call(node, src, fn == null ? owner : fn, out);
      case "string", "template_string", "interpreted_string_literal", "raw_string_literal" -> {
        String s = unquote(text(node, src));
        if (URL.matcher(s).matches() && node.getParent() != null && !isCallArgument(node)) {
          out.httpCalls.add(urlCall(null, s, fn == null ? owner : fn));
        }
      }
      default -> {
      }
    }
    for (int i = 0; i < node.getNamedChildCount(); i++) {
      walk(node.getNamedChild(i), src, nextOwner, nextFn, out);
    }
  }

  /** app.get("/x", h) → endpoint; axios.post("http://svc/x") / requests.get(url) / fetch(url) → outgoing call. */
  private void call(TSNode node, byte[] src, String from, FileIndex.Builder out) {
    TSNode callee = node.getChildByFieldName("function");
    TSNode args = node.getChildByFieldName("arguments");
    if (callee.isNull()) {
      return;
    }
    String object = "";
    String method = text(callee, src);
    if (callee.getType().equals("member_expression") || callee.getType().equals("attribute")
        || callee.getType().equals("selector_expression")) {
      TSNode obj = callee.getChildByFieldName(callee.getType().equals("selector_expression") ? "operand" : "object");
      TSNode prop = callee.getChildByFieldName(switch (callee.getType()) {
        case "attribute" -> "attribute";
        case "selector_expression" -> "field";
        default -> "property";
      });
      object = text(obj, src);
      method = text(prop, src);
    }
    String verb = method.toLowerCase(Locale.ROOT);
    String first = args.isNull() || args.getNamedChildCount() == 0 ? "" : unquote(text(args.getNamedChild(0), src));
    boolean literal = !args.isNull() && args.getNamedChildCount() > 0 && args.getNamedChild(0).getType().contains("string");
    String objectName = object.replaceAll(".*[.]", "").toLowerCase(Locale.ROOT);
    boolean registration = verb.equals("handlefunc") || verb.equals("handle") || verb.equals("route");
    if (literal && !registration && (CLIENTS.contains(objectName) || object.isEmpty() && verb.equals("fetch"))) {
      if (first.startsWith("/") || URL.matcher(first).matches()) {
        out.httpCalls.add(urlCall(VERBS.contains(verb) && !verb.equals("route") ? verb.toUpperCase(Locale.ROOT) : null, first, from));
      }
    } else if (literal && !object.isEmpty() && VERBS.contains(verb) && first.startsWith("/") && args.getNamedChildCount() >= 2) {
      String handler = text(args.getNamedChild(args.getNamedChildCount() - 1), src);
      out.endpoints.add(new Endpoint(httpVerb(verb), first, handler.matches("[\\w.]+") ? handler : from, "code"));
    }
    if (from != null && method.matches("[A-Za-z_$][\\w$]*")) {
      out.refs.add(new Ref(from, method, "calls"));
    }
  }

  /** Python: @app.get("/x") / @router.post(...) / @app.route("/x", methods=["POST"]) on a function. */
  private void decorators(TSNode node, byte[] src, String owner, FileIndex.Builder out) {
    TSNode def = node.getChildByFieldName("definition");
    String fn = qualify(owner, text(def.getChildByFieldName("name"), src));
    for (int i = 0; i < node.getNamedChildCount(); i++) {
      TSNode d = node.getNamedChild(i);
      if (!d.getType().equals("decorator") || d.getNamedChildCount() == 0 || !d.getNamedChild(0).getType().equals("call")) {
        continue;
      }
      TSNode call = d.getNamedChild(0);
      TSNode callee = call.getChildByFieldName("function");
      TSNode args = call.getChildByFieldName("arguments");
      if (!callee.getType().equals("attribute") || args.isNull() || args.getNamedChildCount() == 0) {
        continue;
      }
      String verb = text(callee.getChildByFieldName("attribute"), src).toLowerCase(Locale.ROOT);
      String path = unquote(text(args.getNamedChild(0), src));
      if (VERBS.contains(verb) && path.startsWith("/")) {
        String methods = text(args, src);
        String flask = methods.contains("methods")
            ? methods.replaceAll("(?s).*methods\\s*=\\s*\\[\\s*['\"](\\w+).*", "$1").toUpperCase(Locale.ROOT) : "GET";
        String method = verb.equals("route") ? flask : httpVerb(verb);
        out.endpoints.add(new Endpoint(method, path, fn, "code"));
      }
    }
  }

  private static String httpVerb(String verb) {
    return switch (verb) {
      case "handlefunc", "handle", "any", "route" -> "ANY";
      default -> verb.toUpperCase(Locale.ROOT);
    };
  }

  private static boolean isCallArgument(TSNode node) {
    TSNode p = node.getParent();
    return p.getType().equals("arguments") || p.getType().equals("argument_list");
  }

  private static HttpCall urlCall(String method, String target, String from) {
    Matcher m = URL.matcher(target);
    return m.matches() ? new HttpCall(method, m.group(2), m.group(3) == null ? "" : m.group(3), from)
        : new HttpCall(method, null, target, from);
  }

  private Symbol symbol(String kind, String name, TSNode node, byte[] src) {
    return new Symbol(kind, name, signature(node, node.getChildByFieldName("body"), src), row(node), node.getEndPoint().getRow() + 1);
  }

  /** Declaration text up to the body: the signature without the implementation. */
  private static String signature(TSNode node, TSNode body, byte[] src) {
    int end = body == null || body.isNull() ? node.getEndByte() : body.getStartByte();
    String s = new String(src, node.getStartByte(), Math.max(0, end - node.getStartByte()), StandardCharsets.UTF_8);
    s = s.replaceAll("\\s+", " ").replaceAll("(\\s*(=>|[:{=]))+\\s*$", "").trim();
    return s.length() > 240 ? s.substring(0, 240) + "…" : s;
  }

  private static String qualify(String owner, String name) {
    return owner == null || owner.isEmpty() ? name : owner + "." + name;
  }

  private static int row(TSNode n) {
    return n.getStartPoint().getRow() + 1;
  }

  private static String text(TSNode n, byte[] src) {
    return n == null || n.isNull() ? "" : new String(src, n.getStartByte(), n.getEndByte() - n.getStartByte(), StandardCharsets.UTF_8);
  }

  private static String unquote(String s) {
    return s.replaceAll("^[rbfu]*[\"'`]+|[\"'`]+$", "");
  }
}
