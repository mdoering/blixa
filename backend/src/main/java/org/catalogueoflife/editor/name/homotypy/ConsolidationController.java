package org.catalogueoflife.editor.name.homotypy;

import java.util.List;
import org.catalogueoflife.editor.auth.CurrentUser;
import org.catalogueoflife.editor.name.homotypy.dto.ConflictCluster;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{pid}/usages/{id}")
public class ConsolidationController {

  private final ConsolidationService service;
  private final CurrentUser currentUser;

  public ConsolidationController(ConsolidationService service, CurrentUser currentUser) {
    this.service = service;
    this.currentUser = currentUser;
  }

  @GetMapping("/homotypic/conflicts")
  public List<ConflictCluster> conflicts(@PathVariable int pid, @PathVariable int id) {
    return service.scan(currentUser.require().getId(), pid, id);
  }
}
