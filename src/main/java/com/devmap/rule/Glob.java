package com.devmap.rule;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Path globs of the rule files, on '/'-separated relative paths (same result on every OS, unlike PathMatcher on Windows).
 * {@code **} spans folders, {@code *} and {@code ?} stay inside one; a pattern without '/' matches the file name in any folder;
 * a trailing '/' means everything below that folder.
 */
public final class Glob {

  private Glob() {
  }

  public static boolean matches(String glob, String path) {
    String p = path.replace('\\', '/');
    String g = glob.strip().replace('\\', '/');
    if (g.endsWith("/")) {
      g += "**";
    }
    if (!g.contains("/")) {
      g = "**/" + g;
    }
    return regex(g).matcher(p).matches();
  }

  public static boolean any(List<String> globs, String path) {
    return globs.stream().anyMatch(g -> matches(g, path));
  }

  private static Pattern regex(String glob) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < glob.length(); i++) {
      char c = glob.charAt(i);
      if (c == '*' && i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
        boolean dir = i + 2 < glob.length() && glob.charAt(i + 2) == '/';
        sb.append(dir ? "(?:.*/)?" : ".*");
        i += dir ? 2 : 1;
      } else if (c == '*') {
        sb.append("[^/]*");
      } else if (c == '?') {
        sb.append("[^/]");
      } else {
        sb.append(Pattern.quote(String.valueOf(c)));
      }
    }
    return Pattern.compile(sb.toString());
  }
}
