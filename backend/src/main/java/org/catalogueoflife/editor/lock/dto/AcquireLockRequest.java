package org.catalogueoflife.editor.lock.dto;

import jakarta.validation.constraints.NotBlank;

/** ttlSeconds is nullable -- LockService clamps a missing value to a 300s default. */
public record AcquireLockRequest(@NotBlank String entityType, int entityId, Integer ttlSeconds) {}
