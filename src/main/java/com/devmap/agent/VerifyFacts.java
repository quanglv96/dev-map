package com.devmap.agent;

import com.devmap.git.GitService;
import com.devmap.graph.FileIndex;
import com.devmap.graph.Graph;
import com.devmap.graph.Graph.Direction;
import com.devmap.graph.Graph.Node;
import com.devmap.graph.GraphService;
import com.devmap.graph.SourceIndexer;
import com.devmap.secret.SecretFiles;
import com.devmap.ticket.TicketGitService;
import com.devmap.ticket.TicketGitService.RepoView;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * FR-43 / AIR-14 / FR-18: what SA verification must look at, computed without an LLM from git and the graph:
 * every changed file not named in analysis.md/plan.md (possible scope creep), every class/method signature or API that changed,
 * and who calls it (possible regression). SA cannot pass the ticket without addressing each flagged item in verify.md.
 */
@Component
class VerifyFacts {

  record SignatureChange(String repo, String file, String symbol, String before, String after, List<String> callers) {
  }

  record ApiChange(String repo, String endpoint, String change, List<String> callers) {
  }

  record Facts(List<String> files, List<String> outOfScope, List<SignatureChange> signatures, List<ApiChange> apis,
      List<String> notes) {

    /** Names verify.md must mention: out-of-scope files, and changed symbols/APIs somebody calls. */
    List<String> mustMention() {
      Set<String> out = new LinkedHashSet<>();
      outOfScope.forEach(f -> out.add(fileName(f)));
      signatures.stream().filter(s -> !s.callers().isEmpty()).forEach(s -> out.add(s.symbol()));
      apis.stream().filter(a -> !a.callers().isEmpty()).forEach(a -> out.add(a.endpoint()));
      return List.copyOf(out);
    }

    String markdown() {
      StringBuilder sb = new StringBuilder("## Dữ kiện kiểm chứng (DevMap tính từ git diff và graph)\n\n### File đã đổi\n");
      files.forEach(f -> sb.append("- ").append(f).append('\n'));
      sb.append("\n### File không được nêu trong analysis.md/plan.md (có thể lệch phạm vi)\n");
      list(sb, outOfScope);
      sb.append("\n### Chữ ký class/method bị đổi hoặc xoá\n");
      list(sb, signatures.stream().map(s -> s.repo() + ":" + s.file() + " `" + s.symbol() + "`: `" + s.before() + "` → `"
          + (s.after() == null ? "(đã xoá)" : s.after()) + "`; nơi gọi: " + (s.callers().isEmpty() ? "không thấy trong graph"
          : String.join(", ", s.callers()))).toList());
      sb.append("\n### API/contract bị đổi (FR-18)\n");
      list(sb, apis.stream().map(a -> a.repo() + " `" + a.endpoint() + "` " + a.change() + "; service gọi: "
          + (a.callers().isEmpty() ? "không thấy" : String.join(", ", a.callers()))).toList());
      if (!notes.isEmpty()) {
        sb.append("\n### Ghi chú\n");
        list(sb, notes);
      }
      List<String> must = mustMention();
      sb.append("\n### verify.md phải nêu rõ từng mục\n");
      list(sb, must.stream().map(m -> "`" + m + "`").toList());
      return sb.toString();
    }

    private static void list(StringBuilder sb, List<String> items) {
      if (items.isEmpty()) {
        sb.append("- (không có)\n");
      }
      items.forEach(i -> sb.append("- ").append(i).append('\n'));
    }
  }

  private final TicketGitService dev;
  private final GraphService graphs;
  private final GitService git;
  private final SourceIndexer indexer;

  VerifyFacts(TicketGitService dev, GraphService graphs, GitService git, SourceIndexer indexer) {
    this.dev = dev;
    this.graphs = graphs;
    this.git = git;
    this.indexer = indexer;
  }

