package com.devmap.secret;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * FR-35/36: masks IPs, tokens, keys, passwords and connection strings. Over-masking is preferred to leaking:
 * every rule applies unless the matched value is on the allow list (default: loopback/any addresses, ${placeholders}).
 * Projects add rules and allowed values in config.yml (secrets.patterns / secrets.allow).
 */
@Component
public class SecretMasker {

  public record Result(String text, int count) {
  }

  /** group = the part of the match that is replaced (0 = whole match); wordsOk: keep values made of words (branches, paths). */
  private record Rule(String label, Pattern pattern, int group, boolean wordsOk) {
  }

  private static final String KEY_WORDS =
      "password|passwd|pwd|secret|token|api[_-]?key|apikey|access[_-]?key|private[_-]?key|credentials?|auth[_-]?key";

  private static final List<Rule> RULES = List.of(
      rule("KEY", "-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----", 0),
      rule("CONN", "(?i)\\b(?:jdbc:[a-z0-9]+:|(?:mongodb(?:\\+srv)?|postgres(?:ql)?|mysql|mariadb|redis|rediss|amqps?|sqlserver)://)"
          + "[^\\s\"'<>`]+", 0),
      rule("CONN", "(?i)\\b[a-z][a-z0-9+.-]*://[^\\s/:@\"']+:[^\\s/@\"']+@[^\\s\"'<>`]*", 0),
      rule("SECRET", "(?i)\\b[A-Za-z0-9_.-]*(?:" + KEY_WORDS + ")[A-Za-z0-9_.-]*[ \\t]*[:=][ \\t]*([\"'])(.+?)\\1", 2),
      rule("SECRET", "(?i)\\b[A-Za-z0-9_.-]*(?:" + KEY_WORDS + ")[A-Za-z0-9_.-]*[ \\t]*[:=][ \\t]*([^\\s\"',;()]+)(?=[\\s,;]|$)", 1),
      rule("TOKEN", "(?i)\\bbearer[ \\t]+([A-Za-z0-9._~+/=-]{16,})", 1),
      rule("TOKEN", "\\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[0-9A-Z]{16}|AIza[0-9A-Za-z_-]{35}"
          + "|xox[abprs]-[A-Za-z0-9-]{10,}|(?:sk|pk|rk|tok|key|api)_[A-Za-z0-9_]{16,})\\b", 0),
      rule("TOKEN", "\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}", 0),
      // ponytail: generic "random-looking" string = 32+ chars mixing upper, lower and digits; hex-only keys rely on key=value rules
      new Rule("TOKEN", Pattern.compile("(?<![A-Za-z0-9_+/-])(?=[A-Za-z0-9_+/-]*[A-Z])(?=[A-Za-z0-9_+/-]*[a-z])(?=[A-Za-z0-9_+/-]*[0-9])"
          + "[A-Za-z0-9_+/-]{32,}={0,2}"), 0, true),
      rule("IP", "(?<![\\d.])(?:(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(?![\\d.]*\\d)", 0));

  private static final List<Pattern> DEFAULT_ALLOW = List.of(
      Pattern.compile("127\\.0\\.0\\.1"), Pattern.compile("0\\.0\\.0\\.0"), Pattern.compile("\\$\\{[^}]*\\}"),
      Pattern.compile("\\[MASKED_[A-Z]+\\].*"));

  /** Segments of a branch name or path: lowercase/Capitalized words, numbers, short CAPS (NYX) or anything up to 3 chars. */
  private static final Pattern WORD = Pattern.compile("[a-z]+|[A-Z][a-z]+|[A-Z]{1,10}|[0-9]+|.{1,3}");

  private static Rule rule(String label, String regex, int group) {
    return new Rule(label, Pattern.compile(regex), group, false);
  }

  /** feature/NYX-9-them-hoan-tien, db/migration/V2__add_refund: words joined by / - _; a random token has mixed-case runs. */
  static boolean madeOfWords(String value) {
    String[] parts = value.split("[/_+-]+");
    return parts.length >= 3 && java.util.Arrays.stream(parts).allMatch(p -> p.isEmpty() || WORD.matcher(p).matches());
  }

  public Result mask(String text) {
    return mask(text, null);
  }

  public Result mask(String text, SecretsConfig config) {
    if (text == null || text.isEmpty()) {
      return new Result(text, 0);
    }
    List<Rule> rules = new ArrayList<>(RULES);
    List<Pattern> allow = new ArrayList<>(DEFAULT_ALLOW);
    if (config != null) {
      config.patterns().forEach(p -> rules.add(rule("CUSTOM", p, 0)));
      config.allow().forEach(a -> allow.add(Pattern.compile(a)));
    }
    String out = text;
    int count = 0;
    for (Rule r : rules) {
      Matcher m = r.pattern().matcher(out);
      StringBuilder sb = new StringBuilder();
      int last = 0;
      while (m.find()) {
        String value = m.group(r.group());
        if (value == null || value.isEmpty() || allow.stream().anyMatch(a -> a.matcher(value).matches())
            || r.wordsOk() && madeOfWords(value)) {
          continue;
        }
        sb.append(out, last, m.start(r.group())).append("[MASKED_").append(r.label()).append(']');
        last = m.end(r.group());
        count++;
      }
      out = sb.append(out, last, out.length()).toString();
    }
    return new Result(out, count);
  }
}
