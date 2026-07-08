package org.catalogueoflife.editor.task.dto;

import java.time.OffsetDateTime;

/**
 * changeCount is a scalar subquery over `change.task_id` (see TaskMapper.findByProject) -- it's
 * 0 for every task until Task 2 wires AuditService to stamp changes with the active X-Task-Id.
 */
public record TaskResponse(Integer id, String title, String description, String status,
    Integer userId, String username, OffsetDateTime createdAt, OffsetDateTime closedAt,
    long changeCount) {}
