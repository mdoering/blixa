package org.catalogueoflife.editor.child;

import java.util.List;
import org.catalogueoflife.editor.child.dto.PropertyKeyInfo;
import org.catalogueoflife.editor.project.ProjectService;
import org.catalogueoflife.editor.project.Role;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

// Manage a project's standard taxon property keys: the overview (used ∪ defined keys with counts +
// descriptions), defining/describing a key, and reconciling variant spellings into a canonical key.
// Mirrors the journal-name reconciliation in ReferenceService (containerTitleFacet /
// mergeContainerTitle) -- reads are open to any member, writes are owner/editor only.
@Service
public class PropertyKeyService {

  private final PropertyKeyMapper mapper;
  private final ProjectService projects;

  public PropertyKeyService(PropertyKeyMapper mapper, ProjectService projects) {
    this.mapper = mapper;
    this.projects = projects;
  }

  public List<PropertyKeyInfo> keys(int userId, int projectId) {
    projects.requireRole(userId, projectId);
    return mapper.keyFacet(projectId);
  }

  // Define a standard key or edit its description (upsert). Returns the key's resulting overview row.
  public PropertyKeyInfo defineKey(int userId, int projectId, String key, String description) {
    requireEditor(userId, projectId);
    String trimmedKey = key == null ? null : key.trim();
    if (trimmedKey == null || trimmedKey.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "key must not be blank");
    }
    String desc = description == null || description.isBlank() ? null : description.trim();
    mapper.upsertDefinition(projectId, trimmedKey, desc);
    return mapper.keyInfo(projectId, trimmedKey);
  }

  // Removes only the key's definition (its property_key row); never touches property rows.
  public void deleteDefinition(int userId, int projectId, String key) {
    requireEditor(userId, projectId);
    mapper.deleteDefinition(projectId, key);
  }

  // Rewrites every property row whose key is one of `variants` to `canonical`, and folds the
  // variants' definitions into the canonical (the canonical keeps its own description, or inherits
  // the first non-blank variant description if it had none). Owner/editor gated. Mirrors
  // ReferenceService.mergeContainerTitle. Returns the number of property rows rewritten.
  @Transactional
  public int mergeKeys(int userId, int projectId, String canonical, List<String> variants) {
    requireEditor(userId, projectId);
    if (canonical == null || canonical.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "canonical must not be blank");
    }
    if (variants == null || variants.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "variants must not be empty");
    }
    // fold descriptions: keep the canonical's, else adopt the first non-blank variant description
    String desc = mapper.descriptionOf(projectId, canonical);
    if (desc == null || desc.isBlank()) {
      for (String v : variants) {
        if (v.equals(canonical)) {
          continue;
        }
        String d = mapper.descriptionOf(projectId, v);
        if (d != null && !d.isBlank()) {
          desc = d;
          break;
        }
      }
    }
    int updated = mapper.mergeKey(projectId, canonical, variants);
    // drop the variants' definitions (never the canonical's own row)
    List<String> toDrop = variants.stream().filter(v -> !v.equals(canonical)).toList();
    if (!toDrop.isEmpty()) {
      mapper.deleteDefinitions(projectId, toDrop);
    }
    // ensure the canonical carries the folded description
    if (desc != null && !desc.isBlank()) {
      mapper.upsertDefinition(projectId, canonical, desc);
    }
    return updated;
  }

  private void requireEditor(int userId, int projectId) {
    String role = projects.requireRole(userId, projectId);
    if (!role.equals(Role.OWNER.dbValue()) && !role.equals(Role.EDITOR.dbValue())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "owner or editor required");
    }
  }
}
