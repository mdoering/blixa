package org.catalogueoflife.editor.project.dto;

import java.util.List;
import java.util.Locale;
import org.catalogueoflife.editor.project.Licenses;
import org.catalogueoflife.editor.project.Project;

public record ProjectResponse(
    Integer id, String title, String alias, String description,
    String nomCode, String license,
    String geographicScope, String taxonomicScope, String role,
    boolean gbifOccurrenceLayer, List<String> identifierScopes) {

  public static ProjectResponse of(Project p, String role) {
    return new ProjectResponse(p.getId(), p.getTitle(), p.getAlias(), p.getDescription(),
        // wire form matches what the UI/ColDP use: lowercase nomCode (e.g. "zoological") and the
        // SPDX license id (e.g. "CC0-1.0") -- NOT the raw enum .name() (ZOOLOGICAL / CC0).
        p.getNomCode() == null ? null : p.getNomCode().name().toLowerCase(Locale.ROOT),
        Licenses.toWire(p.getLicense()),
        p.getGeographicScope(), p.getTaxonomicScope(), role, p.getGbifOccurrenceLayer(),
        p.getIdentifierScopes());
  }
}
