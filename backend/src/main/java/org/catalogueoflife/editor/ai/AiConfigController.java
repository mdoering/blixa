package org.catalogueoflife.editor.ai;

import org.catalogueoflife.editor.auth.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Tells the frontend whether to surface the AI-curation affordance and how to label it. Returns the
// resolved provider + model and an `available` flag (a configured provider WITH a backend key) --
// never a key. Any project member may read it.
@RestController
@RequestMapping("/api/projects/{pid}/ai")
public class AiConfigController {

  private final AiConfigService service;
  private final CurrentUser currentUser;

  public AiConfigController(AiConfigService service, CurrentUser currentUser) {
    this.service = service;
    this.currentUser = currentUser;
  }

  @GetMapping("/config")
  public AiConfigResponse config(@PathVariable int pid) {
    int uid = currentUser.require().getId();
    return service.get(uid, pid);
  }
}
