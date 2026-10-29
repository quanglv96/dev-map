package com.devmap.graph;

import com.devmap.graph.FileIndex.Endpoint;
import com.devmap.graph.FileIndex.HttpCall;
import com.devmap.graph.FileIndex.Ref;
import com.devmap.graph.FileIndex.Symbol;
import com.devmap.graph.FileIndex.TableRef;
import com.devmap.graph.FileIndex.TopicRef;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java via JavaParser, no symbol solver: calls are resolved through field/parameter types (constructor injection in
 * Spring), which is what the 2–3 layer graph needs. Detects Spring MVC mappings, Feign clients, RestClient/RestTemplate
 * calls, Kafka/Rabbit/JMS publish+listen, JPA entities and repositories.
 */
class JavaExtractor {

  private static final Map<String, String> MAPPINGS = Map.of("GetMapping", "GET", "PostMapping", "POST",
      "PutMapping", "PUT", "DeleteMapping", "DELETE", "PatchMapping", "PATCH", "RequestMapping", "");
  private static final Set<String> VERBS = Set.of("get", "post", "put", "delete", "patch");
  private static final Pattern REST_TEMPLATE = Pattern.compile("(get|post|put|delete|patch)(ForObject|ForEntity)?|exchange");
  private static final Pattern BROKER = Pattern.compile(".*(Kafka|Rabbit|Amqp|Jms|StreamBridge).*");
  private static final Set<String> PUBLISH = Set.of("send", "sendDefault", "convertAndSend", "publish");
  private static final Map<String, String> LISTENERS = Map.of("KafkaListener", "topics", "RabbitListener", "queues",
      "JmsListener", "destination");
  private static final Set<String> REPOSITORIES = Set.of("JpaRepository", "CrudRepository", "PagingAndSortingRepository",
      "ListCrudRepository", "MongoRepository", "ReactiveCrudRepository", "R2dbcRepository");
  private static final Pattern URL = Pattern.compile("^(https?)://([^/:\"]+)(?::\\d+)?(/.*)?$");
  private static final Set<String> JDK = Set.of("String", "Integer", "Long", "Double", "Float", "Boolean", "Object", "List",
      "Map", "Set", "Optional", "BigDecimal", "LocalDate", "LocalDateTime", "Instant", "UUID", "Logger", "Collection");

