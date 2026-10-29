package com.devmap.cli;

import com.devmap.graph.GraphService;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** FR-42 `devmap index`: fetch, then update the signature index by git diff; --full scans everything once (FR-17). */
@Command(name = "index", mixinStandardHelpOptions = true,
    description = "Fetch the project's repos and update the signature index by git diff (no LLM, nothing sent to agents).")
class IndexCommand implements Callable<Integer> {

  @Parameters(paramLabel = "project")
  String project;

  @Option(names = "--full", description = "Index every file of the base branches once (the PMO's full scan).")
  boolean full;

  @Override
  public Integer call() {
    GraphService graphs = Core.get(GraphService.class);
    if (full) {
      GraphService.Estimate e = graphs.estimate(project);
      System.out.println("Quét toàn bộ: " + e.files() + " file " + e.filesByRepo() + " – chỉ trích chữ ký trên máy, không gửi cho agent");
    }
    GraphService.Report r = graphs.update(project, full);
    r.repos().forEach(rr -> System.out.println(rr.repo() + "\t" + (rr.commit() == null ? "-" : rr.commit().substring(0, 8))
        + "\tđọc " + rr.parsed() + ", bỏ " + rr.removed() + "\t" + rr.note()));
    var g = graphs.graph(project);
    System.out.println("Graph: " + g.nodeCount() + " node, " + g.edgeCount() + " edge (" + r.millis() + " ms)");
    return 0;
  }
}
