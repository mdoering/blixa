package org.catalogueoflife.editor.child;

import java.util.List;
import java.util.Map;
import org.catalogueoflife.editor.auth.CurrentUser;
import org.catalogueoflife.editor.child.dto.PropertyKeyDefinitionRequest;
import org.catalogueoflife.editor.child.dto.PropertyKeyInfo;
import org.catalogueoflife.editor.child.dto.PropertyKeyMergeRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// Project-wide standard taxon property keys: overview facet, define/describe, reconcile (merge).
// Mirrors the /references/facets/container-title endpoints for journal names.
@RestController
@RequestMapping("/api/projects/{pid}/property-keys")
public class PropertyKeyController {

  private final PropertyKeyService service;
  private final CurrentUser currentUser;

  public PropertyKeyController(PropertyKeyService service, CurrentUser currentUser) {
    this.service = service;
    this.currentUser = currentUser;
  }

  // Used ∪ defined keys with counts + descriptions. Also the source for the property-key autocomplete.
  @GetMapping
  public List<PropertyKeyInfo> keys(@PathVariable int pid) {
    int uid = currentUser.require().getId();
    return service.keys(uid, pid);
  }

  // Define a standard key / edit its description. Returns the key's resulting overview row. The key
  // is in the body, not the path -- free-form keys (spaces, slashes, dots) can't be a path segment.
  @PutMapping
  public PropertyKeyInfo define(@PathVariable int pid, @RequestBody PropertyKeyDefinitionRequest req) {
    int uid = currentUser.require().getId();
    return service.defineKey(uid, pid, req.key(), req.description());
  }

  // Removes only the definition (never property rows). Key travels as a query param for the same
  // reason (StrictHttpFirewall would reject an encoded space/slash in the path).
  @DeleteMapping
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable int pid, @RequestParam String key) {
    int uid = currentUser.require().getId();
    service.deleteDefinition(uid, pid, key);
  }

  // Rewrites every property whose key is one of req.variants() to req.canonical(), folding definitions.
  @PostMapping("/merge")
  public Map<String, Integer> merge(@PathVariable int pid, @RequestBody PropertyKeyMergeRequest req) {
    int uid = currentUser.require().getId();
    int updated = service.mergeKeys(uid, pid, req.canonical(), req.variants());
    return Map.of("updated", updated);
  }
}
