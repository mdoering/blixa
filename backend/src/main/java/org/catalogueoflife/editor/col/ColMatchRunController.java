package org.catalogueoflife.editor.col;

import org.catalogueoflife.editor.auth.CurrentUser;
import org.catalogueoflife.editor.col.dto.ColMatchRunResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// The bulk "Match all to COL" job's HTTP surface (Task 3): POST kicks off ColMatchJobService.start,
// which authorizes the caller, inserts the RUNNING col_match_run row and fires the @Async run() --
// returning 202 immediately (the row may already be DONE by the time the response is built, for a
// small/empty project, but the client is never blocked waiting for the job) or 409 if a run is
// already in progress for the project (ColMatchJobService.start's one-active-run-per-project guard);
// GET /{runId} polls a specific row by id for progress/summary; GET /latest is the load-on-mount
// view for the Project page (no runId known yet client-side). "/latest" is a literal path segment
// and "/{runId}" is a variable one at the same position -- Spring's RequestMappingHandlerMapping
// ranks candidate patterns by specificity (literal segments outrank variables) regardless of
// declaration order in this class, so GET /col-match/latest always resolves to latest() below, never
// to get() with runId="latest" (which would 400 on the long conversion anyway); verified explicitly
// by ColMatchRunApiIT (both endpoints asserted to resolve correctly, latest declared after {runId}
// in this file). Mirrors IssueController's project-scoped, thin-controller-delegates-to-service
// shape.
@RestController
@RequestMapping("/api/projects/{pid}/col-match")
public class ColMatchRunController {

  private final ColMatchJobService service;
  private final CurrentUser currentUser;

  public ColMatchRunController(ColMatchJobService service, CurrentUser currentUser) {
    this.service = service;
    this.currentUser = currentUser;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.ACCEPTED)
  public ColMatchRunResponse start(@PathVariable int pid) {
    int uid = currentUser.require().getId();
    return service.start(uid, pid);
  }

  @GetMapping("/{runId}")
  public ColMatchRunResponse get(@PathVariable int pid, @PathVariable long runId) {
    int uid = currentUser.require().getId();
    return service.getRun(uid, pid, runId);
  }

  @GetMapping("/latest")
  public ResponseEntity<ColMatchRunResponse> latest(@PathVariable int pid) {
    int uid = currentUser.require().getId();
    ColMatchRunResponse run = service.latest(uid, pid);
    return run == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(run);
  }
}
