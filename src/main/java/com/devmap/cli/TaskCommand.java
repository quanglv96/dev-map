package com.devmap.cli;

import com.devmap.config.Project;
import com.devmap.config.ProjectService;
import com.devmap.sync.SyncService;
import com.devmap.ticket.Task;
import com.devmap.ticket.TaskService;
import com.devmap.ticket.TaskService.Actor;
import com.devmap.ticket.TicketGitService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(name = "task", aliases = "ticket", mixinStandardHelpOptions = true, description = "Create and inspect tickets.")
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

  @Command(name = "handoff", mixinStandardHelpOptions = true,
      description = "FR-55: WIP-commit the unfinished code, push the ticket branch of every repo (asks first), record the handoff.")
  int handoff(@Parameters(paramLabel = "id") String id,
      @Option(names = "--project", description = "Project; needed only when several projects have this ticket code.") String project,
      @Option(names = "--yes", description = "Confirm the push without asking.") boolean yes) throws IOException {
    String p = project != null ? project : projectOf(id);
    TicketGitService dev = Core.get(TicketGitService.class);
    for (TicketGitService.HandoffRepo r : dev.handoffPlan(p, id)) {
      System.out.println(r.repo() + ": push " + r.branch() + (r.uncommitted().isEmpty() ? ""
          : " kèm commit WIP " + r.uncommitted().size() + " file chưa commit: " + String.join(", ", r.uncommitted())));
    }
    if (!yes) {
      System.out.print("PMO xác nhận push các branch trên để làm tiếp ở máy khác? [y/N] ");
      System.out.flush();
      String answer = new String(System.in.readNBytes(1), StandardCharsets.UTF_8);
      if (!answer.equalsIgnoreCase("y")) {
        System.out.println("Đã huỷ, không push gì.");
        return 1;
      }
    }
    String machine = Core.get(SyncService.class).machine();
    dev.handoff(p, id, machine, Actor.PMO).forEach(r ->
        System.out.println(r.repo() + ": đã push " + r.commit().substring(0, 8) + " → origin/" + r.branch()));
    System.out.println("Đã ghi handoff vào task.md. Trên máy kia: mở ticket rồi bấm \"Tiếp tục trên máy này\", "
        + "hoặc `devmap ticket resume " + id + "`.");
    return 0;
  }

  @Command(name = "resume", mixinStandardHelpOptions = true, description = "FR-55: continue a handed-over ticket on this machine.")
  int resume(@Parameters(paramLabel = "id") String id,
      @Option(names = "--project", description = "Project; needed only when several projects have this ticket code.") String project) {
    String p = project != null ? project : projectOf(id);
    TicketGitService.View v = Core.get(TicketGitService.class).resume(p, id, Core.get(SyncService.class).machine(), Actor.PMO);
    v.repos().forEach(r -> System.out.println(r.repo() + ": " + r.branch() + " @" + (r.head() == null ? "-" : r.head().substring(0, 8))));
    return 0;
  }

  /** The one project that has a ticket with this code. */
  private static String projectOf(String id) {
    TaskService tasks = Core.get(TaskService.class);
    List<String> found = Core.get(ProjectService.class).list().stream().filter(x -> x.config() != null).map(Project::name)
        .filter(n -> tasks.list(n).stream().anyMatch(t -> t.id().equals(id))).toList();
    if (found.size() != 1) {
      throw new IllegalArgumentException(found.isEmpty() ? "Không có ticket " + id
          : "Nhiều dự án có " + id + ": " + found + "; thêm --project");
    }
    return found.get(0);
  }
}
