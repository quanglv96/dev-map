package com.devmap.graph;

import java.util.Map;
import java.util.TreeMap;

/** graph/&lt;repo&gt;.json: the index of one repo at one base-branch commit; rebuilt incrementally from git diff. */
public record RepoIndex(String repo, String commit, String indexedAt, Map<String, FileIndex> files) {

  public RepoIndex {
    files = files == null ? new TreeMap<>() : new TreeMap<>(files);
  }
}
