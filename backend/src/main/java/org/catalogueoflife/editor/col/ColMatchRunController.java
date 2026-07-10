package org.catalogueoflife.editor.col;

import org.catalogueoflife.editor.auth.CurrentUser;
import org.catalogueoflife.editor.col.dto.ColMatchRunResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// The bulk "Match all to COL" job's HTTP surface (Task 3): POST kicks off ColMatchJobService.start,
// which authorizes the caller, inserts the RUNNING col_match_run row and fires the @Async run() --
// returning 202 immediately (the row may already be DONE by the time the response is built, for a
// small/empty project, but the client is never blocked waiting for the job); GET polls the same row
// by id for progress/summary. Mirrors IssueController's project-scoped, thin-controller-delegates-
// to-service shape.
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
}
