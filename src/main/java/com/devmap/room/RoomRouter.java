package com.devmap.room;

import com.devmap.config.RoomConfig;
import com.devmap.ticket.TaskStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * FR-72: who takes the PMO's message, decided locally without an LLM: (1) @SA / @Dev / @all, (2) keywords (configurable in
 * room.*_keywords), (3) ticket status. Not sure → the room asks with two buttons. While implementing, logic changes and bug reports
 * go to SA first (§2.5) unless the PMO named @Dev.
 */
@Component
public class RoomRouter {

  /** ASK: answer; CHANGE: PMO changes logic while implementing (SA classifies A1/A2); BUG: PMO reports a bug (case B1–B4). */
  public enum Kind { ASK, CHANGE, BUG }

  public record Assignment(String role, String text, Kind kind) {
  }

  /** ask: not sure, show "Gửi SA" / "Gửi Dev" (to is then empty). */
  public record Route(List<Assignment> to, boolean ask) {
  }

  private static final Pattern MENTION = Pattern.compile("(?i)@(sa|dev|all)\\b");

  public Route route(String text, TaskStatus status, RoomConfig cfg) {
    String lower = text.toLowerCase(Locale.ROOT);
    boolean implementing = status == TaskStatus.IMPLEMENT || status == TaskStatus.VERIFY;
    Matcher m = MENTION.matcher(text);
    List<int[]> at = new ArrayList<>();
    List<String> who = new ArrayList<>();
    while (m.find()) {
      at.add(new int[] {m.start(), m.end()});
      who.add(m.group(1).toUpperCase(Locale.ROOT));
    }
    if (!at.isEmpty()) {
      String rest = MENTION.matcher(text).replaceAll("").strip();
      if (who.contains("ALL")) {
        return new Route(List.of(new Assignment("SA", rest, kind("SA", rest, implementing, cfg)),
            new Assignment("DEV", rest, Kind.ASK)), false);
      }
      // "@SA rà nghiệp vụ, @Dev dò log": each role gets the words after its mention (FR-74)
      Map<String, StringBuilder> parts = new LinkedHashMap<>();
      String lead = text.substring(0, at.get(0)[0]).strip();
      for (int i = 0; i < at.size(); i++) {
        int end = i + 1 < at.size() ? at.get(i + 1)[0] : text.length();
        String seg = text.substring(at.get(i)[1], end).strip().replaceAll("^[,;:.\\s]+|[,;\\s]+$", "");
        parts.computeIfAbsent(who.get(i), k -> new StringBuilder()).append(parts.get(who.get(i)).isEmpty() ? "" : "; ").append(seg);
      }
      List<Assignment> out = new ArrayList<>();
      parts.forEach((role, sb) -> {
        String seg = (lead.isEmpty() ? "" : lead + " ") + (sb.isEmpty() ? rest : sb.toString());
        out.add(new Assignment(role, seg.strip(), role.equals("SA") ? kind("SA", seg, implementing, cfg) : Kind.ASK));
      });
      return new Route(out, false);
    }
    int sa = hits(lower, cfg.saKeywords());
    int dev = hits(lower, cfg.devKeywords());
    int bug = hits(lower, cfg.bugKeywords());
    int change = hits(lower, cfg.changeKeywords());
    if (implementing && bug > 0) {
      return one("SA", text, Kind.BUG);
    }
    if (dev > sa) {
      return one("DEV", text, Kind.ASK);
    }
    if (sa > dev) {
      return one("SA", text, implementing && change > 0 ? Kind.CHANGE : Kind.ASK);
    }
    if (implementing && change > 0) {
      return one("SA", text, Kind.CHANGE);
    }
    boolean devTime = status == TaskStatus.READY || implementing;
    if (devTime || sa > 0) {
      return new Route(List.of(), true);
    }
    return one("SA", text, Kind.ASK);
  }

  private static Kind kind(String role, String text, boolean implementing, RoomConfig cfg) {
    String lower = text.toLowerCase(Locale.ROOT);
    if (!implementing || !role.equals("SA")) {
      return Kind.ASK;
    }
    return hits(lower, cfg.bugKeywords()) > 0 ? Kind.BUG : hits(lower, cfg.changeKeywords()) > 0 ? Kind.CHANGE : Kind.ASK;
  }

  private static Route one(String role, String text, Kind kind) {
    return new Route(List.of(new Assignment(role, text.strip(), kind)), false);
  }

  /** Whole-word-ish match: a keyword counts when it is not glued to letters on both sides ("test" not in "latest"). */
  static int hits(String lower, List<String> words) {
    int n = 0;
    for (String w : words) {
      String k = w.toLowerCase(Locale.ROOT);
      if (Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(k) + (k.endsWith("-") ? "" : "(?![\\p{L}\\p{N}])"))
          .matcher(lower).find()) {
        n++;
      }
    }
    return n;
  }
}
