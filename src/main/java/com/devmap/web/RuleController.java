package com.devmap.web;

import com.devmap.rule.ConventionService;
import com.devmap.rule.Rules;
import com.devmap.rule.RuleService;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** "Rule và coding convention" screen: project default and per-service rules (FR-29). */
@RestController
@RequestMapping("/api/projects/{project}/rules")
public class RuleController {

  private final RuleService rules;
  private final ConventionService conventions;

  public RuleController(RuleService rules, ConventionService conventions) {
    this.conventions = conventions;
    this.rules = rules;
  }

  @GetMapping
  public RuleService.View view(@PathVariable String project) {
    return rules.view(project);
  }

  @PutMapping("/default")
  public Rules saveDefault(@PathVariable String project, @RequestBody Rules body) {
    return rules.saveDefault(project, body);
  }

  @PutMapping("/services/{service}")
  public Rules saveService(@PathVariable String project, @PathVariable String service, @RequestBody Rules body) {
    return rules.saveService(project, service, body);
  }

  @DeleteMapping("/services/{service}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void reset(@PathVariable String project, @PathVariable String service) {
    rules.resetService(project, service);
  }

  @GetMapping("/conventions")
  public ConventionService.View conventions(@PathVariable String project) {
    return conventions.view(project);
  }

  @PostMapping("/conventions/collect")
  public ConventionService.View collect(@PathVariable String project) {
    return conventions.collect(project);
  }

  @PutMapping("/conventions")
  public ConventionService.View writeConventions(@PathVariable String project, @RequestBody Map<String, String> body) {
    return conventions.write(project, body.get("content"));
  }

  @PostMapping("/conventions/approve")
  public ConventionService.View approve(@PathVariable String project) {
    return conventions.approve(project);
  }
}
