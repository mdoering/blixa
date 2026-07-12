package org.catalogueoflife.editor.coldp;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// Exposes the ColDP identifier-scope vocabulary so the import / project-settings UI can offer a
// "preserve source ids under scope" dropdown. Loaded from the live ChecklistBank vocab (400+
// registries) via IdScopeService, NOT the small in-JVM Identifier.Scope enum -- the generic scopes
// (local/doi/url/urn/lsid) are excluded there. Authenticated read only (SecurityConfig:
// anyRequest().authenticated() covers /api/coldp/**).
@RestController
public class IdScopeController {

  private final IdScopeService service;

  public IdScopeController(IdScopeService service) {
    this.service = service;
  }

  @GetMapping("/api/coldp/id-scopes")
  public List<String> idScopes() {
    return service.scopes();
  }
}
