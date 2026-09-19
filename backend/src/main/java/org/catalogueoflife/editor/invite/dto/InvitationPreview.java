package org.catalogueoflife.editor.invite.dto;

// What an invitee sees before signing in. status: VALID | EXPIRED | ACCEPTED.
public record InvitationPreview(String projectTitle, String invitedBy, String role, String message,
    String status) {}
