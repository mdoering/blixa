package org.catalogueoflife.editor.invite;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;
import org.catalogueoflife.editor.invite.dto.CreateInvitationRequest;
import org.catalogueoflife.editor.project.ProjectService;
import org.catalogueoflife.editor.project.Role;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

// Owner-issued email invitations to a project. The mutating owner methods don't send email
// themselves: InvitationController does that after the transaction commits (no SMTP round-trip
// while holding a DB connection, and the emailed link is guaranteed to exist).
@Service
public class InvitationService {

  static final Duration VALIDITY = Duration.ofDays(30);
  // Same permissive shape check as AppUserService: reject obvious junk, don't verify deliverability.
  private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
  private static final SecureRandom RANDOM = new SecureRandom();

  private final InvitationMapper invitations;
  private final ProjectService projectService;

  public InvitationService(InvitationMapper invitations, ProjectService projectService) {
    this.invitations = invitations;
    this.projectService = projectService;
  }

  @Transactional
  public ProjectInvitation create(int actorId, int projectId, CreateInvitationRequest req) {
    projectService.requireOwner(actorId, projectId);
    String email = req.email() == null ? "" : req.email().trim();
    if (!EMAIL.matcher(email).matches()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "a valid email address is required");
    }
    Role role = req.role() == null || req.role().isBlank() ? Role.EDITOR : Role.fromDb(req.role().trim());
    if (invitations.hasActiveInvite(projectId, email)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          email + " already has a pending invitation — use Resend");
    }
    ProjectInvitation inv = new ProjectInvitation();
    inv.setProjectId(projectId);
    inv.setEmail(email);
    inv.setRole(role.dbValue());
    inv.setMessage(req.message() == null || req.message().isBlank() ? null : req.message().trim());
    inv.setToken(newToken());
    inv.setInvitedBy(actorId);
    inv.setExpiresAt(OffsetDateTime.now().plus(VALIDITY));
    invitations.insert(inv);
    return invitations.findById(inv.getId());
  }

  public List<ProjectInvitation> listPending(int actorId, int projectId) {
    projectService.requireOwner(actorId, projectId);
    return invitations.findPendingByProject(projectId);
  }

  // New token (the old link stops working) and a fresh 30-day window.
  @Transactional
  public ProjectInvitation resend(int actorId, int projectId, int id) {
    projectService.requireOwner(actorId, projectId);
    ProjectInvitation inv = invitations.findById(id);
    if (inv == null || inv.getProjectId() != projectId || inv.getAcceptedAt() != null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "invitation not found");
    }
    invitations.updateToken(id, newToken(), OffsetDateTime.now().plus(VALIDITY));
    return invitations.findById(id);
  }

  @Transactional
  public void revoke(int actorId, int projectId, int id) {
    projectService.requireOwner(actorId, projectId);
    if (invitations.deletePending(projectId, id) == 0) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "invitation not found");
    }
  }

  // 32 random bytes, base64url without padding (43 chars) -- the link is the credential.
  static String newToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
