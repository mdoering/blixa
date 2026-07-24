package org.catalogueoflife.editor.gbif;

import org.catalogueoflife.editor.auth.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// GET /api/projects/{pid}/usages/{id}/gbif-types: the GBIF type-specimen candidates for a usage
// (resolved to COL, queried against GBIF's COL checklist). Any project member may read; the Types
// tab's "Import from GBIF" modal consumes it and imports ticked candidates through the normal
// POST .../type-material create path.
@RestController
@RequestMapping("/api/projects/{pid}/usages")
public class GbifTypeController {

  private final GbifTypeService service;
  private final CurrentUser currentUser;

  public GbifTypeController(GbifTypeService service, CurrentUser currentUser) {
    this.service = service;
    this.currentUser = currentUser;
  }

  @GetMapping("/{id}/gbif-types")
  public GbifTypesResponse gbifTypes(@PathVariable int pid, @PathVariable int id) {
    int uid = currentUser.require().getId();
    return service.findTypeSpecimens(uid, pid, id);
  }
}
