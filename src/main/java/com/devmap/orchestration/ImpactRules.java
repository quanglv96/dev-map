package com.devmap.orchestration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * FR-79 hard rule, local and without an LLM: whatever the agent says, a change of an AC line, of the business rules or of the scope in
 * analysis.md, or a new repo in plan.md / the ticket, needs the PMO. Test cases ("Ca kiểm thử", TC-n) may grow without that: a
 * missed case of an existing AC is technical (FR-92 B1).
 */
public final class ImpactRules {

  /** affects: TECHNICAL_ONLY | AC | BUSINESS_RULE | SCOPE; reason: what the rule saw; repoAdded: scope grew by a repo/service. */
  public record Verdict(String affects, String reason, boolean repoAdded) {

    public boolean technical() {
      return affects.equals("TECHNICAL_ONLY");
    }
  }

  private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.*)$");
  private static final Pattern TESTS = Pattern.compile("(?iu)(ca kiểm thử|test case|kiểm thử)");
  private static final Pattern RULES = Pattern.compile("(?iu)(business rule|quy tắc|nghiệp vụ)");
  private static final Pattern SCOPE = Pattern.compile("(?iu)(phạm vi|scope)");
  private static final Pattern AC_HEAD = Pattern.compile("(?iu)(\\bac\\b|tiêu chí|acceptance)");
  private static final Pattern AC_LINE = Pattern.compile("(?iu)^[\\s>*|-]*\\**\\s*(AC-?\\d+|given\\b|when\\b|then\\b)");

  private ImpactRules() {
  }

  public static Verdict classify(String analysisBefore, String analysisAfter, String planBefore, String planAfter,
      Collection<String> projectRepos, Collection<String> reposBefore, Collection<String> reposAfter) {
    List<String> added = reposAfter.stream().filter(r -> !reposBefore.contains(r)).toList();
    if (!added.isEmpty()) {
      return new Verdict("SCOPE", "thêm repo " + added + " vào ticket", true);
    }
    String pb = planBefore.toLowerCase(Locale.ROOT);
    String pa = planAfter.toLowerCase(Locale.ROOT);
    List<String> named = projectRepos.stream().filter(r -> !reposBefore.contains(r))
        .filter(r -> pa.contains(r.toLowerCase(Locale.ROOT)) && !pb.contains(r.toLowerCase(Locale.ROOT))).toList();
    if (!named.isEmpty()) {
      return new Verdict("SCOPE", "plan.md thêm repo/service " + named, true);
    }
    if (analysisBefore.isBlank()) {
      return new Verdict("TECHNICAL_ONLY", "chưa có analysis.md để so", false);
    }
    if (!part(analysisBefore, SCOPE).equals(part(analysisAfter, SCOPE))) {
      return new Verdict("SCOPE", "đổi mục phạm vi trong analysis.md", false);
    }
    if (!part(analysisBefore, RULES).equals(part(analysisAfter, RULES))) {
      return new Verdict("BUSINESS_RULE", "đổi business rule trong analysis.md", false);
    }
    if (!acLines(analysisBefore).equals(acLines(analysisAfter))) {
      return new Verdict("AC", "đổi nội dung AC trong analysis.md", false);
    }
    return new Verdict("TECHNICAL_ONLY", "không đổi AC, business rule, phạm vi", false);
  }

  /** Lines of the sections whose heading matches (test-case sections excluded), normalized. */
  static List<String> part(String doc, Pattern heading) {
    List<String> out = new ArrayList<>();
    boolean in = false;
    for (String line : doc.split("\n")) {
      var h = HEADING.matcher(line.strip());
      if (h.matches()) {
        in = heading.matcher(h.group(1)).find() && !TESTS.matcher(h.group(1)).find();
        continue;
      }
      if (in && !line.isBlank()) {
        out.add(norm(line));
      }
    }
    return out;
  }

  /** AC lines anywhere outside test-case sections: "AC-1 …", Given/When/Then, and every line under an AC heading. */
  static List<String> acLines(String doc) {
    List<String> out = new ArrayList<>(part(doc, AC_HEAD));
    boolean tests = false;
    for (String line : doc.split("\n")) {
      var h = HEADING.matcher(line.strip());
      if (h.matches()) {
        tests = TESTS.matcher(h.group(1)).find();
        continue;
      }
      if (!tests && AC_LINE.matcher(line).find()) {
        out.add(norm(line));
      }
    }
    return out.stream().distinct().sorted().toList();
  }

  private static String norm(String line) {
    return line.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
  }
}
