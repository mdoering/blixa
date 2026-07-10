package org.catalogueoflife.editor.coldp.export;

import org.catalogueoflife.editor.auth.CurrentUser;
import org.catalogueoflife.editor.coldp.export.dto.ExportRunResponse;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// The ColDP export job's HTTP surface: POST kicks off ExportRunService.start, which authorizes the
// caller, inserts the RUNNING export_run row and fires the @Async run() -- returning 202 immediately
// (the row may already be DONE by the time the response is built, for a small project, but the
// client is never blocked waiting for the job) or 409 if an export is already in progress for the
// project; GET /{runId} polls a specific row by id; GET /latest is the load-on-mount view; GET
// /{runId}/file streams the produced zip. "/latest" is a literal path segment and "/{runId}" is a
// variable one at the same position -- Spring's RequestMappingHandlerMapping ranks candidate
// patterns by specificity (literal segments outrank variables) regardless of declaration order in
// this class, mirroring ColMatchRunController's identical "/latest" vs "/{runId}" routing.
@RestController
@RequestMapping("/api/projects/{pid}/export")
public class ExportRunController {

  private static final MediaType APPLICATION_ZIP = MediaType.valueOf("application/zip");

  private final ExportRunService service;
  private final CurrentUser currentUser;

  public ExportRunController(ExportRunService service, CurrentUser currentUser) {
    this.service = service;
    this.currentUser = currentUser;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.ACCEPTED)
  public ExportRunResponse start(@PathVariable int pid) {
    int uid = currentUser.require().getId();
    return service.start(uid, pid);
  }

  @GetMapping("/{runId}")
  public ExportRunResponse get(@PathVariable int pid, @PathVariable long runId) {
    int uid = currentUser.require().getId();
    return service.get(uid, pid, runId);
  }

  @GetMapping("/latest")
  public ResponseEntity<ExportRunResponse> latest(@PathVariable int pid) {
    int uid = currentUser.require().getId();
    ExportRunResponse run = service.latest(uid, pid);
    return run == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(run);
  }

  @GetMapping("/{runId}/file")
  public ResponseEntity<Resource> file(@PathVariable int pid, @PathVariable long runId) {
    int uid = currentUser.require().getId();
    ExportRunService.ExportFile file = service.fileFor(uid, pid, runId);
    Resource resource = new FileSystemResource(file.path());
    ContentDisposition disposition = ContentDisposition.attachment().filename(file.fileName()).build();
    return ResponseEntity.ok()
        .contentType(APPLICATION_ZIP)
        .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
        .contentLength(file.path().toFile().length())
        .body(resource);
  }
}
