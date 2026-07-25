package org.catalogueoflife.editor.name;

import jakarta.validation.Valid;
import org.catalogueoflife.editor.auth.CurrentUser;
import org.catalogueoflife.editor.name.dto.GenusLinkRequest;
import org.catalogueoflife.editor.name.dto.LinkGeneraResponse;
import org.catalogueoflife.editor.name.dto.NameUsageResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// The nomenclatural-genus link (genus_id): a per-taxon pin/clear and a project-wide fill-missing
// batch. See the genus-link design.
@RestController
@RequestMapping("/api/projects/{pid}")
public class GenusLinkController {

  private final NameUsageService service;
  private final CurrentUser currentUser;

  public GenusLinkController(NameUsageService service, CurrentUser currentUser) {
    this.service = service;
    this.currentUser = currentUser;
  }

  // Pin (or clear) one binomial's nomenclatural genus.
  @PutMapping("/usages/{id}/genus")
  public NameUsageResponse updateGenus(@PathVariable int pid, @PathVariable int id,
      @Valid @RequestBody GenusLinkRequest req) {
    int uid = currentUser.require().getId();
    return service.updateGenusId(uid, pid, id, req);
  }

  // Link every still-unlinked binomial to its genus usage (never overrides an existing link).
  @PostMapping("/link-genera")
  public LinkGeneraResponse linkGenera(@PathVariable int pid) {
    int uid = currentUser.require().getId();
    return service.linkGenera(uid, pid);
  }
}
