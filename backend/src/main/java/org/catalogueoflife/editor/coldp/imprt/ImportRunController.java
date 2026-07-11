package org.catalogueoflife.editor.coldp.imprt;

import org.catalogueoflife.editor.auth.CurrentUser;
import org.catalogueoflife.editor.coldp.imprt.dto.ImportRunResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

// The ColDP import job's HTTP surface: POST kicks off ImportRunService.start, which authorizes the
// caller (any authenticated user -- there is no project yet to hold a role in), inserts the
// RUNNING import_run row, extracts the archive synchronously (failing the request fast with 400 on
// a malformed/oversize one) and fires the @Async run() -- returning 202 immediately (the row may
// already be DONE by the time the response is built, for a tiny archive, but the client is never
// blocked waiting for the job). GET /{runId} polls a specific row by id; GET /latest is the
// load-on-mount view. "/latest" is a literal path segment and "/{runId}" is a variable one at the
// same position -- Spring's RequestMappingHandlerMapping ranks candidate patterns by specificity
// (literal segments outrank variables) regardless of declaration order in this class, mirroring
// ExportRunController's identical "/latest" vs "/{runId}" routing.
@RestController
@RequestMapping("/api/projects/import")
public class ImportRunController {

  private final ImportRunService service;
  private final CurrentUser currentUser;

  public ImportRunController(ImportRunService service, CurrentUser currentUser) {
    this.service = service;
    this.currentUser = currentUser;
  }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.ACCEPTED)
  public ImportRunResponse start(@RequestPart("file") MultipartFile file,
      @RequestParam(defaultValue = "false") boolean preserveIds,
      @RequestParam(required = false) String idScope,
      @RequestParam(required = false) String title) {
    int uid = currentUser.require().getId();
    return service.start(uid, file, preserveIds, idScope, title);
  }

  @GetMapping("/latest")
  public ResponseEntity<ImportRunResponse> latest() {
    int uid = currentUser.require().getId();
    ImportRunResponse run = service.latest(uid);
    return run == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(run);
  }

  @GetMapping("/{runId}")
  public ImportRunResponse get(@PathVariable long runId) {
    int uid = currentUser.require().getId();
    return service.get(uid, runId);
  }
}
