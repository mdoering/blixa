package org.catalogueoflife.editor.project.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateProjectRequest(
    @NotBlank String title,
    String alias,
    String nomCode) {}
