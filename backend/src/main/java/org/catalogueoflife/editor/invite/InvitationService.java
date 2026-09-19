package org.catalogueoflife.editor.invite;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;
import org.catalogueoflife.editor.invite.dto.CreateInvitationRequest;
import org.catalogueoflife.editor.invite.dto.InvitationPreview;
import org.catalogueoflife.editor.project.ProjectMember;
import org.catalogueoflife.editor.project.ProjectMemberMapper;
import org.catalogueoflife.editor.project.ProjectService;
import org.catalogueoflife.editor.project.Role;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserMapper;
import org.catalogueoflife.editor.user.UserState;
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
  private final AppUserMapper users;
  private final ProjectMemberMapper members;

  public InvitationService(InvitationMapper invitations, ProjectService projectService,
      AppUserMapper users, ProjectMemberMapper members) {
    this.invitations = invitations;
    this.projectService = projectService;
    this.users = users;
    this.members = members;
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

  public InvitationPreview preview(String token) {
    ProjectInvitation inv = requireByToken(token);
    String status = inv.getAcceptedAt() != null ? "ACCEPTED" : inv.isExpired() ? "EXPIRED" : "VALID";
    return new InvitationPreview(inv.getProjectTitle(), inv.getInvitedByName(), inv.getRole(),
        inv.getMessage(), status);
  }

  // The signed-in user redeems the link. The owner's invitation vouches for them: a PENDING account
  // becomes ACTIVE (no admin approval), and a blank account email is filled from the invitation (it
  // demonstrably reached them). An existing member keeps their role -- an invitation never downgrades.
  // A DISABLED account stays locked out: an invite must not undo an admin's decision.
  @Transactional
  public int accept(int userId, String token) {
    ProjectInvitation inv = requireByToken(token);
    if (inv.getAcceptedAt() != null || inv.isExpired()) {
      throw new ResponseStatusException(HttpStatus.GONE, "this invitation has expired or was already used");
    }
    AppUser user = users.findById(userId);
    if (UserState.DISABLED.name().equals(user.getState())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "account disabled");
    }
    if (invitations.markAccepted(inv.getId(), userId) == 0) {
      // lost a race with a concurrent accept of the same link
      throw new ResponseStatusException(HttpStatus.GONE, "this invitation has expired or was already used");
    }
    boolean changed = false;
    if (UserState.PENDING.name().equals(user.getState())) {
      user.setState(UserState.ACTIVE.name());
      changed = true;
    }
    if (user.getEmail() == null || user.getEmail().isBlank()) {
      user.setEmail(inv.getEmail());
      changed = true;
    }
    if (changed) users.update(user);
    if (members.findRole(inv.getProjectId(), userId) == null) {
      members.upsert(new ProjectMember(inv.getProjectId(), userId, inv.getRole()));
    }
    return inv.getProjectId();
  }

  private ProjectInvitation requireByToken(String token) {
    ProjectInvitation inv = token == null ? null : invitations.findByToken(token);
    if (inv == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "invitation not found");
    }
    return inv;
  }

  // 32 random bytes, base64url without padding (43 chars) -- the link is the credential.
  static String newToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
