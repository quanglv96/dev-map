package com.devmap.web;

import com.devmap.audit.AuditLog;
import com.devmap.config.FolderPicker;
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

  public record CreateProjectRequest(String name, String dataRoot, String color, List<RepoInput> repos) {
  }

  private final ProjectService service;
  private final AuditLog audit;
  private final FolderPicker picker;

  public ProjectController(ProjectService service, AuditLog audit, FolderPicker picker) {
    this.service = service;
    this.audit = audit;
    this.picker = picker;
  }

  @GetMapping("/projects")
  public List<Project> list() {
    return service.list();
  }

  @PostMapping("/projects")
  @ResponseStatus(HttpStatus.CREATED)
  public Project create(@RequestBody CreateProjectRequest req) {
    Path root = req.dataRoot() == null ? null : Path.of(req.dataRoot());
    return service.create(req.name(), root, req.color(), req.repos() == null ? List.of() : req.repos());
  }

  @GetMapping("/folders")
  public ProjectService.Folders folders(@RequestParam String path) {
    return service.folders(path);
  }

  public record Picked(String path) {
  }

  /** Opens the OS folder dialog; path is null when the PMO cancels. */
  @PostMapping("/folders/pick")
  public Picked pickFolder(@RequestParam(required = false) String start) {
    return new Picked(picker.pick(start));
  }

  @DeleteMapping("/folders")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteFolder(@RequestParam String path) {
    service.deleteFolder(path);
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

  /** NFR-10: the audit log of the project, newest first; ticket filters to one ticket. */
  @GetMapping("/projects/{name}/audit")
  public List<AuditLog.Entry> audit(@PathVariable String name, @RequestParam(required = false) String ticket,
      @RequestParam(defaultValue = "500") int limit) {
    return audit.read(Path.of(service.get(name).dataDir()), ticket == null || ticket.isBlank() ? null : ticket,
        Math.max(1, Math.min(limit, 5000)));
  }

  @GetMapping("/services/detect")
  public List<ServiceInfo> detect(@RequestParam String path) {
    return service.detectServices(path);
  }
}
