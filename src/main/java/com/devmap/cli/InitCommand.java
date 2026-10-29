package com.devmap.cli;

import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

@Command(name = "init", mixinStandardHelpOptions = true,
    description = "Create a project's data folder (config.yml, project.md, rules/, graph/, tasks/) and register it.")
class InitCommand implements Callable<Integer> {

  @Mixin
  ProjectOptions options;

  @Override
  public Integer call() {
    return options.create();
  }
}
