package org.catalogueoflife.editor.discussion.dto;

// status is validated against DiscussionStatus in the service (bad value -> 400).
public record StatusRequest(String status) {}
