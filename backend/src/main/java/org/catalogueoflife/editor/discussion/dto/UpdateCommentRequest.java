package org.catalogueoflife.editor.discussion.dto;

import jakarta.validation.constraints.NotBlank;

// version drives the optimistic-lock CAS (null/stale -> 409).
public record UpdateCommentRequest(@NotBlank String body, Integer version) {}
