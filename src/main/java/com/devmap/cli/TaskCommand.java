package com.devmap.cli;

import com.devmap.ticket.Task;
import com.devmap.ticket.TaskService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(name = "task", mixinStandardHelpOptions = true, description = "Create and inspect tickets.")
class TaskCommand implements Runnable {

  @Spec
  CommandSpec spec;

  @Override
  public void run() {
    spec.commandLine().usage(System.out);
  }

  @Command(name = "new", mixinStandardHelpOptions = true, description = "Create a ticket from a natural-language request.")
  int create(
      @Parameters(paramLabel = "project") String project,
      @Option(names = "--type", defaultValue = "FEAT", description = "FEAT | MAINT | BUG (default: ${DEFAULT-VALUE}).") String type,
      @Option(names = "--code", description = "Ticket code; omit to generate (FEAT-001...).") String code,
      @Option(names = "--title", description = "Short title; omit to take it from the request.") String title,
      @Option(names = "--request", description = "The request in plain language. Omit to read --request-file or stdin.")
      String request,
      @Option(names = "--request-file", description = "UTF-8 file holding the request.") Path requestFile) throws IOException {
    // Windows hands Java its arguments in the ANSI code page, so Vietnamese in --request may arrive as '?'.
    String text = request != null ? request
        : requestFile != null ? Files.readString(requestFile)
        : new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
    TaskService.Created c = Core.get(TaskService.class).create(project, type, code, title, text);
    System.out.println("Đã tạo " + c.task().id() + " – " + c.task().title() + " (branch " + c.task().branch() + ")");
    if (c.maskedCount() > 0) {
      System.out.println("Cảnh báo: đã che " + c.maskedCount() + " giá trị giống bí mật trong yêu cầu");
    }
    return 0;
  }

  @Command(name = "list", description = "List tickets of a project.")
  int list(@Parameters(paramLabel = "project") String project) {
    for (Task t : Core.get(TaskService.class).list(project)) {
      System.out.println(t.id() + "\t" + t.type() + "\t" + t.status().code() + "\t" + t.title());
    }
    return 0;
  }

  @Command(name = "show", description = "Show a ticket, its documents and allowed transitions.")
  int show(@Parameters(paramLabel = "project") String project, @Parameters(paramLabel = "id") String id) {
    TaskService.Detail d = Core.get(TaskService.class).detail(project, id);
    Task t = d.task();
    System.out.println(t.id() + " – " + t.title());
    System.out.println("  loại: " + t.type() + ", trạng thái: " + t.status().code() + ", version: " + t.version()
        + ", mở lại: " + t.reopened());
    System.out.println("  branch: " + t.branch() + ", repo: " + t.repos());
    System.out.println("  tài liệu: " + String.join(", ", d.docs()));
    System.out.println("  chuyển được sang: " + d.actions().stream()
        .map(a -> a.to() + (a.pmoGate() ? " (PMO)" : "")).toList());
    return 0;
  }
}
