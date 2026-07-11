package org.catalogueoflife.editor.clb;

import org.catalogueoflife.editor.auth.CurrentUser;
import org.catalogueoflife.editor.clb.dto.ClbImportRequest;
import org.catalogueoflife.editor.clb.dto.ClbImportSummary;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// The Direct CLB Taxon Import HTTP surface: a single synchronous POST (unlike the ColDP import's
// async start/poll -- a CLB subtree is capped at coldp.clb-import.max-usages and involves no file
// upload, so it's fast enough to serve inline). Owner/editor + focal-usage-accepted are both
// enforced in ClbImportService itself, not here.
@RestController
@RequestMapping("/api/projects/{pid}/usages/{focalId}/clb-import")
public class ClbImportController {

  private final ClbImportService service;
  private final CurrentUser currentUser;

  public ClbImportController(ClbImportService service, CurrentUser currentUser) {
    this.service = service;
    this.currentUser = currentUser;
  }

  @PostMapping
  public ClbImportSummary importFromClb(@PathVariable int pid, @PathVariable int focalId,
      @RequestBody ClbImportRequest req) {
    return service.importFromClb(currentUser.require().getId(), pid, focalId, req);
  }
}
