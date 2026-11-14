package com.devmap.room;

import com.devmap.ticket.Task;
import com.devmap.ticket.TaskService;
import com.devmap.ticket.TaskStatus;
import com.devmap.ticket.TicketGitService;
import com.devmap.ticket.TicketGitService.RepoView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * FR-93: which case a bug the PMO reports while implementing is, guessed locally (no LLM) by matching the files and method names it
 * mentions against the current worktree diff, the current plan.md and changes.md of the Done tickets. Not sure → the room asks.
 */
@Component
public class BugCaseGuesser {

  /** kase: B1 (in the code being written), B2 (old code, fix it here), B3 (another Done ticket), B4 (no ticket); null = ask. */
  public record Guess(String kase, boolean sure, String other, List<String> evidence) {
  }

  private static final Pattern FILE = Pattern.compile("[\\w.-]+\\.(java|kt|ts|tsx|js|jsx|py|go|cs|rb|php|vue|sql)\\b");
  private static final Pattern CAMEL = Pattern.compile("\\b[a-z]+[A-Z][A-Za-z0-9]*\\b|\\b[A-Z][a-z0-9]+[A-Z][A-Za-z0-9]*\\b");
  private static final Pattern CALL = Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]{3,})\\s*\\(");
  private static final Pattern HERE = Pattern.compile("(?iu)(sửa luôn|fix luôn|làm luôn|trong task này|trong ticket này)");

  private final TaskService tasks;
  private final TicketGitService dev;

  public BugCaseGuesser(TaskService tasks, TicketGitService dev) {
    this.tasks = tasks;
    this.dev = dev;
  }

  public Guess guess(String project, String id, String text) {
    if (HERE.matcher(text).find()) {
      return new Guess("B2", true, null, List.of("PMO muốn sửa luôn trong task này"));
    }
    List<String> tokens = tokens(text);
    if (tokens.isEmpty()) {
      return new Guess(null, false, null, List.of("không nêu file hay method nào"));
    }
    String current = currentCode(project, id);
    String plan = doc(project, id, "plan.md").toLowerCase(Locale.ROOT);
    Map<String, Integer> done = new LinkedHashMap<>();
    for (Task t : tasks.list(project)) {
      if (t.id().equals(id) || t.status() != TaskStatus.DONE) {
        continue;
      }
      String changes = doc(project, t.id(), "changes.md").toLowerCase(Locale.ROOT);
      int n = (int) tokens.stream().filter(changes::contains).count();
      if (n > 0) {
        done.put(t.id(), n);
      }
    }
    List<String> inCurrent = tokens.stream().filter(current::contains).toList();
    List<String> evidence = new ArrayList<>();
    if (!inCurrent.isEmpty()) {
      evidence.add("khớp diff của task đang làm: " + inCurrent);
    }
    done.forEach((t, n) -> evidence.add("khớp changes.md của " + t + " (" + n + ")"));
    String best = done.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    if (!inCurrent.isEmpty() && done.isEmpty()) {
      return new Guess("B1", true, null, evidence);
    }
    if (inCurrent.isEmpty() && !done.isEmpty()) {
      return new Guess("B3", done.size() == 1, best, evidence);
    }
    if (!inCurrent.isEmpty()) {
      return new Guess("B1", false, best, evidence); // both: ask
    }
    if (tokens.stream().anyMatch(plan::contains)) {
      evidence.add("chỉ khớp plan.md (code cũ cùng khu vực?)");
      return new Guess("B2", false, null, evidence);
    }
    evidence.add("không khớp task nào: " + tokens);
    return new Guess("B4", true, null, evidence);
  }

  /** Files and identifiers the PMO named, lowercased: "calculateRefund", "RefundService.java", "send(". */
  static List<String> tokens(String text) {
    Set<String> out = new LinkedHashSet<>();
    Matcher f = FILE.matcher(text);
    while (f.find()) {
      out.add(f.group().toLowerCase(Locale.ROOT));
    }
    Matcher c = CAMEL.matcher(text);
    while (c.find()) {
      out.add(c.group().toLowerCase(Locale.ROOT));
    }
    Matcher call = CALL.matcher(text);
    while (call.find()) {
      out.add(call.group(1).toLowerCase(Locale.ROOT));
    }
    return out.stream().filter(s -> s.length() >= 4).toList();
  }

  /** Files changed by the ticket (committed and not) and its diff text, lowercased. */
  private String currentCode(String project, String id) {
    StringBuilder sb = new StringBuilder();
    try {
      for (RepoView r : dev.view(project, id).repos()) {
        r.files().forEach(x -> sb.append(x).append('\n'));
        r.uncommitted().forEach(x -> sb.append(x).append('\n'));
        if (r.head() != null) {
          String d = dev.diff(project, id, r.repo());
          sb.append(d, 0, Math.min(d.length(), 200_000));
        }
      }
    } catch (RuntimeException e) {
      // no worktree yet: nothing written in this ticket
    }
    return sb.toString().toLowerCase(Locale.ROOT);
  }

  private String doc(String project, String id, String name) {
    try {
      return tasks.readDoc(project, id, name).content();
    } catch (NoSuchElementException e) {
      return "";
    }
  }
}
