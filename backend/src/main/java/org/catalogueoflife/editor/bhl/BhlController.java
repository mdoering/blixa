package org.catalogueoflife.editor.bhl;

import java.util.List;
import org.catalogueoflife.editor.auth.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// BHL tooling: availability (gates the UI) + publication search (find an item to link a reference
// to). Read-only, any project member.
@RestController
@RequestMapping("/api/projects/{pid}/bhl")
public class BhlController {

  private final BhlService service;
  private final CurrentUser currentUser;

  public BhlController(BhlService service, CurrentUser currentUser) {
    this.service = service;
    this.currentUser = currentUser;
  }

  @GetMapping("/config")
  public BhlConfigResponse config(@PathVariable int pid) {
    return service.config(currentUser.require().getId(), pid);
  }

  @GetMapping("/publication-search")
  public List<BhlItem> publicationSearch(@PathVariable int pid, @RequestParam String q) {
    return service.publicationSearch(currentUser.require().getId(), pid, q);
  }

  // Phase B: within a reference's linked BHL item, browse pages and get name-suggested pages so a
  // name can be pointed at its exact protologue page.
  @GetMapping("/items/{itemId}/pages")
  public List<BhlPage> itemPages(@PathVariable int pid, @PathVariable int itemId) {
    return service.itemPages(currentUser.require().getId(), pid, itemId);
  }

  @GetMapping("/items/{itemId}/name-pages")
  public List<BhlPage> namePages(@PathVariable int pid, @PathVariable int itemId,
      @RequestParam String name) {
    return service.namePagesInItem(currentUser.require().getId(), pid, itemId, name);
  }
}

