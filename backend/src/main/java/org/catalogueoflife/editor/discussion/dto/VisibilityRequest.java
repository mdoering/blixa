package org.catalogueoflife.editor.discussion.dto;

// visibility is validated against DiscussionVisibility in the service (bad value -> 400).
public record VisibilityRequest(String visibility) {}
