package org.catalogueoflife.editor.merge;

import java.util.List;
import org.catalogueoflife.editor.auth.CurrentUser;
import org.catalogueoflife.editor.merge.dto.MappingRow;
import org.catalogueoflife.editor.merge.dto.MergeOverride;
import org.catalogueoflife.editor.merge.dto.MergeRunResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// The supervised project-merge compute-plan job's HTTP surface: POST kicks off
// MergeService.start (authorizes owner/editor on the target + membership on the source, inserts
// the RUNNING merge_run row, fires the @Async computePlan) -- returning 202 immediately (the row
// may already be PLANNED by the time the response is built, for a tiny project pair, but the
// client is never blocked waiting for the job). GET /{runId} polls a specific run's summary +
// metrics (never the full plan blob -- see MergeRunResponse.of); GET /{runId}/mapping pages the
// plan's name/reference candidates, display-enriched, for the review table; GET /latest is the
// load-on-mount view. "/latest" is a literal path segment and "/{runId}" is a variable one at the
// same position -- Spring's RequestMappingHandlerMapping ranks candidate patterns by specificity
// (literal segments outrank variables) regardless of declaration order in this class, mirroring
// ImportRunController/ExportRunController/ColMatchRunController's identical "/latest" vs
// "/{runId}" routing. PUT /{runId}/overrides (Task 5) lets the curator confirm/reject/re-point
// individual candidates on a still-PLANNED plan before apply (Task 6/7) -- same owner/editor
// authorization tier as POST, since it mutates the plan the apply will eventually act on. All
// endpoints are nested under the TARGET project (source is a query param on POST only, or embedded
// in the stored run thereafter) since the merge itself is scoped to (and eventually writes into)
// the target.
@RestController
@RequestMapping("/api/projects/{targetId}/merge")
public class MergeController {

  private final MergeService service;
  private final CurrentUser currentUser;

  public MergeController(MergeService service, CurrentUser currentUser) {
    this.service = service;
    this.currentUser = currentUser;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.ACCEPTED)
  public MergeRunResponse start(@PathVariable int targetId, @RequestParam("source") int source) {
    int uid = currentUser.require().getId();
    return service.start(uid, targetId, source);
  }

  @GetMapping("/latest")
  public ResponseEntity<MergeRunResponse> latest(@PathVariable int targetId) {
    int uid = currentUser.require().getId();
    MergeRunResponse run = service.latest(uid, targetId);
    return run == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(run);
  }

  @GetMapping("/{runId}")
  public MergeRunResponse get(@PathVariable int targetId, @PathVariable long runId) {
    int uid = currentUser.require().getId();
    return service.get(uid, targetId, runId);
  }

  @GetMapping("/{runId}/mapping")
  public List<MappingRow> mapping(@PathVariable int targetId, @PathVariable long runId,
      @RequestParam String entity, @RequestParam(required = false) String category,
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
    int uid = currentUser.require().getId();
    return service.getMapping(uid, targetId, runId, entity, category, page, size);
  }

  @PutMapping("/{runId}/overrides")
  public MergeRunResponse overrides(@PathVariable int targetId, @PathVariable long runId,
      @RequestBody List<MergeOverride> overrides) {
    int uid = currentUser.require().getId();
    return service.applyOverrides(uid, targetId, runId, overrides);
  }
}
