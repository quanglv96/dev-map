package com.devmap.cli;

import com.devmap.agent.AgentService;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** FR-42 `devmap prompt`: prints what an agent session of the ticket would receive (system prompt, context, tools). Calls no LLM. */
@Command(name = "prompt", mixinStandardHelpOptions = true,
    description = "Print the system prompt, context and tools an agent session would get for a ticket. No LLM call.")
class PromptCommand implements Callable<Integer> {

  @Parameters(paramLabel = "project")
  String project;

  @Parameters(paramLabel = "id")
  String id;

  @Option(names = "--role", description = "sa | dev (default: by ticket status).")
  String role;

  @Option(names = "--question", description = "A question to include, cleaned and masked like in the chat.")
  String question;

  @Override
  public Integer call() {
    System.out.println(Core.get(AgentService.class).preview(project, id, role, question));
    return 0;
  }
}
