package org.catalogueoflife.editor.invite.dto;

// role defaults to editor when omitted; message is optional (blank -> none).
public record CreateInvitationRequest(String email, String role, String message) {}
