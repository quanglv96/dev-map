package com.devmap.rule;

import com.devmap.config.Project;
import com.devmap.config.ProjectService;
import com.devmap.config.RepoConfig;
import com.devmap.git.GitService;
import com.devmap.secret.SecretFiles;
import com.devmap.secret.SecretMasker;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * FR-33: conventions.md of a project. DevMap collects a draft from the code and lint/formatter configs of every repo's base
 * branch (read from git, no LLM, secret files never opened); the PMO edits, adds their own standard and approves it.
 * Only the section between the devmap:collected markers is rewritten by a new collection.
 */
@Service
public class ConventionService {

  public record View(String content, boolean approved) {
  }

  private static final String BEGIN = "<!-- devmap:collected -->";
  private static final String END = "<!-- /devmap:collected -->";
  private static final Pattern SECTION = Pattern.compile(Pattern.quote(BEGIN) + ".*?" + Pattern.quote(END), Pattern.DOTALL);
  private static final Pattern STATUS = Pattern.compile("(?m)^> Trạng thái:.*$");
  private static final String DRAFT = "> Trạng thái: **bản nháp, chờ PMO duyệt**";
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
  private static final Set<String> SKIP = Set.of("node_modules", "target", "build", "dist", "out", "vendor", ".git", ".idea");
  private static final Map<String, String> LANGS = Map.of("java", "Java", "ts", "TypeScript", "tsx", "TypeScript", "js", "JavaScript",
      "jsx", "JavaScript", "py", "Python", "go", "Go");
  private static final Pattern LINT = Pattern.compile("(^|.*/)(\\.editorconfig|checkstyle[^/]*\\.xml|pmd[^/]*\\.xml|\\.eslintrc[^/]*"
      + "|eslint\\.config\\.[cm]?js|\\.prettierrc[^/]*|\\.golangci\\.ya?ml|ruff\\.toml|pyproject\\.toml|setup\\.cfg|pom\\.xml|package\\.json)$");
  private static final int MAX_FILES = 400;

  private final ProjectService projects;
  private final RuleRepository repo;
  private final GitService git;
  private final SecretMasker masker;

  public ConventionService(ProjectService projects, RuleRepository repo, GitService git, SecretMasker masker) {
    this.projects = projects;
    this.repo = repo;
    this.git = git;
    this.masker = masker;
  }

  public View view(String project) {
    String text = repo.readConventions(dir(project));
    return new View(text, isApproved(text));
  }

  /** Rewrites the collected section from the base branches; the PMO's own text stays. Back to draft. */
  public synchronized View collect(String project) {
    Project p = project(project);
    StringBuilder sb = new StringBuilder(BEGIN).append("\n## Thu thập từ code (DevMap, ").append(now()).append(")\n");
    for (RepoConfig rc : p.config().repos()) {
      String head = git.resolveBranch(Path.of(rc.path()), rc.baseBranch());
      sb.append("\n### ").append(rc.name()).append(" (`").append(rc.baseBranch()).append("`)\n\n");
      if (head == null) {
        sb.append("Không đọc được nhánh nền.\n");
        continue;
      }
      Stats stats = new Stats();
      git.readFiles(Path.of(rc.path()), head, this::wanted, stats::add);
      sb.append(stats.markdown());
    }
    sb.append(END);
    String text = repo.readConventions(dir(project)).replace("\nChưa thu thập.\n", "\n");
    Matcher m = SECTION.matcher(text);
    text = m.find() ? m.replaceFirst(Matcher.quoteReplacement(sb.toString())) : text.stripTrailing() + "\n\n" + sb + "\n";
    return save(project, withStatus(text, DRAFT));
  }

  /** PMO's edit (e.g. pasting their standard): masked, kept as a draft until approved. */
  public synchronized View write(String project, String content) {
    return save(project, withStatus(content == null ? "" : content, DRAFT));
  }

  public synchronized View approve(String project) {
    String text = repo.readConventions(dir(project));
    return save(project, withStatus(text, "> Trạng thái: **PMO đã duyệt** (" + now() + ")"));
  }

  private View save(String project, String text) {
    String masked = masker.mask(text, project(project).config().secrets()).text();
    repo.writeConventions(dir(project), masked);
    return new View(masked, isApproved(masked));
  }

  private static boolean isApproved(String text) {
    Matcher m = STATUS.matcher(text);
    return m.find() && m.group().contains("đã duyệt");
  }

  private static String withStatus(String text, String status) {
    Matcher m = STATUS.matcher(text);
    if (m.find()) {
      return m.replaceFirst(Matcher.quoteReplacement(status));
    }
    int nl = text.startsWith("# ") ? text.indexOf('\n') : -1;
    return nl < 0 ? status + "\n\n" + text : text.substring(0, nl + 1) + "\n" + status + "\n" + text.substring(nl + 1);
  }

  private boolean wanted(String path) {
    if (SecretFiles.isSecret(path) || Arrays.stream(path.split("/")).anyMatch(SKIP::contains)) {
      return false;
    }
    return LINT.matcher(path).matches() || LANGS.containsKey(ext(path));
  }

  private static String ext(String path) {
    int dot = path.lastIndexOf('.');
    return dot < 0 ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
  }

  /** Counts over the sampled sources of one repo, plus the lint configs found. */
  private static final class Stats {
    final Map<String, int[]> files = new TreeMap<>();
    final Map<String, List<Integer>> lengths = new TreeMap<>();
    final Map<String, int[]> indent = new TreeMap<>(); // tab, 2-space, 4-space
    int singleQuotes;
    int doubleQuotes;
    int semicolonLines;
    int jsLines;
    int fieldInjection;
    int lombok;
    final Set<String> layers = new TreeSet<>();
    final Map<String, String> configs = new LinkedHashMap<>();
    final Set<String> testLibs = new TreeSet<>();

