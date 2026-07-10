package org.catalogueoflife.editor.project.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.catalogueoflife.editor.project.IdentifierScope;

public record UpdateProjectMetadataRequest(
    @NotBlank String title,
    String alias,
    String description,
    String nomCode,
    String license,
    String geographicScope,
    String taxonomicScope,
    Boolean gbifOccurrenceLayer,
    // Which alternative_id CURIE scopes (e.g. "ipni", "gbif") the Details form renders a real
    // identifier field for, each with an optional CLB dataset key (matchable iff datasetKey is
    // set). Same "omitted -> keep existing" contract as gbifOccurrenceLayer (see
    // ProjectService.updateMetadata); an explicit [] clears the configured scopes.
    List<IdentifierScope> identifierScopes) {}
