package com.devmap.rule;

import com.devmap.config.Project;
import com.devmap.config.ProjectService;
import com.devmap.config.RepoConfig;
import com.devmap.config.ServiceInfo;
import com.devmap.config.Yaml;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * FR-29 → FR-32: rules per service in .devrules.yml files, inheriting the project default, on top of a preset chosen by the
 * service kind. The UI always edits a full {@link Rules}; only the differences from the level above are stored.
 */
@Service
public class RuleService {

  /** A service of a repo: the unit rules and quality gates apply to. path is relative to the repo root. */
  public record Unit(String repo, String service, String path, String kind) {
  }

  /** presets: preset name to its quality settings (snake_case), so the UI can fill the commands when the PMO picks one. */
  public record View(Rules projectDefault, Map<String, Rules> services, Map<String, Boolean> overridden, Map<String, JsonNode> presets,
      List<String> lockedBaseBranches) {
  }

  private static final JsonNode BUILTIN;

  static {
    try (InputStream in = RuleService.class.getResourceAsStream("/rules/presets.yml")) {
      BUILTIN = Yaml.readTree(in);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private final ProjectService projects;
  private final RuleRepository repo;
  private final QualityGate gate;

  public RuleService(ProjectService projects, RuleRepository repo, QualityGate gate) {
    this.projects = projects;
    this.repo = repo;
    this.gate = gate;
  }

  public static List<String> presets() {
    List<String> out = new ArrayList<>();
    BUILTIN.path("presets").fieldNames().forEachRemaining(out::add);
    return out;
  }

  /** Every service of every repo; a repo without a detected service counts as one service named after it. */
  public List<Unit> units(String project) {
    List<Unit> out = new ArrayList<>();
    for (RepoConfig r : project(project).config().repos()) {
      if (r.services().isEmpty()) {
        out.add(new Unit(r.name(), r.name(), "", null));
      }
      for (ServiceInfo s : r.services()) {
        out.add(new Unit(r.name(), s.name(), s.path(), s.kind()));
      }
    }
    return out;
  }

  public Rules effective(String project, String service) {
    Unit u = unit(project, service);
    return toRules(merged(dir(project), u));
  }

  public Rules projectDefault(String project) {
    return toRules(parent(dir(project), null, null));
  }

  public View view(String project) {
    Path dir = dir(project);
    Map<String, Rules> services = new LinkedHashMap<>();
    Map<String, Boolean> overridden = new LinkedHashMap<>();
    for (Unit u : units(project)) {
      services.put(u.service(), toRules(merged(dir, u)));
      overridden.put(u.service(), repo.read(dir, u.service()).size() > 0);
    }
    Map<String, JsonNode> presets = new LinkedHashMap<>();
    presets().forEach(p -> presets.put(p, BUILTIN.path("presets").path(p).path("quality")));
    return new View(projectDefault(project), services, overridden, presets, baseBranches(project));
  }

  public synchronized Rules saveDefault(String project, Rules rules) {
    requireValid(rules);
    Path dir = dir(project);
    JsonNode full = Yaml.toTree(rules);
    repo.write(dir, RuleRepository.DEFAULT, diff(full, withPreset(BUILTIN.path("defaults"), full.path("preset").asText(null))));
    return projectDefault(project);
  }

  public synchronized Rules saveService(String project, String service, Rules rules) {
    requireValid(rules);
    Unit u = unit(project, service);
    Path dir = dir(project);
    JsonNode full = Yaml.toTree(rules);
    String preset = full.path("preset").asText(null);
    JsonNode stored = diff(full, parent(dir, preset == null ? presetOf(dir, u, null) : preset, u));
    if (stored.size() == 0) {
      repo.delete(dir, service);
    } else {
      repo.write(dir, service, stored);
    }
    return effective(project, service);
  }

  /** Back to inheriting everything from the project default. */
  public synchronized void resetService(String project, String service) {
    unit(project, service);
    repo.delete(dir(project), service);
  }

  /** FR-30, cannot be configured away: the project's protected list plus every repo's base branch. */
  public List<String> protectedBranches(String project) {
    Set<String> out = new LinkedHashSet<>(projectDefault(project).git().protectedBranches());
    out.addAll(baseBranches(project));
    return List.copyOf(out);
  }

  public boolean isProtected(String project, String branch) {
    return protectedBranches(project).stream().anyMatch(p -> Glob.matches("/" + p, "/" + branch));
  }

  /** FR-26: every commit message carries the ticket code. */
  public String commitMessage(String project, String code, String message) {
    String text = projectDefault(project).git().commitTemplate().replace("{code}", code).replace("{message}", message.strip());
    return text.contains(code) ? text : code + ": " + text;
  }

  /** Runs the quality gate of every service of a repo, in its folder under root (a ticket worktree or the repo itself). */
  public List<QualityGate.Result> runGate(String project, String repoName, Path root) {
    return units(project).stream().filter(u -> u.repo().equals(repoName))
        .map(u -> gate.run(u.service(), u.path().isEmpty() ? root : root.resolve(u.path()), effective(project, u.service()).quality()))
        .toList();
  }

  /** Changed files of a repo sorted by what they need (FR-32): forbidden, PMO approval (schema, dependency), or fine. */
  public record ScopeReport(List<String> forbidden, List<String> schema, List<String> dependency) {
    public boolean clean() {
      return forbidden.isEmpty() && schema.isEmpty() && dependency.isEmpty();
    }
  }

  public ScopeReport scope(String project, String repoName, List<String> changed) {
    List<Unit> units = units(project).stream().filter(u -> u.repo().equals(repoName)).toList();
    List<String> forbidden = new ArrayList<>();
    List<String> schema = new ArrayList<>();
    List<String> dependency = new ArrayList<>();
    for (String path : changed) {
      Unit owner = units.stream().filter(u -> u.path().isEmpty() || path.startsWith(u.path() + "/"))
          .max((a, b) -> Integer.compare(a.path().length(), b.path().length())).orElse(null);
      Rules.Scope s = owner == null ? projectDefault(project).scope() : effective(project, owner.service()).scope();
      String rel = owner == null || owner.path().isEmpty() ? path : path.substring(owner.path().length() + 1);
      if (Glob.any(s.forbiddenPaths(), rel)) {
        forbidden.add(path);
      } else if (Glob.any(s.schemaPaths(), rel)) {
        schema.add(path);
      } else if (Glob.any(s.dependencyFiles(), rel)) {
        dependency.add(path);
      }
    }
    return new ScopeReport(forbidden, schema, dependency);
  }

  public List<String> problems(Rules r) {
    List<String> out = new ArrayList<>();
    if (r.preset() != null && !r.preset().isBlank() && !presets().contains(r.preset())) {
      out.add("Preset phải là một trong " + presets());
    }
    if (r.git() == null || r.quality() == null || r.scope() == null) {
      out.add("Thiếu nhóm git/quality/scope");
      return out;
    }
    String tpl = r.git().commitTemplate();
    if (tpl == null || !tpl.contains("{code}") || !tpl.contains("{message}")) {
      out.add("Mẫu commit phải chứa {code} và {message}");
    }
    if (r.quality().timeoutMinutes() < 1 || r.quality().timeoutMinutes() > 240) {
      out.add("Thời gian chạy tối đa phải từ 1 đến 240 phút");
    }
    return out;
  }

  private void requireValid(Rules r) {
    List<String> p = problems(r);
    if (!p.isEmpty()) {
      throw new IllegalArgumentException(String.join("; ", p));
    }
  }

  private JsonNode merged(Path dir, Unit u) {
    JsonNode own = repo.read(dir, u.service());
    ObjectNode out = (ObjectNode) parent(dir, presetOf(dir, u, own), u);
    merge(out, own);
    return out;
  }

  /** defaults ← preset ← project default file; unit null = the project default level itself. */
  private JsonNode parent(Path dir, String preset, Unit u) {
    JsonNode def = repo.read(dir, RuleRepository.DEFAULT);
    String p = u == null ? def.path("preset").asText(null) : preset;
    ObjectNode out = (ObjectNode) withPreset(BUILTIN.path("defaults"), p);
    merge(out, def);
    if (u != null) {
      out.put("preset", p);
    }
    return out;
  }

  private String presetOf(Path dir, Unit u, JsonNode own) {
    String p = own == null ? null : own.path("preset").asText(null);
    p = p != null ? p : repo.read(dir, RuleRepository.DEFAULT).path("preset").asText(null);
    return p != null ? p : u.kind() != null && BUILTIN.path("presets").has(u.kind()) ? u.kind() : "none";
  }

  private static JsonNode withPreset(JsonNode defaults, String preset) {
    ObjectNode out = defaults.deepCopy();
    if (preset != null) {
      merge(out, BUILTIN.path("presets").path(preset));
    }
    return out;
  }

  private static void merge(ObjectNode into, JsonNode over) {
    over.fields().forEachRemaining(e -> {
      JsonNode cur = into.get(e.getKey());
      if (cur instanceof ObjectNode o && e.getValue().isObject()) {
        merge(o, e.getValue());
      } else if (!e.getValue().isNull()) {
        into.set(e.getKey(), e.getValue().deepCopy());
      }
    });
  }

  /** Fields of full that differ from base (objects compared field by field, lists as a whole). */
  private static ObjectNode diff(JsonNode full, JsonNode base) {
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    full.fields().forEachRemaining(e -> {
      JsonNode b = base.get(e.getKey());
      JsonNode v = e.getValue();
      if (v.isNull() || v.equals(b)) {
        return;
      }
      if (v.isObject() && b != null && b.isObject()) {
        ObjectNode d = diff(v, b);
        if (d.size() > 0) {
          out.set(e.getKey(), d);
        }
      } else {
        out.set(e.getKey(), v);
      }
    });
    return out;
  }

  private static Rules toRules(JsonNode tree) {
    return Yaml.convert(tree, Rules.class);
  }

  private List<String> baseBranches(String project) {
    return project(project).config().repos().stream().map(RepoConfig::baseBranch).distinct().toList();
  }

  private Unit unit(String project, String service) {
    return units(project).stream().filter(u -> u.service().equals(service)).findFirst()
        .orElseThrow(() -> new NoSuchElementException("Không có service " + service));
  }

  private Project project(String name) {
    Project p = projects.get(name);
    if (p.error() != null) {
      throw new IllegalStateException(p.error());
    }
    return p;
  }

  private Path dir(String project) {
    return Path.of(project(project).dataDir());
  }
}
