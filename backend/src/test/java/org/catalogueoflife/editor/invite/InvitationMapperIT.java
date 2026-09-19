package org.catalogueoflife.editor.invite;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import org.catalogueoflife.editor.project.Project;
import org.catalogueoflife.editor.project.ProjectService;
import org.catalogueoflife.editor.project.dto.CreateProjectRequest;
import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class InvitationMapperIT extends AbstractPostgresIT {

  @Autowired InvitationMapper mapper;
  @Autowired ProjectService projects;
  @Autowired AppUserService users;

  private AppUser user(String username) {
    if (users.requireByUsernameOrNull(username) == null) users.createLocal(username, "pw", username);
    return users.requireByUsernameOrNull(username);
  }

  private ProjectInvitation invitation(int projectId, int invitedBy, String email, String token,
      OffsetDateTime expiresAt) {
    ProjectInvitation inv = new ProjectInvitation();
    inv.setProjectId(projectId);
    inv.setEmail(email);
    inv.setRole("editor");
    inv.setMessage("welcome");
    inv.setToken(token);
    inv.setInvitedBy(invitedBy);
    inv.setExpiresAt(expiresAt);
    mapper.insert(inv);
    return inv;
  }

  @Test
  void insertJoinAndSingleUseAccept() {
    AppUser owner = user("invMapOwner");
    Project p = projects.create(owner.getId(), new CreateProjectRequest("Invitation mapper", null, null));
    ProjectInvitation inv = invitation(p.getId(), owner.getId(), "Someone@Example.org", "tok-map-1",
        OffsetDateTime.now().plusDays(30));
    assertThat(inv.getId()).isNotNull();

    ProjectInvitation found = mapper.findByToken("tok-map-1");
    assertThat(found.getProjectTitle()).isEqualTo("Invitation mapper");
    assertThat(found.getInvitedByName()).isEqualTo("invMapOwner");
    assertThat(found.getCreatedAt()).isNotNull();
    assertThat(found.isExpired()).isFalse();

    // duplicate check is case-insensitive on the email
    assertThat(mapper.hasActiveInvite(p.getId(), "someone@example.org")).isTrue();
    assertThat(mapper.findPendingByProject(p.getId()))
        .extracting(ProjectInvitation::getId).containsExactly(inv.getId());

    assertThat(mapper.markAccepted(inv.getId(), owner.getId())).isEqualTo(1);
    assertThat(mapper.markAccepted(inv.getId(), owner.getId())).isZero(); // single use
    assertThat(mapper.findById(inv.getId()).getAcceptedBy()).isEqualTo(owner.getId());
    assertThat(mapper.hasActiveInvite(p.getId(), "someone@example.org")).isFalse();
    assertThat(mapper.findPendingByProject(p.getId())).isEmpty();
    assertThat(mapper.deletePending(p.getId(), inv.getId())).isZero(); // accepted -> not revocable
  }

  @Test
  void expiredInviteIsListedButDoesNotBlockAndCanBeRenewed() {
    AppUser owner = user("invMapOwner2");
    Project p = projects.create(owner.getId(), new CreateProjectRequest("Invitation mapper 2", null, null));
    ProjectInvitation inv = invitation(p.getId(), owner.getId(), "late@example.org", "tok-map-2",
        OffsetDateTime.now().minusDays(1));

    assertThat(mapper.findByToken("tok-map-2").isExpired()).isTrue();
    assertThat(mapper.hasActiveInvite(p.getId(), "late@example.org")).isFalse();
    assertThat(mapper.findPendingByProject(p.getId())).hasSize(1);

    mapper.updateToken(inv.getId(), "tok-map-3", OffsetDateTime.now().plusDays(30));
    assertThat(mapper.findByToken("tok-map-2")).isNull();
    assertThat(mapper.findByToken("tok-map-3").isExpired()).isFalse();

    assertThat(mapper.deletePending(p.getId(), inv.getId())).isEqualTo(1);
    assertThat(mapper.findById(inv.getId())).isNull();
  }
}