  private final JavaParser parser = new JavaParser(
      new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));

  FileIndex extract(String source) {
    FileIndex.Builder out = new FileIndex.Builder("java");
    Optional<CompilationUnit> cu = parser.parse(source).getResult();
    if (cu.isEmpty()) {
      return out.build();
    }
    Map<String, String> constants = new HashMap<>();
    cu.get().findAll(FieldDeclaration.class).stream().filter(f -> f.isStatic() && f.isFinal()).forEach(f -> f.getVariables()
        .forEach(v -> v.getInitializer().filter(Expression::isStringLiteralExpr)
            .ifPresent(i -> constants.put(v.getNameAsString(), i.asStringLiteralExpr().getValue()))));
    for (TypeDeclaration<?> type : cu.get().findAll(TypeDeclaration.class)) {
      typeDecl(type, constants, out);
    }
    return out.build();
  }

  private void typeDecl(TypeDeclaration<?> type, Map<String, String> constants, FileIndex.Builder out) {
    String cls = type.getNameAsString();
    String kind = type.isEnumDeclaration() ? "enum"
        : type instanceof ClassOrInterfaceDeclaration c && c.isInterface() ? "interface" : "class";
    out.symbols.add(new Symbol(kind, cls, typeSignature(type, kind), line(type), endLine(type)));

    Map<String, String> fieldTypes = new HashMap<>();
    for (FieldDeclaration f : type.getFields()) {
      f.getVariables().forEach(v -> fieldTypes.put(v.getNameAsString(), raw(v.getType())));
    }
    fieldTypes.values().stream().distinct().filter(JavaExtractor::projectType)
        .forEach(t -> out.refs.add(new Ref(cls, t, "depends")));
    if (type instanceof ClassOrInterfaceDeclaration c) {
      for (ClassOrInterfaceType sup : concat(c.getExtendedTypes(), c.getImplementedTypes())) {
        if (REPOSITORIES.contains(sup.getNameAsString())) {
          sup.getTypeArguments().filter(a -> !a.isEmpty()).ifPresent(a -> out.refs.add(new Ref(cls, raw(a.get(0)), "entity")));
        } else if (projectType(sup.getNameAsString())) {
          out.refs.add(new Ref(cls, sup.getNameAsString(), "extends"));
        }
      }
    }
    if (annotation(type, "Entity").isPresent()) {
      String table = annotation(type, "Table").flatMap(a -> attr(a, "name", constants)).orElse(snake(cls));
      out.tables.add(new TableRef(table, cls));
    }
    Optional<String> feign = annotation(type, "FeignClient")
        .flatMap(a -> attr(a, "name", constants).or(() -> attr(a, "value", constants)));
    String prefix = annotation(type, "RequestMapping").flatMap(a -> attr(a, "value", constants)
        .or(() -> attr(a, "path", constants))).orElse("");

    for (MethodDeclaration m : type.getMethods()) {
      String fn = cls + "." + m.getNameAsString();
      out.symbols.add(new Symbol("method", fn, m.getDeclarationAsString(true, true, true), line(m), endLine(m)));
      for (AnnotationExpr a : m.getAnnotations()) {
        String verb = MAPPINGS.get(a.getNameAsString());
        if (verb != null) {
          String path = join(prefix, attr(a, "value", constants).or(() -> attr(a, "path", constants)).orElse(""));
          String method = verb.isEmpty() ? requestMethod(a) : verb;
          if (feign.isPresent()) {
            out.httpCalls.add(new HttpCall(method, feign.get(), path, fn));
          } else {
            out.endpoints.add(new Endpoint(method, path, fn, "code"));
          }
        }
        String topicsAttr = LISTENERS.get(a.getNameAsString());
        if (topicsAttr != null) {
          strings(a, topicsAttr, constants).forEach(t -> out.topics.add(new TopicRef(t, "consume", fn)));
        }
      }
      Map<String, String> locals = new HashMap<>(fieldTypes);
      for (Parameter p : m.getParameters()) {
        locals.put(p.getNameAsString(), raw(p.getType()));
      }
      m.findAll(MethodCallExpr.class).forEach(call -> methodCall(cls, fn, call, locals, constants, out));
    }
    type.findAll(StringLiteralExpr.class).stream().map(StringLiteralExpr::getValue).filter(s -> URL.matcher(s).matches())
        .forEach(s -> out.httpCalls.add(urlCall(null, s, cls)));
  }

  private void methodCall(String cls, String fn, MethodCallExpr call, Map<String, String> locals,
      Map<String, String> constants, FileIndex.Builder out) {
    String name = call.getNameAsString();
    Optional<Expression> scope = call.getScope();
    String scopeType = scope.map(s -> scopeType(s, locals)).orElse(cls);
    if (scopeType != null && projectType(scopeType)) {
      out.refs.add(new Ref(fn, scopeType + "." + name, "calls"));
    }
    if (PUBLISH.contains(name) && scopeType != null && BROKER.matcher(scopeType).matches() && call.getArguments().isNonEmpty()) {
      resolve(call.getArgument(0), constants).ifPresent(t -> out.topics.add(new TopicRef(t, "publish", fn)));
    }
    boolean uri = name.equals("uri");
    if ((uri || REST_TEMPLATE.matcher(name).matches()) && call.getArguments().isNonEmpty()) {
      Optional<String> target = resolve(call.getArgument(0), constants);
      if (target.isPresent() && (target.get().startsWith("/") || URL.matcher(target.get()).matches())) {
        String verb = uri ? chainVerb(call) : name.startsWith("exchange") ? null : name.replaceAll("For.*", "").toUpperCase(Locale.ROOT);
        out.httpCalls.add(urlCall(verb, target.get(), fn));
      }
    }
  }

  /** http.post().uri("/x") → POST: the verb is a call earlier in the fluent chain. */
  private static String chainVerb(MethodCallExpr call) {
    Optional<Expression> s = call.getScope();
    while (s.isPresent() && s.get().isMethodCallExpr()) {
      MethodCallExpr m = s.get().asMethodCallExpr();
      if (VERBS.contains(m.getNameAsString())) {
        return m.getNameAsString().toUpperCase(Locale.ROOT);
      }
      s = m.getScope();
    }
    return null;
  }

  private static HttpCall urlCall(String method, String target, String from) {
    Matcher m = URL.matcher(target);
    if (m.matches()) {
      return new HttpCall(method, m.group(2), m.group(3) == null ? "" : m.group(3), from);
    }
    return new HttpCall(method, null, target, from);
  }

  private static String scopeType(Expression scope, Map<String, String> locals) {
    if (scope instanceof NameExpr n) {
      String t = locals.get(n.getNameAsString());
      return t != null ? t : Character.isUpperCase(n.getNameAsString().charAt(0)) ? n.getNameAsString() : null;
    }
    if (scope instanceof FieldAccessExpr f && f.getScope() instanceof ThisExpr) {
      return locals.get(f.getNameAsString());
    }
    return null;
  }

  /** Literal, local constant, or concatenation keeping the literal parts ("base + \"/api/x\"" → "/api/x"). */
  private static Optional<String> resolve(Expression e, Map<String, String> constants) {
    if (e instanceof StringLiteralExpr s) {
      return Optional.of(s.getValue());
    }
    if (e instanceof NameExpr n) {
      return Optional.ofNullable(constants.get(n.getNameAsString()));
    }
    if (e instanceof FieldAccessExpr f) {
      return Optional.ofNullable(constants.get(f.getNameAsString()));
    }
    if (e instanceof BinaryExpr b && b.getOperator() == BinaryExpr.Operator.PLUS) {
      String joined = resolve(b.getLeft(), constants).orElse("") + resolve(b.getRight(), constants).orElse("");
      return joined.isEmpty() ? Optional.empty() : Optional.of(joined);
    }
    return Optional.empty();
  }

  private static Optional<AnnotationExpr> annotation(BodyDeclaration<?> decl, String name) {
    return decl.getAnnotations().stream().filter(a -> a.getNameAsString().equals(name)).findFirst();
  }

  private static Optional<String> attr(AnnotationExpr a, String name, Map<String, String> constants) {
    List<String> values = strings(a, name, constants);
    return values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
  }

  /** String values of an annotation attribute; "value" also matches the single-member form @X("..."). */
  private static List<String> strings(AnnotationExpr a, String name, Map<String, String> constants) {
    Expression v = null;
    if (a instanceof SingleMemberAnnotationExpr s && (name.equals("value") || name.equals("topics") || name.equals("queues")
        || name.equals("destination"))) {
      v = s.getMemberValue();
    } else if (a instanceof NormalAnnotationExpr n) {
      v = n.getPairs().stream().filter(p -> p.getNameAsString().equals(name)).map(p -> p.getValue()).findFirst().orElse(null);
    }
    if (v == null) {
      return List.of();
    }
    List<Expression> items = v instanceof ArrayInitializerExpr arr ? arr.getValues() : List.of(v);
    return items.stream().map(i -> resolve(i, constants)).flatMap(Optional::stream).toList();
  }

  private static String requestMethod(AnnotationExpr a) {
    if (a instanceof NormalAnnotationExpr n) {
      return n.getPairs().stream().filter(p -> p.getNameAsString().equals("method"))
          .map(p -> p.getValue().toString().replaceAll(".*\\.", "").replaceAll("[^A-Z]", "")).findFirst().orElse("ANY");
    }
    return "ANY";
  }

  private static String typeSignature(TypeDeclaration<?> type, String kind) {
    StringBuilder sb = new StringBuilder();
    type.getAnnotations().forEach(a -> sb.append('@').append(a.getNameAsString()).append(' '));
    sb.append(kind).append(' ').append(type.getNameAsString());
    if (type instanceof ClassOrInterfaceDeclaration c) {
      if (c.getExtendedTypes().isNonEmpty()) {
        sb.append(" extends ").append(String.join(", ", c.getExtendedTypes().stream().map(Object::toString).toList()));
      }
      if (c.getImplementedTypes().isNonEmpty()) {
        sb.append(" implements ").append(String.join(", ", c.getImplementedTypes().stream().map(Object::toString).toList()));
      }
    }
    return sb.toString();
  }

  private static List<ClassOrInterfaceType> concat(List<ClassOrInterfaceType> a, List<ClassOrInterfaceType> b) {
    return java.util.stream.Stream.concat(a.stream(), b.stream()).toList();
  }

  private static String raw(Type t) {
    return t.isClassOrInterfaceType() ? t.asClassOrInterfaceType().getNameAsString() : t.asString();
  }

  private static boolean projectType(String t) {
    return !t.isEmpty() && Character.isUpperCase(t.charAt(0)) && !JDK.contains(t);
  }

  private static String join(String prefix, String path) {
    String p = (prefix.isEmpty() || prefix.startsWith("/") ? prefix : "/" + prefix).replaceAll("/$", "");
    String s = path.isEmpty() ? "" : path.startsWith("/") ? path : "/" + path;
    return (p + s).isEmpty() ? "/" : p + s;
  }

  private static String snake(String name) {
    return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
  }

  private static int line(Node n) {
    return n.getBegin().map(p -> p.line).orElse(0);
  }

  private static int endLine(Node n) {
    return n.getEnd().map(p -> p.line).orElse(0);
  }
}
