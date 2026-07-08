package org.catalogueoflife.editor.validation;

import java.util.List;
import org.catalogueoflife.editor.auth.CurrentUser;
import org.catalogueoflife.editor.validation.dto.IssueResponse;
import org.catalogueoflife.editor.validation.dto.IssueSummaryResponse;
import org.catalogueoflife.editor.validation.dto.ReviewRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// Class-level mapping is the project root (not "/issues") because this controller also owns the
// project-wide POST /revalidate action (see the plan: "IssueController ... plus POST
// /api/projects/{pid}/revalidate") -- Spring has no way to have one method opt out of a
// class-level @RequestMapping prefix, so every method spells out its own "/issues..." or
// "/revalidate" suffix instead.
@RestController
@RequestMapping("/api/projects/{pid}")
public class IssueController {

  private final IssueService service;
  private final CurrentUser currentUser;

  public IssueController(IssueService service, CurrentUser currentUser) {
    this.service = service;
    this.currentUser = currentUser;
  }

  @GetMapping("/issues")
  public List<IssueResponse> list(@PathVariable int pid,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String severity,
      @RequestParam(required = false) String entityType,
      @RequestParam(required = false) Integer entityId,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    int uid = currentUser.require().getId();
    return service.list(uid, pid, status, severity, entityType, entityId, limit, offset);
  }

  @GetMapping("/issues/summary")
  public IssueSummaryResponse summary(@PathVariable int pid) {
    int uid = currentUser.require().getId();
    return service.summary(uid, pid);
  }

  @PostMapping("/issues/{id}/review")
  public IssueResponse review(@PathVariable int pid, @PathVariable int id, @RequestBody ReviewRequest req) {
    int uid = currentUser.require().getId();
    return service.review(uid, pid, id, req);
  }

  // On-demand full-project recompute (features.md "regenerate on demand, e.g. after a deploy").
  @PostMapping("/revalidate")
  public IssueSummaryResponse revalidate(@PathVariable int pid) {
    int uid = currentUser.require().getId();
    return service.revalidateProject(uid, pid);
  }
}
