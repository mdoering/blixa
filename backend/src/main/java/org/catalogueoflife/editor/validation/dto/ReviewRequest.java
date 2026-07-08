package org.catalogueoflife.editor.validation.dto;

// Body of POST /api/projects/{pid}/issues/{id}/review. `action` is one of accept/reject/reopen
// (case-insensitive, parsed by IssueService) -- accept -> ACCEPTED, reject -> REJECTED,
// reopen -> OPEN (clearing the reviewer); anything else is a 400.
public record ReviewRequest(String action) {}
