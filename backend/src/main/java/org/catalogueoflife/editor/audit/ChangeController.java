package org.catalogueoflife.editor.audit;

import java.util.List;
import org.catalogueoflife.editor.auth.CurrentUser;
import org.catalogueoflife.editor.name.Pagination;
import org.catalogueoflife.editor.project.ProjectService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/projects/{pid}/changes")
public class ChangeController {

  private final ChangeMapper changes;
  private final ProjectService projects;
  private final CurrentUser currentUser;

  public ChangeController(ChangeMapper changes, ProjectService projects, CurrentUser currentUser) {
    this.changes = changes;
    this.projects = projects;
    this.currentUser = currentUser;
  }

  @GetMapping
  public List<Change> list(@PathVariable int pid,
      @RequestParam(required = false) String entityType,
      @RequestParam(required = false) Integer entityId,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    int uid = currentUser.require().getId();
    projects.requireRole(uid, pid); // any member may read -- 404 if not a member
    int clampedLimit = Pagination.clampLimit(limit);
    int clampedOffset = Pagination.clampOffset(offset);
    if (entityId != null) {
      if (entityType == null || entityType.isBlank()) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "entityType is required when entityId is given");
      }
      return changes.findByEntity(pid, entityType, entityId, clampedLimit, clampedOffset);
    }
    return changes.findByProject(pid, clampedLimit, clampedOffset);
  }
}
