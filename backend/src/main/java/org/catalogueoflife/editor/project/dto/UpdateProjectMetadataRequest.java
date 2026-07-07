package org.catalogueoflife.editor.project.dto;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;

public record UpdateProjectMetadataRequest(
    @NotBlank String title,
    String alias,
    String description,
    String nomCode,
    String license,
    String version,
    LocalDate issued,
    String geographicScope,
    String taxonomicScope,
    String doi) {}
