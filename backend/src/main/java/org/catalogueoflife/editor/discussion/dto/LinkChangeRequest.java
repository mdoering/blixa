package org.catalogueoflife.editor.discussion.dto;

// changeId is validated to belong to the project in the service (else 404).
public record LinkChangeRequest(Integer changeId) {}
