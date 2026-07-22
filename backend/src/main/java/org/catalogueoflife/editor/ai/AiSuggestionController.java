package org.catalogueoflife.editor.ai;

import org.catalogueoflife.editor.auth.CurrentUser;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Runs a "gather AI suggestions" pass for a focal taxon and returns categorized cards (references
// verified against Crossref/DataCite; synonyms leading). Editor-only. The AI never writes -- the
// curator accepts individual cards through the existing create endpoints (handled client-side).
@RestController
@RequestMapping("/api/projects/{pid}/usages/{id}/ai")
public class AiSuggestionController {

  private final AiSuggestionService service;
  private final CurrentUser currentUser;

  public AiSuggestionController(AiSuggestionService service, CurrentUser currentUser) {
    this.service = service;
    this.currentUser = currentUser;
  }

  @PostMapping("/suggest")
  public AiSuggestionSet suggest(@PathVariable int pid, @PathVariable int id) {
    int uid = currentUser.require().getId();
    return service.suggest(uid, pid, id);
  }
}
