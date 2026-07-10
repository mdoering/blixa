package org.catalogueoflife.editor.project.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

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
    // identifier field for. Same "omitted -> keep existing" contract as gbifOccurrenceLayer
    // (see ProjectService.updateMetadata); an explicit [] clears the configured scopes.
    List<String> identifierScopes) {}
