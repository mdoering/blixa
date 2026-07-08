package org.catalogueoflife.editor.project.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateProjectMetadataRequest(
    @NotBlank String title,
    String alias,
    String description,
    String nomCode,
    String license,
    String geographicScope,
    String taxonomicScope) {}
