package org.catalogueoflife.editor.child;

import org.catalogueoflife.editor.auth.CurrentUser;
import org.catalogueoflife.editor.child.dto.MapData;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{pid}/usages/{uid}/map")
public class MapDataController {

  private final MapDataService service;
  private final CurrentUser currentUser;

  public MapDataController(MapDataService service, CurrentUser currentUser) {
    this.service = service;
    this.currentUser = currentUser;
  }

  @GetMapping
  public MapData get(@PathVariable int pid, @PathVariable int uid) {
    return service.get(currentUser.require().getId(), pid, uid);
  }
}
