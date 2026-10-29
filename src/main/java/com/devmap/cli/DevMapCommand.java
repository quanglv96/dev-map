package com.devmap.cli;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParseResult;
import picocli.CommandLine.Spec;

@Command(name = "devmap", mixinStandardHelpOptions = true, version = "devmap 0.1.0",
    description = "DevMap: PMO requirement -> SA/Dev agents -> traced code changes.",
    subcommands = {InitCommand.class, WorkspaceCommand.class, TaskCommand.class, ServeCommand.class, DoctorCommand.class})
public class DevMapCommand implements Runnable {

  @Spec
  CommandSpec spec;

  @Override
  public void run() {
    spec.commandLine().usage(System.out);
  }


  public static void run(String[] args) {
    // UTF-8 regardless of the platform default (Cp1252 on Windows with Java 17), or Vietnamese text is garbled.
    System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
    CommandLine cli = new CommandLine(new DevMapCommand());
    cli.setOut(new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true));
    cli.setErr(new PrintWriter(new OutputStreamWriter(new FileOutputStream(FileDescriptor.err), StandardCharsets.UTF_8), true));
    cli.setExecutionExceptionHandler((e, cmd, parsed) -> {
      cmd.getErr().println("Lỗi: " + e.getMessage());
      return 1;
    });
    int code = cli.execute(args);
    if (code == 0 && isServe(cli.getParseResult())) {
      return; // the web server keeps the JVM alive until Ctrl+C
    }
    Core.close();
    System.exit(code);
  }

  private static boolean isServe(ParseResult parsed) {
    return parsed != null && parsed.subcommand() != null && "serve".equals(parsed.subcommand().commandSpec().name());
  }
}
