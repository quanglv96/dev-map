package com.devmap.secret;

import java.util.List;

/** Per-project additions to the built-in masking rules: extra regexes to mask and regexes of values to keep. */
public record SecretsConfig(List<String> patterns, List<String> allow) {

  public static final SecretsConfig EMPTY = new SecretsConfig(List.of(), List.of());

  public SecretsConfig {
    patterns = patterns == null ? List.of() : List.copyOf(patterns);
    allow = allow == null ? List.of() : List.copyOf(allow);
  }
}
