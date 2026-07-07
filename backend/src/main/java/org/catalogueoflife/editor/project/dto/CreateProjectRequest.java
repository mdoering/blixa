package org.catalogueoflife.editor.project.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateProjectRequest(
    @NotBlank String slug,
    @NotBlank String title,
    String nomCode) {}
