package org.catalogueoflife.editor.lock.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * ttlSeconds is nullable -- LockService clamps a missing value to a 300s default. taskId is
 * nullable -- locks are advisory whether or not intent is declared; when present, LockService
 * validates it names an OPEN task in this project (else 400) so the lock list can show *why* the
 * entity is locked (see LockResponse.taskTitle).
 */
public record AcquireLockRequest(@NotBlank String entityType, int entityId, Integer ttlSeconds, Integer taskId) {}
