package com.devmap.web;

import com.devmap.config.Project;
import com.devmap.config.ProjectConfig;
import com.devmap.config.ProjectService;
import com.devmap.config.ProjectService.RepoInput;
import com.devmap.config.ServiceInfo;
import java.nio.file.Path;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ProjectController {

  public record CreateProjectRequest(String name, String dataRoot, String color, List<RepoInput> repos, boolean git) {
  }

  private final ProjectService service;

  public ProjectController(ProjectService service) {
    this.service = service;
  }

  @GetMapping("/projects")
  public List<Project> list() {
    return service.list();
  }

  @PostMapping("/projects")
  @ResponseStatus(HttpStatus.CREATED)
  public Project create(@RequestBody CreateProjectRequest req) {
    Path root = req.dataRoot() == null ? null : Path.of(req.dataRoot());
    return service.create(req.name(), root, req.color(), req.repos() == null ? List.of() : req.repos(), req.git());
  }

  @GetMapping("/projects/{name}")
  public Project get(@PathVariable String name) {
    return service.get(name);
  }

  @PutMapping("/projects/{name}/config")
  public Project update(@PathVariable String name, @RequestBody ProjectConfig config) {
    return service.update(name, config);
  }

  @DeleteMapping("/projects/{name}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void remove(@PathVariable String name) {
    service.remove(name);
  }

  @GetMapping("/services/detect")
  public List<ServiceInfo> detect(@RequestParam String path) {
    return service.detectServices(path);
  }
}