    void add(String path, byte[] bytes) {
      String text = new String(bytes, StandardCharsets.UTF_8);
      if (LINT.matcher(path).matches()) {
        config(path, text);
        if (!LANGS.containsKey(ext(path))) {
          return;
        }
      }
      String lang = LANGS.get(ext(path));
      int[] n = files.computeIfAbsent(lang, k -> new int[1]);
      if (path.contains("/test/") || path.contains("/tests/") || path.contains(".test.") || path.contains(".spec.") || ++n[0] > MAX_FILES) {
        return;
      }
      int[] ind = indent.computeIfAbsent(lang, k -> new int[3]);
      List<Integer> len = lengths.computeIfAbsent(lang, k -> new ArrayList<>());
      for (String line : text.split("\n", -1)) {
        String l = line.replace("\r", "");
        if (l.isBlank()) {
          continue;
        }
        len.add(l.length());
        int spaces = l.length() - l.stripLeading().length();
        if (l.startsWith("\t")) {
          ind[0]++;
        } else if (spaces > 0 && !l.stripLeading().startsWith("*")) {
          ind[spaces % 4 == 0 ? 2 : 1]++;
        }
        if (lang.equals("JavaScript") || lang.equals("TypeScript")) {
          jsLines++;
          semicolonLines += l.stripTrailing().endsWith(";") ? 1 : 0;
          singleQuotes += count(l, '\'');
          doubleQuotes += count(l, '"');
        }
      }
      if (lang.equals("Java")) {
        fieldInjection += countMatches(text, "@Autowired\\s+(private|protected)?\\s*\\w");
        lombok += text.contains("import lombok.") ? 1 : 0;
        Matcher m = Pattern.compile("(?m)^package\\s+[\\w.]*\\.(controller|web|rest|api|service|repository|dao|domain|entity|dto)\\b")
            .matcher(text);
        if (m.find()) {
          layers.add(m.group(1));
        }
      }
    }

    private void config(String path, String text) {
      String name = path.substring(path.lastIndexOf('/') + 1);
      if (name.equals("pom.xml") || name.equals("package.json")) {
        for (String lib : List.of("junit-jupiter", "mockito", "assertj", "testcontainers", "spring-boot-starter-test", "jest", "vitest",
            "mocha", "lombok", "mapstruct")) {
          if (text.contains(lib)) {
            testLibs.add(lib);
          }
        }
        return;
      }
      if (name.equals("pyproject.toml") && !text.contains("[tool.")) {
        return;
      }
      List<String> lines = text.lines().filter(l -> !l.isBlank()).toList();
      String shown = name.startsWith("checkstyle") || name.startsWith("pmd")
          ? String.join("\n", lines.stream().filter(l -> l.contains("<module") || l.contains("<rule") || l.contains("value=")).limit(40).toList())
          : String.join("\n", lines.subList(0, Math.min(40, lines.size())));
      configs.put(path, shown);
    }

    String markdown() {
      StringBuilder sb = new StringBuilder();
      if (files.isEmpty() && configs.isEmpty()) {
        return "Không thấy mã nguồn hay file cấu hình lint.\n";
      }
      files.forEach((lang, n) -> {
        int[] ind = indent.get(lang);
        List<Integer> len = lengths.get(lang);
        sb.append("- **").append(lang).append("** (").append(n[0]).append(" file): thụt lề ").append(indentText(ind));
        if (len != null && !len.isEmpty()) {
          List<Integer> sorted = len.stream().sorted().toList();
          sb.append(", dòng dài 99% ≤ ").append(sorted.get((int) (sorted.size() * 0.99))).append(" ký tự (dài nhất ")
              .append(sorted.get(sorted.size() - 1)).append(')');
        }
        sb.append('\n');
      });
      if (files.containsKey("Java")) {
        sb.append("- Java: tách package theo lớp ").append(layers.isEmpty() ? "(không rõ)" : String.join(", ", layers))
            .append("; ").append(fieldInjection == 0 ? "inject qua constructor" : fieldInjection + " chỗ @Autowired trên field")
            .append(lombok > 0 ? "; có dùng Lombok (" + lombok + " file)" : "").append('\n');
      }
      if (jsLines > 0) {
        sb.append("- JS/TS: ").append(singleQuotes >= doubleQuotes ? "nháy đơn" : "nháy kép").append(", ")
            .append(semicolonLines * 2 >= jsLines ? "có dấu chấm phẩy" : "không dấu chấm phẩy").append('\n');
      }
      if (!testLibs.isEmpty()) {
        sb.append("- Thư viện test/hỗ trợ: ").append(String.join(", ", testLibs)).append('\n');
      }
      configs.forEach((path, shown) -> sb.append("\n`").append(path).append("`\n\n```\n").append(shown).append("\n```\n"));
      return sb.toString();
    }

    private static String indentText(int[] ind) {
      if (ind == null || ind[0] + ind[1] + ind[2] == 0) {
        return "(không rõ)";
      }
      if (ind[0] > ind[1] + ind[2]) {
        return "tab";
      }
      return ind[1] * 5 > ind[2] ? "2 dấu cách" : "4 dấu cách";
    }

    private static int count(String s, char c) {
      return (int) s.chars().filter(ch -> ch == c).count();
    }

    private static int countMatches(String s, String regex) {
      Matcher m = Pattern.compile(regex).matcher(s);
      int n = 0;
      while (m.find()) {
        n++;
      }
      return n;
    }
  }

  private static String now() {
    return LocalDateTime.now().format(TIME);
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
