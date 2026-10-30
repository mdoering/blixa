package org.catalogueoflife.editor.name.homotypy;

import java.util.List;
import org.catalogueoflife.editor.auth.CurrentUser;
import org.catalogueoflife.editor.name.homotypy.dto.RelationConflictPair;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// The name relation cleanup: list the pairs of names with conflicting, redundant or duplicate
// relations, for a subtree (rootId) or the whole project. Any project role may scan.
@RestController
@RequestMapping("/api/projects/{pid}/name-relations")
public class NameRelationCleanupController {

  private final NameRelationCleanupService service;
  private final CurrentUser currentUser;

  public NameRelationCleanupController(NameRelationCleanupService service, CurrentUser currentUser) {
    this.service = service;
    this.currentUser = currentUser;
  }

  @GetMapping("/conflicts")
  public List<RelationConflictPair> conflicts(@PathVariable int pid,
      @RequestParam(required = false) Integer rootId) {
    return service.scan(currentUser.require().getId(), pid, rootId);
  }
}
