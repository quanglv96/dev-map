package com.devmap.web;

import com.devmap.config.Project;
import com.devmap.sync.SyncService;
import com.devmap.sync.SyncService.Conflict;
import com.devmap.sync.SyncService.Setup;
import com.devmap.sync.SyncService.Status;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * FR-46 → FR-57 over HTTP, per project (D-53): status for the top bar, sync now, push confirmation, lock choice, conflicts, setup,
 * export/import.
 */
@RestController
@RequestMapping("/api")
public class SyncController {

  public record Remote(String remote, String path) {
  }

  public record Resolve(String conflictFile, String content) {
  }

  public record AutoPush(boolean on) {
  }

  private final SyncService sync;

  public SyncController(SyncService sync) {
    this.sync = sync;
  }

  /** Home page: every project's status. */
  @GetMapping("/sync")
  public List<Status> statuses() {
    return sync.statuses();
  }

  /** New machine: clone one project's data repo into path/<project> (FR-46). */
  @PostMapping("/sync/clone")
  public Setup cloneProject(@RequestBody Remote req) {
    if (req.path() == null || req.path().isBlank()) {
      throw new IllegalArgumentException("Chọn folder dữ liệu để clone dự án vào");
    }
    return sync.cloneProject(req.remote(), Path.of(req.path()));
  }

  @GetMapping("/projects/{name}/sync")
  public Status status(@PathVariable String name) {
    return sync.status(name);
  }

  @PostMapping("/projects/{name}/sync/now")
  public Status now(@PathVariable String name) {
    return sync.syncNow(name);
  }

  /** Called before a project page loads (FR-48); a fresh pull is skipped. */
  @PostMapping("/projects/{name}/sync/pull")
  public Status pull(@PathVariable String name) {
    return sync.pullIfStale(name);
  }

  @PostMapping("/projects/{name}/sync/push")
  public Status push(@PathVariable String name) {
    return sync.confirmPush(name);
  }

  @PostMapping("/projects/{name}/sync/lock/take")
  public Status takeOver(@PathVariable String name) {
    return sync.takeOver(name);
  }

  @PostMapping("/projects/{name}/sync/lock/view")
  public Status viewOnly(@PathVariable String name) {
    return sync.viewOnly(name);
  }

  @PutMapping("/projects/{name}/sync/auto-push")
  public Status autoPush(@PathVariable String name, @RequestBody AutoPush req) {
    sync.setAutoPush(name, req.on());
    return sync.status(name);
  }

  @PostMapping("/projects/{name}/sync/init")
  public Setup init(@PathVariable String name, @RequestBody Remote req) {
    return sync.initSync(name, req.remote());
  }

  @GetMapping("/projects/{name}/sync/conflicts")
  public List<Conflict> conflicts(@PathVariable String name) {
    return sync.conflicts(name);
  }

  @PostMapping("/projects/{name}/sync/conflicts/resolve")
  public Status resolve(@PathVariable String name, @RequestBody Resolve req) {
    return sync.resolve(name, req.conflictFile(), req.content());
  }

  /** FR-57: download the project as a zip (rebuildable and per-machine files left out). */
  @GetMapping("/projects/{name}/export")
  public void export(@PathVariable String name, HttpServletResponse res) throws IOException {
    res.setContentType("application/zip");
    res.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"devmap-" + name + ".zip\"");
    sync.export(name, res.getOutputStream());
  }

  /** FR-57: the zip is the request body; dataRoot defaults to the data root of the existing projects. */
  @PostMapping("/projects/import")
  public Project importZip(@RequestParam(required = false) String dataRoot, HttpServletRequest req) throws IOException {
    return sync.importZip(req.getInputStream(), dataRoot == null || dataRoot.isBlank() ? null : Path.of(dataRoot));
  }
}
