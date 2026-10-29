package com.devmap.ticket;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * FR-44 step 2, rules only (no LLM): drops polite filler, splits constraints from the rest and finds target modules
 * by service/repo name. Input must already be secret-masked.
 */
@Component
public class RequestCleaner {

  private static final String WHO = "(?:anh|chị|chi|em|bạn|ban|mình|tôi|tớ|team|mọi người|sếp)";

  // ponytail: phrase list, not NLP; Vietnamese free text that slips through just stays in the summary
  private static final Pattern FILLER = Pattern.compile("(?iU)(?<![\\p{L}\\p{N}])(?:"
      + "(?:xin )?chào(?: " + WHO + ")*|hi|hello|hey|dear " + WHO
      + "|" + WHO + " ơi|(?:" + WHO + " )?nhờ " + WHO + "|làm ơn|vui lòng|please|pls|kindly"
      + "|giúp(?: " + WHO + ")?|giùm(?: " + WHO + ")?|hộ " + WHO + "|với nhé|với nha"
      + "|(?:cảm ơn|cám ơn|thanks|thank you|thank|tks)(?: " + WHO + "| nhiều| nhé| nha)*"
      + "|nhé|nha|nhá|ạ|dạ|ok"
      + ")(?![\\p{L}\\p{N}])");

  private static final Pattern CONSTRAINT = Pattern.compile("(?iU)(?<![\\p{L}])(?:không được|không đổi|không thay đổi|giữ nguyên"
      + "|bắt buộc|phải|chỉ được|tối đa|tối thiểu|must|must not|do not|don't|never|only|at most|at least)(?![\\p{L}])");

  private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?;])\\s+|\\n+");

  public record Cleaned(String summary, List<String> constraints) {
  }

  public Cleaned clean(String text) {
    List<String> summary = new ArrayList<>();
    List<String> constraints = new ArrayList<>();
    for (String raw : SENTENCE_END.split(text == null ? "" : text)) {
      String s = FILLER.matcher(raw).replaceAll(" ")
          .replaceAll("\\s+([,.!?;:])", "$1")
          .replaceAll("\\s+", " ")
          .replaceAll("^[\\s,.!?;:-]+|[\\s,;:-]+$", "")
          .trim();
      if (s.replaceAll("[\\p{Punct}\\s]", "").isEmpty()) {
        continue;
      }
      s = Character.toUpperCase(s.charAt(0)) + s.substring(1);
      (CONSTRAINT.matcher(s).find() ? constraints : summary).add(s);
    }
    return new Cleaned(String.join(" ", summary), constraints);
  }

  /** Names (services, repos) mentioned in the text; "order-service", "order service", "Order_Service" all match. */
  public List<String> mentioned(String text, Collection<String> names) {
    String haystack = fold(text);
    Set<String> out = new LinkedHashSet<>();
    for (String name : names) {
      String re = "(?<![a-z0-9])" + String.join("[-_ ]?", fold(name).split("[-_ ]+")) + "(?![a-z0-9])";
      if (Pattern.compile(re).matcher(haystack).find()) {
        out.add(name);
      }
    }
    return List.copyOf(out);
  }

  /** "Đăng nhập bằng Google!" -> "dang-nhap-bang-google" (max 40 chars). */
  public static String slug(String text) {
    String s = fold(text).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    if (s.length() > 40) {
      s = s.substring(0, 40).replaceAll("-[^-]*$", "");
    }
    return s.isEmpty() ? "task" : s;
  }

  private static String fold(String text) {
    String noMarks = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    return noMarks.replace('đ', 'd').replace('Đ', 'D').toLowerCase(Locale.ROOT);
  }
}
