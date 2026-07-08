package org.catalogueoflife.editor.project.dto;

import org.catalogueoflife.editor.project.Project;

public record ProjectResponse(
    Integer id, String title, String alias, String description,
    String nomCode, String license,
    String geographicScope, String taxonomicScope, String role) {

  public static ProjectResponse of(Project p, String role) {
    return new ProjectResponse(p.getId(), p.getTitle(), p.getAlias(), p.getDescription(),
        p.getNomCode() == null ? null : p.getNomCode().name(),
        p.getLicense() == null ? null : p.getLicense().name(),
        p.getGeographicScope(), p.getTaxonomicScope(), role);
  }
}
