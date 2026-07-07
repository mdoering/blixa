package org.catalogueoflife.editor.project.dto;

import jakarta.validation.constraints.NotBlank;

public record MemberRequest(@NotBlank String username, @NotBlank String role) {}
