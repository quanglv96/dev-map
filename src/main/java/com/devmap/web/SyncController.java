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

/** FR-46 → FR-57 over HTTP: status for the top bar, sync now, push confirmation, lock choice, conflicts, setup, export/import. */
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

  @GetMapping("/sync")
  public Status status() {
    return sync.status();
  }

  @PostMapping("/sync/now")
  public Status now() {
    return sync.syncNow();
  }

  /** Called before a project page loads (FR-48); a fresh pull is skipped. */
  @PostMapping("/sync/pull")
  public Status pull() {
    return sync.pullIfStale();
  }

  @PostMapping("/sync/push")
  public Status push() {
    return sync.confirmPush();
  }

  @PostMapping("/sync/lock/take")
  public Status takeOver() {
    return sync.takeOver();
  }

  @PostMapping("/sync/lock/view")
  public Status viewOnly() {
    return sync.viewOnly();
  }

  @PutMapping("/sync/auto-push")
  public Status autoPush(@RequestBody AutoPush req) {
    sync.setAutoPush(req.on());
    return sync.status();
  }

  @PostMapping("/sync/init")
  public Setup init(@RequestBody Remote req) {
    return sync.initSync(req.remote(), req.path() == null || req.path().isBlank() ? null : Path.of(req.path()));
  }

  @PostMapping("/sync/clone")
  public Setup cloneData(@RequestBody Remote req) {
    if (req.path() == null || req.path().isBlank()) {
      throw new IllegalArgumentException("Chọn folder để clone dữ liệu vào");
    }
    return sync.cloneData(req.remote(), Path.of(req.path()));
  }

  @GetMapping("/sync/conflicts")
  public List<Conflict> conflicts(@RequestParam(required = false) String project) {
    return sync.conflicts(project);
  }

  @PostMapping("/sync/conflicts/resolve")
  public Status resolve(@RequestBody Resolve req) {
    return sync.resolve(req.conflictFile(), req.content());
  }

  /** FR-57: download the project as a zip (rebuildable and per-machine files left out). */
  @GetMapping("/projects/{name}/export")
  public void export(@PathVariable String name, HttpServletResponse res) throws IOException {
    res.setContentType("application/zip");
    res.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"devmap-" + name + ".zip\"");
    sync.export(name, res.getOutputStream());
  }

  /** FR-57: the zip is the request body; dataRoot defaults to the synced data root. */
  @PostMapping("/projects/import")
  public Project importZip(@RequestParam(required = false) String dataRoot, HttpServletRequest req) throws IOException {
    return sync.importZip(req.getInputStream(), dataRoot == null || dataRoot.isBlank() ? null : Path.of(dataRoot));
  }
}
