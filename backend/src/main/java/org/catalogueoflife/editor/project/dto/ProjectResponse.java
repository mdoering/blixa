package org.catalogueoflife.editor.project.dto;

import java.time.LocalDate;
import org.catalogueoflife.editor.project.Project;

public record ProjectResponse(
    Long id, String slug, String title, String alias, String description,
    String nomCode, String license, String version, LocalDate issued,
    String geographicScope, String taxonomicScope, String doi, String role) {

  public static ProjectResponse of(Project p, String role) {
    return new ProjectResponse(p.getId(), p.getSlug(), p.getTitle(), p.getAlias(),
        p.getDescription(), p.getNomCode(), p.getLicense(), p.getVersion(), p.getIssued(),
        p.getGeographicScope(), p.getTaxonomicScope(), p.getDoi(), role);
  }
}