  /** scopeText: analysis.md + plan.md, where every intended file or class should be named. */
  Facts compute(String project, String taskId, String scopeText) {
    String scope = scopeText.toLowerCase(Locale.ROOT);
    Graph g = graphs.graph(project);
    List<String> files = new ArrayList<>();
    List<String> outOfScope = new ArrayList<>();
    List<SignatureChange> sigs = new ArrayList<>();
    List<ApiChange> apis = new ArrayList<>();
    List<String> notes = new ArrayList<>();
    if (g.nodeCount() == 0 || g.edges().stream().noneMatch(e -> !e.type().equals("contains"))) {
      notes.add("Graph chưa có chỉ mục: không tìm được nơi gọi; SA tự kiểm bằng search_graph/get_snippet hoặc PMO bấm Quét toàn bộ");
    }
    for (RepoView r : dev.view(project, taskId).repos()) {
      if (r.head() == null) {
        notes.add(r.repo() + ": worktree không còn");
        continue;
      }
      Set<String> changed = new LinkedHashSet<>(r.files());
      changed.addAll(r.uncommitted());
      Path wt = Path.of(r.worktree());
      for (String f : changed) {
        files.add(r.repo() + ":" + f);
        if (SecretFiles.isSecret(f)) {
          notes.add(r.repo() + ":" + f + " là file bí mật: không đọc, không được commit");
          continue;
        }
        if (!isTest(f) && !scope.contains(stem(f).toLowerCase(Locale.ROOT))) {
          outOfScope.add(r.repo() + ":" + f);
        }
        if (indexer.indexable(f)) {
          compare(g, r.repo(), f, extract(f, git.show(wt, r.baseCommit(), f)), extract(f, read(wt.resolve(f))), sigs, apis);
        }
      }
    }
    return new Facts(files, outOfScope, sigs, apis, notes);
  }

  private void compare(Graph g, String repo, String file, FileIndex before, FileIndex after, List<SignatureChange> sigs,
      List<ApiChange> apis) {
    if (before == null) {
      return; // new file: nothing existing can break
    }
    Map<String, String> now = signatures(after);
    List<Node> declared = g.nodesIn(repo, file);
    signatures(before).forEach((name, sig) -> {
      String nowSig = now.get(name);
      if (!sig.equals(nowSig)) {
        sigs.add(new SignatureChange(repo, file, name, sig, nowSig, callers(g, declared.stream()
            .filter(n -> n.label().equals(name) && !n.kind().equals("api")).map(Node::id).toList())));
      }
    });
    Set<String> nowApis = after == null ? Set.of()
        : after.endpoints().stream().map(e -> e.method() + " " + e.path()).collect(Collectors.toSet());
    for (FileIndex.Endpoint e : before.endpoints()) {
      String key = e.method() + " " + e.path();
      if (!nowApis.contains(key)) {
        apis.add(new ApiChange(repo, key, "bị xoá hoặc đổi đường dẫn/method", callers(g, declared.stream()
            .filter(n -> n.kind().equals("api") && n.label().equals(key)).map(Node::id).toList())));
      }
    }
  }

  /** Who depends on the nodes (1 hop); for an API handler also the services calling that API over HTTP (FR-18). */
  private static List<String> callers(Graph g, List<String> ids) {
    Set<String> out = new LinkedHashSet<>();
    for (Node n : g.neighborhood(ids, 1, Direction.IN, 50).nodes()) {
      if (ids.contains(n.id())) {
        continue;
      }
      out.add(n.service() + ":" + n.label());
      if (n.kind().equals("api")) {
        g.neighborhood(List.of(n.id()), 1, Direction.IN, 50).nodes().stream().filter(c -> !c.id().equals(n.id()))
            .forEach(c -> out.add(c.service() + ":" + c.label()));
      }
    }
    return List.copyOf(out);
  }

  private static Map<String, String> signatures(FileIndex idx) {
    Map<String, String> out = new TreeMap<>();
    if (idx != null) {
      idx.symbols().forEach(s -> out.merge(s.name(), s.signature(), (a, b) -> a + " | " + b));
    }
    return out;
  }

  private FileIndex extract(String path, String text) {
    try {
      return text == null ? null : indexer.extract(path, text.getBytes(StandardCharsets.UTF_8));
    } catch (RuntimeException | LinkageError e) {
      return null;
    }
  }

  private static String read(Path f) {
    try {
      return Files.exists(f) ? Files.readString(f) : null;
    } catch (IOException e) {
      return null;
    }
  }

  static boolean isTest(String path) {
    String p = path.toLowerCase(Locale.ROOT);
    String name = fileName(path);
    return p.contains("/test/") || p.startsWith("test/") || p.contains("/tests/") || p.contains("__tests__/")
        || name.matches(".*(Test|Tests|IT)\\.java") || name.matches(".*[._](test|spec)\\.[a-z]+") || name.matches("test_.*\\.py");
  }

  static String fileName(String path) {
    return path.substring(path.lastIndexOf('/') + 1);
  }

  private static String stem(String path) {
    String name = fileName(path);
    int dot = name.lastIndexOf('.');
    return dot > 0 ? name.substring(0, dot) : name;
  }
}
