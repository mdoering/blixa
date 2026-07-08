package org.catalogueoflife.editor.validation.dto;

import java.time.OffsetDateTime;

// The API-facing projection of one `issue` row (see V7__issue.sql / Issue), read directly by
// IssueMapper.findByProject/findById via MyBatis's automatic constructor-based result mapping
// (record + `-parameters`, the same way TaskMapper's TaskResponse reads do) -- every column the
// SQL selects is aliased to the exact camelCase name below. `severity`/`status` are LOWER()'d in
// SQL (the API speaks lowercase, matching Role.dbValue()/TaskStatus.apiValue()'s convention);
// `context` stays the JSONB column's raw JSON text, same as Issue.context/Change.diff -- callers
// parse it themselves. `reviewerUsername` comes from a LEFT JOIN app_user (null until reviewed).
public record IssueResponse(
    Integer id,
    String entityType,
    Integer entityId,
    String rule,
    String severity,
    String message,
    String context,
    String status,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    Integer reviewerId,
    String reviewerUsername,
    OffsetDateTime reviewedAt) {}
