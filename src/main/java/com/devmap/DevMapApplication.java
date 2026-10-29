package com.devmap;

import com.devmap.cli.DevMapCommand;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Single entry point: every invocation of devmap.jar goes through the Picocli command tree. */
@SpringBootApplication
public class DevMapApplication {

  public static void main(String[] args) {
    DevMapCommand.run(args);
  }
}
