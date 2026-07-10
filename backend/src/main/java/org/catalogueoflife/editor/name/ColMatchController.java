package org.catalogueoflife.editor.name;

import java.util.List;
import org.catalogueoflife.editor.auth.CurrentUser;
import org.catalogueoflife.editor.name.dto.ColMatchCandidate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// GET /api/projects/{pid}/usages/{id}/col-match: matches a single usage against the published COL
// checklist (ColMatchService/ClbMatchClient) -- best match first, then alternatives[], empty list
// when unmatched. Consumed by the match modal (Task 8) and later the bulk-match workflow.
@RestController
@RequestMapping("/api/projects/{pid}/usages")
public class ColMatchController {

  private final ColMatchService service;
  private final CurrentUser currentUser;

  public ColMatchController(ColMatchService service, CurrentUser currentUser) {
    this.service = service;
    this.currentUser = currentUser;
  }

  @GetMapping("/{id}/col-match")
  public List<ColMatchCandidate> match(@PathVariable int pid, @PathVariable int id) {
    int uid = currentUser.require().getId();
    return service.match(uid, pid, id);
  }
}
