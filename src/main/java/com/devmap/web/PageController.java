package com.devmap.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** localhost:7070/{project} is the project workspace; the page loads its data from /api. */
@Controller
public class PageController {

  @GetMapping("/{project:[a-z0-9][a-z0-9-]*}")
  public String project() {
    return "forward:/project.html";
  }
}
