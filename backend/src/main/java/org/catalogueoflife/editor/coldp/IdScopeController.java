package org.catalogueoflife.editor.coldp;

import java.util.Arrays;
import java.util.List;
import life.catalogue.api.model.Identifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// Exposes the ColDP identifier-scope vocabulary (life.catalogue.api.model.Identifier.Scope) so
// the import UI can offer a "preserve source ids under scope" dropdown. Authenticated read only
// (see SecurityConfig: anyRequest().authenticated() covers /api/coldp/**); no service layer
// needed since the vocab is a fixed, in-JVM enum.
@RestController
public class IdScopeController {

  @GetMapping("/api/coldp/id-scopes")
  public List<String> idScopes() {
    return Arrays.stream(Identifier.Scope.values()).map(Identifier.Scope::prefix).toList();
  }
}
