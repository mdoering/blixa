package org.catalogueoflife.editor.discussion.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateDiscussionRequest(@NotBlank String title, String body) {}
