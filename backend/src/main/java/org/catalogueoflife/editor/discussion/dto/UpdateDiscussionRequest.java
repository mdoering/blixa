package org.catalogueoflife.editor.discussion.dto;

import jakarta.validation.constraints.NotBlank;

// version drives the optimistic-lock CAS (null/stale -> 409). See DiscussionService.update.
public record UpdateDiscussionRequest(@NotBlank String title, String body, Integer version) {}
