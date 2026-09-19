package org.catalogueoflife.editor.invite.dto;

import java.time.OffsetDateTime;
import org.catalogueoflife.editor.invite.ProjectInvitation;

// Owner-facing view of a pending invitation. acceptUrl carries the token so the owner can copy the
// link (e.g. when outgoing mail isn't configured).
public record InvitationResponse(int id, String email, String role, String message, String invitedBy,
    OffsetDateTime createdAt, OffsetDateTime expiresAt, boolean expired, String acceptUrl) {

  public static InvitationResponse of(ProjectInvitation i, String acceptUrl) {
    return new InvitationResponse(i.getId(), i.getEmail(), i.getRole(), i.getMessage(),
        i.getInvitedByName(), i.getCreatedAt(), i.getExpiresAt(), i.isExpired(), acceptUrl);
  }
}
