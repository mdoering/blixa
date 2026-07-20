package org.catalogueoflife.editor.name.homotypy;

import org.catalogueoflife.editor.auth.CurrentUser;
import org.catalogueoflife.editor.name.homotypy.dto.ApplyHomotypicRequest;
import org.catalogueoflife.editor.name.homotypy.dto.HomotypyProposal;
import org.catalogueoflife.editor.name.homotypy.dto.Synonymy;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{pid}/usages/{id}")
public class HomotypyController {

  private final HomotypyService service;
  private final CurrentUser currentUser;

  public HomotypyController(HomotypyService service, CurrentUser currentUser) {
    this.service = service;
    this.currentUser = currentUser;
  }

  @GetMapping("/homotypic/detect")
  public HomotypyProposal detect(@PathVariable int pid, @PathVariable int id) {
    return service.detect(currentUser.require().getId(), pid, id);
  }

  @PostMapping("/homotypic/apply")
  public Synonymy apply(@PathVariable int pid, @PathVariable int id, @RequestBody ApplyHomotypicRequest req) {
    return service.apply(currentUser.require().getId(), pid, id, req);
  }

  @GetMapping("/synonymy")
  public Synonymy synonymy(@PathVariable int pid, @PathVariable int id) {
    return service.synonymy(currentUser.require().getId(), pid, id);
  }
}
