package org.catalogueoflife.editor.lock.dto;

import java.time.OffsetDateTime;

/**
 * heldByMe distinguishes "you hold this lock" from "someone else does" without the client having
 * to compare userId against its own session -- see LockService for how acquire() returns this
 * same shape with heldByMe=false (and a 409) when another user's still-active lock blocked a
 * takeover. taskId/taskTitle are the optional declared intent behind the lock (null when none was
 * given) -- taskTitle is joined in by LockMapper's read queries so the lock list shows *why* an
 * entity is locked without a separate task lookup.
 */
public record LockResponse(Integer id, String entityType, Integer entityId, Integer userId,
    String username, OffsetDateTime acquiredAt, OffsetDateTime expiresAt, boolean heldByMe,
    Integer taskId, String taskTitle) {}
