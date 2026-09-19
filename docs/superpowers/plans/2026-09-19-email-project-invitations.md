# Project invitations by email — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a project owner invite someone who is not yet in Blixa by email (role + optional message); the invitee gets a standard email (owner CC'd / Reply-To), opens the link, signs in with ORCID, accepts, and is an ACTIVE member of the project.

**Architecture:** A new `project_invitation` table + `invite` backend package (mapper, service, two controllers, notifier). Owner endpoints under `/api/projects/{pid}/invitations`; an unauthenticated preview under `/api/public/invitations/{token}`; an authenticated accept under `/api/invitations/{token}/accept`, exempted from `ActiveUserFilter` so PENDING users can reach it. The SPA carries the token across the ORCID round-trip in `localStorage` (`blixa.pendingInvite`); `RequireAuth` resumes it before the PENDING gate.

**Tech Stack:** Spring Boot 4.1 / Java 25, MyBatis annotations, Flyway, Spring Mail (`SimpleMailMessage`), Testcontainers ITs + MockMvc; React 18 + TS, Mantine 7, TanStack Query, vitest + MSW.

**Spec:** `docs/superpowers/specs/2026-09-19-email-project-invitations-design.md`

## Global Constraints

- Work directly on `main`, one commit per task. End every commit message with `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`.
- Backend commands run from `backend/` after `sdk env` (JDK 25; `mvn` on Java 21 fails to compile).
- Unit tests: `mvn -o test -Dtest=<FULLY.QUALIFIED.ClassName>` — a simple class name matches nothing.
- ITs: `mvn -o test-compile failsafe:integration-test failsafe:verify -Dit.test=<FQN>`; Docker must be up (`orb start`; a "start VM: timed out" line is harmless — `docker info` confirms).
- Frontend commands run from `frontend/`. `npm run build` is the type-check gate (no ESLint). Single test: `npx vitest run src/<path>.test.tsx`.
- Token: 32 bytes from `SecureRandom`, base64url **without padding**. Validity: **30 days** (on create and on resend).
- Accept link: `${coldp.mail.base-url}/invite/<token>`. Mail config reused as-is (`coldp.mail.from`, `coldp.mail.base-url`); no new config keys.
- `localStorage` key: `blixa.pendingInvite`; every access wrapped in try/catch.
- Emails are sent **after** the service transaction commits — from the controller, same as `AdminUserController.setState`.
- Error contract: `ResponseStatusException(status, reason)` → `{"error": reason}` (see `web/ApiExceptionHandler`); `IllegalArgumentException` (e.g. `Role.fromDb("x")`) → 400.
- Email copy (exact):
  ```
  Subject: You're invited to join "<Project title>" on Blixa

  <Owner name> has invited you to join the project "<Project title>" on Blixa, the collaborative editor for taxonomic checklists, as <an editor|a viewer|an owner>.

  <Owner name> wrote:
  <personal message>                      ← whole block only when a message was given

  To accept, open the link below and sign in with your ORCID iD. If you don't have one yet, you can register for free at orcid.org during sign-in.

  <base-url>/invite/<token>

  This invitation expires on <d MMMM yyyy>. If you weren't expecting it, you can ignore this email.
  ```

## File map

**Backend** (`backend/src/main/java/org/catalogueoflife/editor/…`)
- Modify `notify/EmailService.java` — add `send(to, cc, replyTo, subject, text)`.
- Create `../resources/db/migration/V11__project_invitation.sql` — table.
- Create `invite/ProjectInvitation.java` — row model (+ joined `projectTitle`, `invitedByName`; `isExpired()`).
- Create `invite/InvitationMapper.java` — MyBatis SQL.
- Create `invite/InvitationNotifier.java` — builds + sends the invitation email; `acceptUrl(token)`.
- Create `invite/InvitationService.java` — owner ops (create/list/resend/revoke) + invitee ops (preview/accept).
- Create `invite/InvitationController.java` — owner endpoints.
- Create `invite/InvitationTokenController.java` — public preview + accept.
- Create `invite/dto/CreateInvitationRequest.java`, `invite/dto/InvitationResponse.java`, `invite/dto/InvitationPreview.java`, `invite/dto/AcceptInvitationResponse.java`.
- Modify `auth/ActiveUserFilter.java` — `/api/invitations/` prefix exemption.

**Backend tests** (`backend/src/test/java/org/catalogueoflife/editor/…`)
- Create `notify/EmailServiceTest.java`, `invite/InvitationNotifierTest.java` (unit).
- Create `invite/InvitationMapperIT.java`, `invite/InvitationApiIT.java`, `invite/InvitationAcceptIT.java`.

**Frontend** (`frontend/src/…`)
- Create `api/invitations.ts` — API wrappers + types (local types, like `api/join.ts`).
- Create `projects/roles.ts` — shared `ROLES` / `ROLE_DATA` / `roleWithArticle`.
- Create `projects/InviteMemberModal.tsx`, `projects/PendingInvitations.tsx`.
- Modify `projects/MembersPage.tsx` — invite button + modal + pending list.
- Create `invite/pendingInvite.ts` — guarded localStorage helpers.
- Create `invite/InviteAcceptPage.tsx` (+ `.test.tsx`).
- Modify `App.tsx` — `/invite/:token` route.
- Modify `auth/RequireAuth.tsx` (+ new `auth/RequireAuth.test.tsx`).
- Modify `test/server.ts` — default empty invitations list.
- Modify `projects/MembersPage.test.tsx`.

**Docs:** `backlog.md` (section "5. Global admin & user lifecycle").

---

### Task 1: EmailService — CC and Reply-To

**Files:**
- Modify: `backend/src/main/java/org/catalogueoflife/editor/notify/EmailService.java`
- Test: `backend/src/test/java/org/catalogueoflife/editor/notify/EmailServiceTest.java`

**Interfaces:**
- Produces: `EmailService.send(String to, String cc, String replyTo, String subject, String text)` — blank/null `cc`/`replyTo` are not set. Existing `send(to, subject, text)` unchanged in behaviour (delegates with nulls).

- [ ] **Step 1: Write the failing test**

```java
package org.catalogueoflife.editor.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class EmailServiceTest {

  @SuppressWarnings("unchecked")
  private static ObjectProvider<JavaMailSender> provider(JavaMailSender sender) {
    ObjectProvider<JavaMailSender> p = mock(ObjectProvider.class);
    when(p.getIfAvailable()).thenReturn(sender);
    return p;
  }

  private static SimpleMailMessage sent(JavaMailSender sender) {
    ArgumentCaptor<SimpleMailMessage> cap = ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(sender).send(cap.capture());
    return cap.getValue();
  }

  @Test
  void setsCcAndReplyToWhenGiven() {
    JavaMailSender sender = mock(JavaMailSender.class);
    new EmailService(provider(sender), "noreply@example.org")
        .send("to@example.org", "cc@example.org", "reply@example.org", "Subj", "Body");
    SimpleMailMessage m = sent(sender);
    assertThat(m.getFrom()).isEqualTo("noreply@example.org");
    assertThat(m.getTo()).containsExactly("to@example.org");
    assertThat(m.getCc()).containsExactly("cc@example.org");
    assertThat(m.getReplyTo()).isEqualTo("reply@example.org");
    assertThat(m.getSubject()).isEqualTo("Subj");
    assertThat(m.getText()).isEqualTo("Body");
  }

  @Test
  void omitsBlankCcAndReplyTo() {
    JavaMailSender sender = mock(JavaMailSender.class);
    new EmailService(provider(sender), "noreply@example.org")
        .send("to@example.org", " ", null, "Subj", "Body");
    SimpleMailMessage m = sent(sender);
    assertThat(m.getCc()).isNull();
    assertThat(m.getReplyTo()).isNull();
  }

  @Test
  void suppressedWhenFromIsUnset() {
    JavaMailSender sender = mock(JavaMailSender.class);
    new EmailService(provider(sender), "")
        .send("to@example.org", "cc@example.org", null, "Subj", "Body");
    verifyNoInteractions(sender);
  }
}
```

- [ ] **Step 2: Run it — expect a compile failure** (no 5-arg `send`)

Run: `cd backend && mvn -o test -Dtest=org.catalogueoflife.editor.notify.EmailServiceTest`
Expected: COMPILATION ERROR — `method send in class EmailService cannot be applied to given types`.

- [ ] **Step 3: Implement** — replace the body of `send` in `EmailService.java` with:

```java
  public void send(String to, String subject, String text) {
    send(to, null, null, subject, text);
  }

  // cc / replyTo are optional: null or blank -> the header is simply not set.
  public void send(String to, String cc, String replyTo, String subject, String text) {
    if (to == null || to.isBlank()) return;
    JavaMailSender sender = mailSender.getIfAvailable();
    if (sender == null || from == null || from.isBlank()) {
      log.info("email suppressed (mail not configured): to={} cc={} subject=\"{}\"", to, cc, subject);
      return;
    }
    try {
      SimpleMailMessage msg = new SimpleMailMessage();
      msg.setFrom(from);
      msg.setTo(to);
      if (cc != null && !cc.isBlank()) msg.setCc(cc);
      if (replyTo != null && !replyTo.isBlank()) msg.setReplyTo(replyTo);
      msg.setSubject(subject);
      msg.setText(text);
      sender.send(msg);
    } catch (Exception e) {
      log.warn("failed to send email to {}: {}", to, e.toString());
    }
  }
```

- [ ] **Step 4: Run the test — expect PASS**

Run: `cd backend && mvn -o test -Dtest=org.catalogueoflife.editor.notify.EmailServiceTest`
Expected: `Tests run: 3, Failures: 0, Errors: 0`.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/org/catalogueoflife/editor/notify/EmailService.java \
        backend/src/test/java/org/catalogueoflife/editor/notify/EmailServiceTest.java
git commit -m "feat(notify): EmailService.send with optional CC and Reply-To

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 2: `project_invitation` table, model and mapper

**Files:**
- Create: `backend/src/main/resources/db/migration/V11__project_invitation.sql`
- Create: `backend/src/main/java/org/catalogueoflife/editor/invite/ProjectInvitation.java`
- Create: `backend/src/main/java/org/catalogueoflife/editor/invite/InvitationMapper.java`
- Test: `backend/src/test/java/org/catalogueoflife/editor/invite/InvitationMapperIT.java`

**Interfaces:**
- Produces `ProjectInvitation` (getters/setters): `Integer id, projectId, invitedBy, acceptedBy; String email, role, message, token, projectTitle, invitedByName; OffsetDateTime createdAt, expiresAt, acceptedAt; boolean isExpired()`. `projectTitle` / `invitedByName` are read-only joined columns (filled by every `SELECT`, ignored by writes).
- Produces `InvitationMapper`:
  - `void insert(ProjectInvitation inv)` — sets `inv.id`
  - `ProjectInvitation findById(int id)`
  - `ProjectInvitation findByToken(String token)`
  - `List<ProjectInvitation> findPendingByProject(int projectId)` — `accepted_at IS NULL`, expired included, newest first
  - `boolean hasActiveInvite(int projectId, String email)` — pending **and** unexpired, email case-insensitive
  - `void updateToken(int id, String token, OffsetDateTime expiresAt)`
  - `int markAccepted(int id, int userId)` — only if not yet accepted; returns rows updated
  - `int deletePending(int projectId, int id)` — only if not yet accepted; returns rows deleted

- [ ] **Step 1: Write the migration**

`V11__project_invitation.sql`:
```sql
-- Owner-issued, emailed invitations to join a project. The token is the credential: whoever opens
-- the link and signs in may accept (single-use, expires_at bounds it). Kept after acceptance for the
-- record; revoking a pending invitation deletes the row.
CREATE TABLE project_invitation (
  id          integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  project_id  bigint NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  email       text NOT NULL,
  role        text NOT NULL CHECK (role IN ('owner', 'editor', 'viewer')),
  message     text,
  token       text NOT NULL UNIQUE,
  invited_by  bigint REFERENCES app_user(id) ON DELETE SET NULL,
  created_at  timestamptz NOT NULL DEFAULT now(),
  expires_at  timestamptz NOT NULL,
  accepted_at timestamptz,
  accepted_by bigint REFERENCES app_user(id) ON DELETE SET NULL
);

CREATE INDEX project_invitation_project_idx ON project_invitation (project_id);
```

- [ ] **Step 2: Write the model**

`invite/ProjectInvitation.java`:
```java
package org.catalogueoflife.editor.invite;

import java.time.OffsetDateTime;

public class ProjectInvitation {
  private Integer id;
  private Integer projectId;
  private String email;
  private String role;
  private String message;
  private String token;
  private Integer invitedBy;
  private OffsetDateTime createdAt;
  private OffsetDateTime expiresAt;
  private OffsetDateTime acceptedAt;
  private Integer acceptedBy;
  // Read-only, joined in by InvitationMapper's SELECTs (project.title; inviter display name or username).
  private String projectTitle;
  private String invitedByName;

  public boolean isExpired() {
    return expiresAt != null && expiresAt.isBefore(OffsetDateTime.now());
  }

  public Integer getId() { return id; }
  public void setId(Integer id) { this.id = id; }
  public Integer getProjectId() { return projectId; }
  public void setProjectId(Integer projectId) { this.projectId = projectId; }
  public String getEmail() { return email; }
  public void setEmail(String email) { this.email = email; }
  public String getRole() { return role; }
  public void setRole(String role) { this.role = role; }
  public String getMessage() { return message; }
  public void setMessage(String message) { this.message = message; }
  public String getToken() { return token; }
  public void setToken(String token) { this.token = token; }
  public Integer getInvitedBy() { return invitedBy; }
  public void setInvitedBy(Integer invitedBy) { this.invitedBy = invitedBy; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
  public OffsetDateTime getExpiresAt() { return expiresAt; }
  public void setExpiresAt(OffsetDateTime expiresAt) { this.expiresAt = expiresAt; }
  public OffsetDateTime getAcceptedAt() { return acceptedAt; }
  public void setAcceptedAt(OffsetDateTime acceptedAt) { this.acceptedAt = acceptedAt; }
  public Integer getAcceptedBy() { return acceptedBy; }
  public void setAcceptedBy(Integer acceptedBy) { this.acceptedBy = acceptedBy; }
  public String getProjectTitle() { return projectTitle; }
  public void setProjectTitle(String projectTitle) { this.projectTitle = projectTitle; }
  public String getInvitedByName() { return invitedByName; }
  public void setInvitedByName(String invitedByName) { this.invitedByName = invitedByName; }
}
```

- [ ] **Step 3: Write the mapper**

`invite/InvitationMapper.java`:
```java
package org.catalogueoflife.editor.invite;

import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface InvitationMapper {

  // Every read joins the project title and the inviter's display name (falling back to username),
  // which the owner list, the public preview and the invitation email all need.
  String SELECT = """
      SELECT i.*, p.title AS project_title,
             COALESCE(NULLIF(u.display_name, ''), u.username) AS invited_by_name
      FROM project_invitation i
      JOIN project p ON p.id = i.project_id
      LEFT JOIN app_user u ON u.id = i.invited_by
      """;

  @Insert("""
      INSERT INTO project_invitation (project_id, email, role, message, token, invited_by, expires_at)
      VALUES (#{projectId}, #{email}, #{role}, #{message}, #{token}, #{invitedBy}, #{expiresAt})
      """)
  @Options(useGeneratedKeys = true, keyProperty = "id")
  void insert(ProjectInvitation inv);

  @Select(SELECT + "WHERE i.id = #{id}")
  ProjectInvitation findById(int id);

  @Select(SELECT + "WHERE i.token = #{token}")
  ProjectInvitation findByToken(String token);

  // Pending = not yet accepted. Expired ones are included (the owner UI flags them) until resent or
  // revoked.
  @Select(SELECT + "WHERE i.project_id = #{projectId} AND i.accepted_at IS NULL ORDER BY i.created_at DESC, i.id DESC")
  List<ProjectInvitation> findPendingByProject(int projectId);

  // A live (pending + unexpired) invitation for this address already exists -> the owner should
  // resend it rather than create a second one. An expired one doesn't block a fresh invite.
  @Select("""
      SELECT EXISTS (
        SELECT 1 FROM project_invitation
        WHERE project_id = #{projectId} AND lower(email) = lower(#{email})
          AND accepted_at IS NULL AND expires_at > now())
      """)
  boolean hasActiveInvite(@Param("projectId") int projectId, @Param("email") String email);

  @Update("UPDATE project_invitation SET token = #{token}, expires_at = #{expiresAt} WHERE id = #{id}")
  void updateToken(@Param("id") int id, @Param("token") String token,
      @Param("expiresAt") OffsetDateTime expiresAt);

  // Single-use: the accepted_at guard makes a concurrent second accept update 0 rows.
  @Update("""
      UPDATE project_invitation SET accepted_at = now(), accepted_by = #{userId}
      WHERE id = #{id} AND accepted_at IS NULL
      """)
  int markAccepted(@Param("id") int id, @Param("userId") int userId);

  @Delete("DELETE FROM project_invitation WHERE project_id = #{projectId} AND id = #{id} AND accepted_at IS NULL")
  int deletePending(@Param("projectId") int projectId, @Param("id") int id);
}
```

- [ ] **Step 4: Write the IT**

`invite/InvitationMapperIT.java`:
```java
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
```

- [ ] **Step 5: Run the IT — expect PASS** (it's written after the mapper; the migration + SQL are what's under test)

Run: `cd backend && mvn -o test-compile failsafe:integration-test failsafe:verify -Dit.test=org.catalogueoflife.editor.invite.InvitationMapperIT`
Expected: `Tests run: 2, Failures: 0, Errors: 0`, BUILD SUCCESS. If Flyway reports a checksum/ordering error, confirm the file is named exactly `V11__project_invitation.sql`.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/resources/db/migration/V11__project_invitation.sql \
        backend/src/main/java/org/catalogueoflife/editor/invite/ProjectInvitation.java \
        backend/src/main/java/org/catalogueoflife/editor/invite/InvitationMapper.java \
        backend/src/test/java/org/catalogueoflife/editor/invite/InvitationMapperIT.java
git commit -m "feat(invite): project_invitation table, model and mapper

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 3: InvitationNotifier — the invitation email

**Files:**
- Create: `backend/src/main/java/org/catalogueoflife/editor/invite/InvitationNotifier.java`
- Test: `backend/src/test/java/org/catalogueoflife/editor/invite/InvitationNotifierTest.java`

**Interfaces:**
- Consumes: `EmailService.send(to, cc, replyTo, subject, text)` (Task 1); `ProjectInvitation` getters incl. `getProjectTitle()` (Task 2); `AppUser.getDisplayName()/getUsername()/getEmail()`.
- Produces:
  - `String acceptUrl(String token)` → `baseUrl + "/invite/" + token`
  - `void sendInvitation(ProjectInvitation inv, AppUser inviter)` — best-effort, never throws; CC + Reply-To = `inviter.getEmail()` when present
  - `static String withArticle(String role)` → `"an owner" | "an editor" | "a viewer"`

- [ ] **Step 1: Write the failing test**

```java
package org.catalogueoflife.editor.invite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.catalogueoflife.editor.notify.EmailService;
import org.catalogueoflife.editor.user.AppUser;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class InvitationNotifierTest {

  private final EmailService email = mock(EmailService.class);
  private final InvitationNotifier notifier = new InvitationNotifier(email, "https://blixa.example.org");

  private static ProjectInvitation inv(String role, String message) {
    ProjectInvitation inv = new ProjectInvitation();
    inv.setEmail("invitee@example.org");
    inv.setRole(role);
    inv.setMessage(message);
    inv.setToken("tok123");
    inv.setProjectTitle("Beetles of Europe");
    inv.setExpiresAt(OffsetDateTime.of(2026, 10, 19, 12, 0, 0, 0, ZoneOffset.UTC));
    return inv;
  }

  private static AppUser owner(String mail) {
    AppUser u = new AppUser();
    u.setUsername("olga");
    u.setDisplayName("Olga Owner");
    u.setEmail(mail);
    return u;
  }

  @Test
  void sendsToInviteeWithOwnerAsCcAndReplyTo() {
    notifier.sendInvitation(inv("editor", null), owner("olga@example.org"));
    ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
    verify(email).send(eq("invitee@example.org"), eq("olga@example.org"), eq("olga@example.org"),
        eq("You're invited to join \"Beetles of Europe\" on Blixa"), body.capture());
    assertThat(body.getValue())
        .contains("Olga Owner has invited you to join the project \"Beetles of Europe\" on Blixa")
        .contains("as an editor.")
        .contains("sign in with your ORCID iD")
        .contains("https://blixa.example.org/invite/tok123")
        .contains("This invitation expires on 19 October 2026.")
        .doesNotContain("wrote:");
  }

  @Test
  void includesPersonalMessageAndFallsBackToUsernameWithoutCc() {
    AppUser inviter = owner(null);
    inviter.setDisplayName(" ");
    notifier.sendInvitation(inv("viewer", "Please help with the weevils."), inviter);
    ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
    verify(email).send(eq("invitee@example.org"), isNull(), isNull(),
        eq("You're invited to join \"Beetles of Europe\" on Blixa"), body.capture());
    assertThat(body.getValue())
        .startsWith("olga has invited you")
        .contains("as a viewer.")
        .contains("olga wrote:\nPlease help with the weevils.\n\n");
  }

  @Test
  void articles() {
    assertThat(InvitationNotifier.withArticle("owner")).isEqualTo("an owner");
    assertThat(InvitationNotifier.withArticle("editor")).isEqualTo("an editor");
    assertThat(InvitationNotifier.withArticle("viewer")).isEqualTo("a viewer");
  }

  @Test
  void acceptUrl() {
    assertThat(notifier.acceptUrl("abc")).isEqualTo("https://blixa.example.org/invite/abc");
  }
}
```

- [ ] **Step 2: Run it — expect a compile failure** (`InvitationNotifier` missing)

Run: `cd backend && mvn -o test -Dtest=org.catalogueoflife.editor.invite.InvitationNotifierTest`
Expected: COMPILATION ERROR — `cannot find symbol: class InvitationNotifier`.

- [ ] **Step 3: Implement**

`invite/InvitationNotifier.java`:
```java
package org.catalogueoflife.editor.invite;

import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.catalogueoflife.editor.notify.EmailService;
import org.catalogueoflife.editor.user.AppUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

// The project-invitation email. Sent To the invitee, with the inviting owner on CC and as Reply-To
// (so an invitee's reply reaches a person, not the no-reply sender). Best-effort: never throws.
@Service
public class InvitationNotifier {

  private static final Logger log = LoggerFactory.getLogger(InvitationNotifier.class);
  private static final DateTimeFormatter EXPIRY = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

  private final EmailService email;
  private final String baseUrl;

  public InvitationNotifier(EmailService email, @Value("${coldp.mail.base-url:}") String baseUrl) {
    this.email = email;
    this.baseUrl = baseUrl;
  }

  public String acceptUrl(String token) {
    return baseUrl + "/invite/" + token;
  }

  public void sendInvitation(ProjectInvitation inv, AppUser inviter) {
    try {
      String name = inviterName(inviter);
      String title = inv.getProjectTitle();
      StringBuilder body = new StringBuilder()
          .append(name).append(" has invited you to join the project \"").append(title)
          .append("\" on Blixa, the collaborative editor for taxonomic checklists, as ")
          .append(withArticle(inv.getRole())).append(".\n\n");
      if (inv.getMessage() != null && !inv.getMessage().isBlank()) {
        body.append(name).append(" wrote:\n").append(inv.getMessage()).append("\n\n");
      }
      body.append("To accept, open the link below and sign in with your ORCID iD. If you don't have "
              + "one yet, you can register for free at orcid.org during sign-in.\n\n")
          .append(acceptUrl(inv.getToken())).append("\n\n")
          .append("This invitation expires on ").append(EXPIRY.format(inv.getExpiresAt()))
          .append(". If you weren't expecting it, you can ignore this email.\n");
      String cc = inviter == null ? null : inviter.getEmail();
      email.send(inv.getEmail(), cc, cc, "You're invited to join \"" + title + "\" on Blixa",
          body.toString());
    } catch (Exception e) {
      log.warn("failed to send invitation email to {}: {}", inv.getEmail(), e.toString());
    }
  }

  static String withArticle(String role) {
    return ("owner".equals(role) || "editor".equals(role) ? "an " : "a ") + role;
  }

  private static String inviterName(AppUser u) {
    if (u == null) return "A Blixa user";
    return u.getDisplayName() == null || u.getDisplayName().isBlank() ? u.getUsername() : u.getDisplayName();
  }
}
```

- [ ] **Step 4: Run the test — expect PASS**

Run: `cd backend && mvn -o test -Dtest=org.catalogueoflife.editor.invite.InvitationNotifierTest`
Expected: `Tests run: 4, Failures: 0, Errors: 0`.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/org/catalogueoflife/editor/invite/InvitationNotifier.java \
        backend/src/test/java/org/catalogueoflife/editor/invite/InvitationNotifierTest.java
git commit -m "feat(invite): invitation email with owner on CC and Reply-To

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 4: Owner endpoints — create, list, resend, revoke

**Files:**
- Create: `backend/src/main/java/org/catalogueoflife/editor/invite/InvitationService.java`
- Create: `backend/src/main/java/org/catalogueoflife/editor/invite/InvitationController.java`
- Create: `backend/src/main/java/org/catalogueoflife/editor/invite/dto/CreateInvitationRequest.java`
- Create: `backend/src/main/java/org/catalogueoflife/editor/invite/dto/InvitationResponse.java`
- Test: `backend/src/test/java/org/catalogueoflife/editor/invite/InvitationApiIT.java`

**Interfaces:**
- Consumes: `InvitationMapper` (Task 2); `InvitationNotifier.sendInvitation(inv, inviter)` / `acceptUrl(token)` (Task 3); `ProjectService.requireOwner(int actorId, int projectId)`; `Role.fromDb(String)`; `CurrentUser.require()`.
- Produces:
  - `InvitationService.create(int actorId, int projectId, CreateInvitationRequest req) → ProjectInvitation` (`@Transactional`, does **not** email)
  - `InvitationService.listPending(int actorId, int projectId) → List<ProjectInvitation>`
  - `InvitationService.resend(int actorId, int projectId, int id) → ProjectInvitation` (`@Transactional`, does **not** email)
  - `InvitationService.revoke(int actorId, int projectId, int id)`
  - `record CreateInvitationRequest(String email, String role, String message)`
  - `record InvitationResponse(int id, String email, String role, String message, String invitedBy, OffsetDateTime createdAt, OffsetDateTime expiresAt, boolean expired, String acceptUrl)` with `static of(ProjectInvitation, String acceptUrl)`
  - HTTP: `POST/GET /api/projects/{pid}/invitations`, `POST /api/projects/{pid}/invitations/{id}/resend`, `DELETE /api/projects/{pid}/invitations/{id}` (204)

- [ ] **Step 1: Write the failing IT**

`invite/InvitationApiIT.java`:
```java
package org.catalogueoflife.editor.invite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.catalogueoflife.editor.notify.EmailService;
import org.catalogueoflife.editor.project.ProjectMember;
import org.catalogueoflife.editor.project.ProjectMemberMapper;
import org.catalogueoflife.editor.project.Role;
import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserMapper;
import org.catalogueoflife.editor.user.AppUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@AutoConfigureMockMvc
class InvitationApiIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired AppUserMapper userMapper;
  @Autowired ProjectMemberMapper members;
  @Autowired ObjectMapper json;
  @MockitoBean EmailService email;

  private AppUser user(String username, String mail) {
    if (users.requireByUsernameOrNull(username) == null) users.createLocal(username, "pw", username);
    AppUser u = users.requireByUsernameOrNull(username);
    u.setEmail(mail);
    userMapper.update(u);
    return u;
  }

  private int project(String owner, String title) throws Exception {
    String b = mvc.perform(post("/api/projects").with(csrf()).with(user(owner))
            .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"" + title + "\"}"))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    return json.readTree(b).get("id").asInt();
  }

  private String invite(int pid, String owner, String body) throws Exception {
    return mvc.perform(post("/api/projects/" + pid + "/invitations").with(csrf()).with(user(owner))
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andReturn().getResponse().getContentAsString();
  }

  @Test
  void ownerInvitesListsResendsAndRevokes() throws Exception {
    user("invApiOwner", "owner@example.org");
    int pid = project("invApiOwner", "Invitations IT");

    // create -> 201, emailed To invitee with the owner on CC + Reply-To
    String created = mvc.perform(post("/api/projects/" + pid + "/invitations").with(csrf())
            .with(user("invApiOwner")).contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\" New.Person@example.org \",\"role\":\"editor\",\"message\":\"Welcome!\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.email").value("New.Person@example.org"))
        .andExpect(jsonPath("$.role").value("editor"))
        .andExpect(jsonPath("$.message").value("Welcome!"))
        .andExpect(jsonPath("$.invitedBy").value("invApiOwner"))
        .andExpect(jsonPath("$.expired").value(false))
        .andReturn().getResponse().getContentAsString();
    int id = json.readTree(created).get("id").asInt();
    String firstUrl = json.readTree(created).get("acceptUrl").asText();
    assertThat(firstUrl).contains("/invite/");
    verify(email).send(eq("New.Person@example.org"), eq("owner@example.org"), eq("owner@example.org"),
        contains("Invitations IT"), contains(firstUrl));

    // a second live invite for the same address (any case) -> 409
    mvc.perform(post("/api/projects/" + pid + "/invitations").with(csrf()).with(user("invApiOwner"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"new.person@EXAMPLE.org\",\"role\":\"viewer\"}"))
       .andExpect(status().isConflict());

    // validation -> 400
    mvc.perform(post("/api/projects/" + pid + "/invitations").with(csrf()).with(user("invApiOwner"))
            .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"not-an-email\",\"role\":\"editor\"}"))
       .andExpect(status().isBadRequest());
    mvc.perform(post("/api/projects/" + pid + "/invitations").with(csrf()).with(user("invApiOwner"))
            .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"x@example.org\",\"role\":\"boss\"}"))
       .andExpect(status().isBadRequest());

    // list
    mvc.perform(get("/api/projects/" + pid + "/invitations").with(user("invApiOwner")))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.length()").value(1))
       .andExpect(jsonPath("$[0].id").value(id));

    // resend -> new link, emailed again
    String resent = mvc.perform(post("/api/projects/" + pid + "/invitations/" + id + "/resend")
            .with(csrf()).with(user("invApiOwner")))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    String secondUrl = json.readTree(resent).get("acceptUrl").asText();
    assertThat(secondUrl).isNotEqualTo(firstUrl);
    verify(email, times(2)).send(eq("New.Person@example.org"), anyString(), anyString(),
        anyString(), anyString());

    // revoke -> 204, gone; revoking again -> 404
    mvc.perform(delete("/api/projects/" + pid + "/invitations/" + id).with(csrf()).with(user("invApiOwner")))
       .andExpect(status().isNoContent());
    mvc.perform(get("/api/projects/" + pid + "/invitations").with(user("invApiOwner")))
       .andExpect(jsonPath("$.length()").value(0));
    mvc.perform(delete("/api/projects/" + pid + "/invitations/" + id).with(csrf()).with(user("invApiOwner")))
       .andExpect(status().isNotFound());
  }

  @Test
  void onlyOwnersManageInvitations() throws Exception {
    user("invApiOwner2", null);
    int pid = project("invApiOwner2", "Invitations IT 2");
    AppUser editor = user("invApiEditor", null);
    members.upsert(new ProjectMember(pid, editor.getId(), Role.EDITOR.dbValue()));
    user("invApiStranger", null);

    mvc.perform(get("/api/projects/" + pid + "/invitations").with(user("invApiEditor")))
       .andExpect(status().isForbidden());
    assertThat(invite(pid, "invApiEditor", "{\"email\":\"a@example.org\",\"role\":\"editor\"}"))
        .contains("owner required");
    mvc.perform(get("/api/projects/" + pid + "/invitations").with(user("invApiStranger")))
       .andExpect(status().isNotFound());
  }
}
```

- [ ] **Step 2: Run it — expect FAIL** (the IT only talks HTTP, so it compiles; the endpoints don't exist yet)

Run: `cd backend && mvn -o test-compile failsafe:integration-test failsafe:verify -Dit.test=org.catalogueoflife.editor.invite.InvitationApiIT`
Expected: FAIL — `Status expected:<201> but was:<404>` (no controller yet).

- [ ] **Step 3: Write the DTOs**

`invite/dto/CreateInvitationRequest.java`:
```java
package org.catalogueoflife.editor.invite.dto;

// role defaults to editor when omitted; message is optional (blank -> none).
public record CreateInvitationRequest(String email, String role, String message) {}
```

`invite/dto/InvitationResponse.java`:
```java
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
```

- [ ] **Step 4: Write the service (owner half)**

`invite/InvitationService.java`:
```java
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
```

Note: `inv.getProjectId() != projectId` compares `Integer` with `int` — auto-unboxing makes it a value comparison.

- [ ] **Step 5: Write the controller**

`invite/InvitationController.java`:
```java
package org.catalogueoflife.editor.invite;

import java.util.List;
import org.catalogueoflife.editor.auth.CurrentUser;
import org.catalogueoflife.editor.invite.dto.CreateInvitationRequest;
import org.catalogueoflife.editor.invite.dto.InvitationResponse;
import org.catalogueoflife.editor.user.AppUser;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// Owner-only management of a project's email invitations (all gated via ProjectService.requireOwner:
// non-owner member 403, non-member 404). The email goes out here, after the service's transaction
// has committed -- same pattern as AdminUserController.setState.
@RestController
@RequestMapping("/api/projects/{pid}/invitations")
public class InvitationController {

  private final InvitationService service;
  private final InvitationNotifier notifier;
  private final CurrentUser currentUser;

  public InvitationController(InvitationService service, InvitationNotifier notifier, CurrentUser currentUser) {
    this.service = service;
    this.notifier = notifier;
    this.currentUser = currentUser;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public InvitationResponse create(@PathVariable int pid, @RequestBody CreateInvitationRequest req) {
    AppUser me = currentUser.require();
    ProjectInvitation inv = service.create(me.getId(), pid, req);
    notifier.sendInvitation(inv, me);
    return toResponse(inv);
  }

  @GetMapping
  public List<InvitationResponse> list(@PathVariable int pid) {
    return service.listPending(currentUser.require().getId(), pid).stream().map(this::toResponse).toList();
  }

  @PostMapping("/{id}/resend")
  public InvitationResponse resend(@PathVariable int pid, @PathVariable int id) {
    AppUser me = currentUser.require();
    ProjectInvitation inv = service.resend(me.getId(), pid, id);
    notifier.sendInvitation(inv, me);
    return toResponse(inv);
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void revoke(@PathVariable int pid, @PathVariable int id) {
    service.revoke(currentUser.require().getId(), pid, id);
  }

  private InvitationResponse toResponse(ProjectInvitation inv) {
    return InvitationResponse.of(inv, notifier.acceptUrl(inv.getToken()));
  }
}
```

- [ ] **Step 6: Run the IT — expect PASS**

Run: `cd backend && mvn -o test-compile failsafe:integration-test failsafe:verify -Dit.test=org.catalogueoflife.editor.invite.InvitationApiIT`
Expected: `Tests run: 2, Failures: 0, Errors: 0`.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/org/catalogueoflife/editor/invite/ \
        backend/src/test/java/org/catalogueoflife/editor/invite/InvitationApiIT.java
git commit -m "feat(invite): owner endpoints to create, list, resend and revoke invitations

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 5: Public preview + accept (PENDING-safe)

**Files:**
- Modify: `backend/src/main/java/org/catalogueoflife/editor/invite/InvitationService.java` (add `preview`, `accept`)
- Create: `backend/src/main/java/org/catalogueoflife/editor/invite/InvitationTokenController.java`
- Create: `backend/src/main/java/org/catalogueoflife/editor/invite/dto/InvitationPreview.java`
- Create: `backend/src/main/java/org/catalogueoflife/editor/invite/dto/AcceptInvitationResponse.java`
- Modify: `backend/src/main/java/org/catalogueoflife/editor/auth/ActiveUserFilter.java`
- Test: `backend/src/test/java/org/catalogueoflife/editor/invite/InvitationAcceptIT.java`

**Interfaces:**
- Consumes: `InvitationMapper.findByToken/markAccepted` (Task 2); `AppUserMapper.findById/update`; `ProjectMemberMapper.findRole(int projectId, int userId)` / `upsert(ProjectMember)`; `UserState`.
- Produces:
  - `record InvitationPreview(String projectTitle, String invitedBy, String role, String message, String status)` — status `VALID | EXPIRED | ACCEPTED`
  - `record AcceptInvitationResponse(int projectId)`
  - `InvitationService.preview(String token) → InvitationPreview` (404 unknown)
  - `InvitationService.accept(int userId, String token) → int projectId` (`@Transactional`; 404 unknown, 410 expired/used, 403 disabled)
  - HTTP: `GET /api/public/invitations/{token}` (no auth), `POST /api/invitations/{token}/accept` (auth, CSRF)

- [ ] **Step 1: Write the failing IT**

`invite/InvitationAcceptIT.java`:
```java
package org.catalogueoflife.editor.invite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import org.catalogueoflife.editor.notify.EmailService;
import org.catalogueoflife.editor.project.ProjectMemberMapper;
import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserMapper;
import org.catalogueoflife.editor.user.AppUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@AutoConfigureMockMvc
class InvitationAcceptIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired AppUserMapper userMapper;
  @Autowired ProjectMemberMapper members;
  @Autowired InvitationMapper invitations;
  @Autowired ObjectMapper json;
  @MockitoBean EmailService email;

  private void ensureUser(String username) {
    if (users.requireByUsernameOrNull(username) == null) users.createLocal(username, "pw", username);
  }

  private int project(String owner, String title) throws Exception {
    String b = mvc.perform(post("/api/projects").with(csrf()).with(user(owner))
            .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"" + title + "\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return json.readTree(b).get("id").asInt();
  }

  // Creates an invitation as `owner` and returns its token (the last path segment of acceptUrl).
  private String inviteToken(int pid, String owner, String mail, String role) throws Exception {
    String b = mvc.perform(post("/api/projects/" + pid + "/invitations").with(csrf()).with(user(owner))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + mail + "\",\"role\":\"" + role + "\",\"message\":\"Join us\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    String url = json.readTree(b).get("acceptUrl").asText();
    return url.substring(url.lastIndexOf('/') + 1);
  }

  @Test
  void pendingOrcidUserAcceptsAndBecomesActiveMember() throws Exception {
    ensureUser("accOwner");
    int pid = project("accOwner", "Accept IT");
    String token = inviteToken(pid, "accOwner", "ina@example.org", "editor");

    // unauthenticated preview
    mvc.perform(get("/api/public/invitations/" + token))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.projectTitle").value("Accept IT"))
       .andExpect(jsonPath("$.invitedBy").value("accOwner"))
       .andExpect(jsonPath("$.role").value("editor"))
       .andExpect(jsonPath("$.message").value("Join us"))
       .andExpect(jsonPath("$.status").value("VALID"));
    mvc.perform(get("/api/public/invitations/no-such-token")).andExpect(status().isNotFound());

    // a brand-new ORCID sign-up is PENDING with no email; accept must get past ActiveUserFilter
    String orcid = "0000-0002-9999-0201";
    users.upsertFromOrcid(orcid, "Ina Invitee", "Ina", "Invitee");
    mvc.perform(post("/api/invitations/" + token + "/accept").with(csrf()).with(user(orcid)))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.projectId").value(pid));

    AppUser ina = userMapper.findByOrcid(orcid);
    assertThat(ina.getState()).isEqualTo("ACTIVE");
    assertThat(ina.getEmail()).isEqualTo("ina@example.org");
    assertThat(members.findRole(pid, ina.getId())).isEqualTo("editor");

    // single use
    mvc.perform(get("/api/public/invitations/" + token)).andExpect(jsonPath("$.status").value("ACCEPTED"));
    mvc.perform(post("/api/invitations/" + token + "/accept").with(csrf()).with(user(orcid)))
       .andExpect(status().isGone());
  }

  @Test
  void existingMemberKeepsRoleAndExistingEmailIsKept() throws Exception {
    ensureUser("accOwner2");
    AppUser owner = users.requireByUsernameOrNull("accOwner2");
    owner.setEmail("owner2@example.org");
    userMapper.update(owner);
    int pid = project("accOwner2", "Accept IT 2");
    String token = inviteToken(pid, "accOwner2", "someone@example.org", "viewer");

    // the owner opens their own link: consumed, but never downgraded, and their email untouched
    mvc.perform(post("/api/invitations/" + token + "/accept").with(csrf()).with(user("accOwner2")))
       .andExpect(status().isOk());
    assertThat(members.findRole(pid, owner.getId())).isEqualTo("owner");
    assertThat(userMapper.findById(owner.getId()).getEmail()).isEqualTo("owner2@example.org");
  }

  @Test
  void expiredAndDisabledAreRejected() throws Exception {
    ensureUser("accOwner3");
    int pid = project("accOwner3", "Accept IT 3");

    String expired = inviteToken(pid, "accOwner3", "late@example.org", "editor");
    invitations.updateToken(invitations.findByToken(expired).getId(), expired, OffsetDateTime.now().minusDays(1));
    mvc.perform(get("/api/public/invitations/" + expired)).andExpect(jsonPath("$.status").value("EXPIRED"));
    ensureUser("accLate");
    mvc.perform(post("/api/invitations/" + expired + "/accept").with(csrf()).with(user("accLate")))
       .andExpect(status().isGone());

    String token = inviteToken(pid, "accOwner3", "gone@example.org", "editor");
    ensureUser("accDisabled");
    AppUser disabled = users.requireByUsernameOrNull("accDisabled");
    disabled.setState("DISABLED");
    userMapper.update(disabled);
    mvc.perform(post("/api/invitations/" + token + "/accept").with(csrf()).with(user("accDisabled")))
       .andExpect(status().isForbidden());
    assertThat(members.findRole(pid, disabled.getId())).isNull();
    // still usable by someone else -- the rejected attempt didn't consume it
    mvc.perform(get("/api/public/invitations/" + token)).andExpect(jsonPath("$.status").value("VALID"));
  }
}
```

- [ ] **Step 2: Run it — expect FAIL**

Run: `cd backend && mvn -o test-compile failsafe:integration-test failsafe:verify -Dit.test=org.catalogueoflife.editor.invite.InvitationAcceptIT`
Expected: FAIL — preview `Status expected:<200> but was:<404>` (no endpoint yet).

- [ ] **Step 3: Write the DTOs**

`invite/dto/InvitationPreview.java`:
```java
package org.catalogueoflife.editor.invite.dto;

// What an invitee sees before signing in. status: VALID | EXPIRED | ACCEPTED.
public record InvitationPreview(String projectTitle, String invitedBy, String role, String message,
    String status) {}
```

`invite/dto/AcceptInvitationResponse.java`:
```java
package org.catalogueoflife.editor.invite.dto;

public record AcceptInvitationResponse(int projectId) {}
```

- [ ] **Step 4: Add `preview` and `accept` to `InvitationService`**

Add these imports:
```java
import org.catalogueoflife.editor.invite.dto.InvitationPreview;
import org.catalogueoflife.editor.project.ProjectMember;
import org.catalogueoflife.editor.project.ProjectMemberMapper;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserMapper;
import org.catalogueoflife.editor.user.UserState;
```
replace the fields + constructor with:
```java
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
```
and add these methods:
```java
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
```

- [ ] **Step 5: Write the token controller**

`invite/InvitationTokenController.java`:
```java
package org.catalogueoflife.editor.invite;

import org.catalogueoflife.editor.auth.CurrentUser;
import org.catalogueoflife.editor.invite.dto.AcceptInvitationResponse;
import org.catalogueoflife.editor.invite.dto.InvitationPreview;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

// The invitee's side, addressed by the emailed token. The preview is public (/api/public/**:
// permitAll + CSRF-exempt) so the accept page can show what the link is for before sign-in. Accept
// needs a login; ActiveUserFilter exempts /api/invitations/ so a brand-new PENDING account can call it.
@RestController
public class InvitationTokenController {

  private final InvitationService service;
  private final CurrentUser currentUser;

  public InvitationTokenController(InvitationService service, CurrentUser currentUser) {
    this.service = service;
    this.currentUser = currentUser;
  }

  @GetMapping("/api/public/invitations/{token}")
  public InvitationPreview preview(@PathVariable String token) {
    return service.preview(token);
  }

  @PostMapping("/api/invitations/{token}/accept")
  public AcceptInvitationResponse accept(@PathVariable String token) {
    return new AcceptInvitationResponse(service.accept(currentUser.require().getId(), token));
  }
}
```

- [ ] **Step 6: Exempt the accept path in `ActiveUserFilter`**

In `auth/ActiveUserFilter.java`, change the `gated` expression and the class comment:
```java
// Blocks authenticated but non-ACTIVE accounts (PENDING/DISABLED) from the protected API with a 403,
// EXCEPT /api/me, /api/me/application, logout and /api/invitations/** -- so a pending user can still
// load the SPA, see the "awaiting admin approval" screen, submit their access request, accept a
// project invitation (which activates them; InvitationService rejects DISABLED accounts itself), and
// log out. The permitAll surface (public/auth/ping/config) is skipped.
```
```java
    boolean gated = path.startsWith("/api/") && !ALLOW.contains(path)
        && !path.startsWith("/api/public/") && !path.startsWith("/api/auth/")
        && !path.startsWith("/api/invitations/");
```

- [ ] **Step 7: Run the accept IT and the owner IT — expect PASS**

Run: `cd backend && mvn -o test-compile failsafe:integration-test failsafe:verify -Dit.test='org.catalogueoflife.editor.invite.*IT'`
Expected: `InvitationMapperIT`, `InvitationApiIT`, `InvitationAcceptIT` all pass (7 tests).

- [ ] **Step 8: Re-run the existing gate + lifecycle ITs** (the filter changed)

Run: `cd backend && mvn -o test-compile failsafe:integration-test failsafe:verify -Dit.test='org.catalogueoflife.editor.auth.*IT,org.catalogueoflife.editor.admin.*IT,org.catalogueoflife.editor.notify.*IT'`
Expected: all pass.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/org/catalogueoflife/editor/invite/ \
        backend/src/main/java/org/catalogueoflife/editor/auth/ActiveUserFilter.java \
        backend/src/test/java/org/catalogueoflife/editor/invite/InvitationAcceptIT.java
git commit -m "feat(invite): public invitation preview and accept (activates pending accounts)

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 6: Frontend — invite modal + pending invitations on the Members page

**Files:**
- Create: `frontend/src/api/invitations.ts`
- Create: `frontend/src/projects/roles.ts`
- Create: `frontend/src/projects/InviteMemberModal.tsx`
- Create: `frontend/src/projects/PendingInvitations.tsx`
- Modify: `frontend/src/projects/MembersPage.tsx`
- Modify: `frontend/src/test/server.ts`
- Test: `frontend/src/projects/MembersPage.test.tsx`

**Interfaces:**
- Consumes: HTTP from Tasks 4–5.
- Produces (`api/invitations.ts`):
  ```ts
  export interface Invitation { id: number; email: string; role: Role; message: string | null;
    invitedBy: string | null; createdAt: string; expiresAt: string; expired: boolean; acceptUrl: string }
  export interface InvitationPreview { projectTitle: string; invitedBy: string | null; role: Role;
    message: string | null; status: 'VALID' | 'EXPIRED' | 'ACCEPTED' }
  export interface InvitationBody { email: string; role: Role; message?: string }
  listInvitations(pid: number): Promise<Invitation[]>
  createInvitation(pid: number, body: InvitationBody): Promise<Invitation>
  resendInvitation(pid: number, id: number): Promise<Invitation>
  revokeInvitation(pid: number, id: number): Promise<void>
  getInvitationPreview(token: string): Promise<InvitationPreview>
  acceptInvitation(token: string): Promise<{ projectId: number }>
  ```
- Produces (`projects/roles.ts`): `ROLES: Role[]`, `ROLE_DATA: {value,label}[]`, `roleWithArticle(role: Role): string`.
- Query key: `['invitations', projectId]`.

- [ ] **Step 1: Write the failing tests** — append to `projects/MembersPage.test.tsx` (add `within` to the `@testing-library/react` import):

```tsx
const INVITATION = {
  id: 9,
  email: 'late@example.org',
  role: 'viewer',
  message: null,
  invitedBy: 'boss',
  createdAt: '2026-07-01T00:00:00Z',
  expiresAt: '2026-07-31T00:00:00Z',
  expired: true,
  acceptUrl: 'http://localhost:5173/invite/tok9',
};

function ownerProject() {
  return [
    http.get('/api/projects/5', () => HttpResponse.json({ id: 5, slug: 's', title: 'T', role: 'owner' })),
    http.get('/api/projects/5/members', () => HttpResponse.json([])),
  ];
}

test('owner invites someone by email', async () => {
  let body: unknown = null;
  server.use(
    ...ownerProject(),
    http.post('/api/projects/5/invitations', async ({ request }) => {
      body = await request.json();
      return HttpResponse.json({ ...INVITATION, id: 10, email: 'new@example.org', role: 'editor', expired: false },
        { status: 201 });
    }),
  );
  renderPage();
  await userEvent.click(await screen.findByRole('button', { name: /invite by email/i }));
  const dialog = await screen.findByRole('dialog');
  await userEvent.type(within(dialog).getByLabelText(/email/i), 'new@example.org');
  await userEvent.type(within(dialog).getByLabelText(/message/i), 'Welcome aboard');
  await userEvent.click(within(dialog).getByRole('button', { name: /send invitation/i }));
  await waitFor(() =>
    expect(body).toEqual({ email: 'new@example.org', role: 'editor', message: 'Welcome aboard' }),
  );
});

test('invite modal rejects a malformed email', async () => {
  let posted = false;
  server.use(
    ...ownerProject(),
    http.post('/api/projects/5/invitations', () => {
      posted = true;
      return HttpResponse.json(INVITATION, { status: 201 });
    }),
  );
  renderPage();
  await userEvent.click(await screen.findByRole('button', { name: /invite by email/i }));
  const dialog = await screen.findByRole('dialog');
  await userEvent.type(within(dialog).getByLabelText(/email/i), 'nope');
  await userEvent.click(within(dialog).getByRole('button', { name: /send invitation/i }));
  expect(await within(dialog).findByText(/valid email/i)).toBeInTheDocument();
  expect(posted).toBe(false);
});

test('owner sees pending invitations, can resend and revoke', async () => {
  let resent = false;
  let pending = [INVITATION];
  server.use(
    ...ownerProject(),
    http.get('/api/projects/5/invitations', () => HttpResponse.json(pending)),
    http.post('/api/projects/5/invitations/9/resend', () => {
      resent = true;
      return HttpResponse.json({ ...INVITATION, expired: false });
    }),
    http.delete('/api/projects/5/invitations/9', () => {
      pending = [];
      return new HttpResponse(null, { status: 204 });
    }),
  );
  renderPage();
  expect(await screen.findByText('late@example.org')).toBeInTheDocument();
  expect(screen.getByText('expired')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: /copy link/i })).toBeInTheDocument();

  await userEvent.click(screen.getByRole('button', { name: /resend/i }));
  await waitFor(() => expect(resent).toBe(true));

  await userEvent.click(screen.getByRole('button', { name: /revoke/i }));
  const dialog = await screen.findByRole('dialog');
  await userEvent.click(within(dialog).getByRole('button', { name: /revoke/i }));
  await waitFor(() => expect(screen.queryByText('late@example.org')).not.toBeInTheDocument());
});

test('non-owner sees no invite button and never loads invitations', async () => {
  let requested = false;
  server.use(
    http.get('/api/projects/5', () => HttpResponse.json({ id: 5, slug: 's', title: 'T', role: 'editor' })),
    http.get('/api/projects/5/members', () => HttpResponse.json([{ userId: 1, username: 'boss', role: 'owner' }])),
    http.get('/api/projects/5/invitations', () => {
      requested = true;
      return HttpResponse.json([INVITATION]);
    }),
  );
  renderPage();
  expect(await screen.findByText('boss')).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: /invite by email/i })).not.toBeInTheDocument();
  expect(requested).toBe(false);
});
```

Also add a default handler to `test/server.ts` (next to the other `/api/projects/:pid/...` defaults) so existing owner-role renders don't hit an unhandled request:
```ts
  // Default empty pending-invitations list, so any owner-role render of MembersPage doesn't need
  // this mocked per-test unless it asserts the list.
  http.get('/api/projects/:pid/invitations', () => HttpResponse.json([])),
```

- [ ] **Step 2: Run — expect FAIL**

Run: `cd frontend && npx vitest run src/projects/MembersPage.test.tsx`
Expected: the 3 new owner tests FAIL (`Unable to find role="button" and name /invite by email/i` / text `late@example.org`); the rest pass.

- [ ] **Step 3: Write `api/invitations.ts`**

```ts
import { api } from './client';
import type { Role } from './types';

export interface Invitation {
  id: number;
  email: string;
  role: Role;
  message: string | null;
  invitedBy: string | null;
  createdAt: string;
  expiresAt: string;
  expired: boolean;
  acceptUrl: string;
}

export interface InvitationPreview {
  projectTitle: string;
  invitedBy: string | null;
  role: Role;
  message: string | null;
  status: 'VALID' | 'EXPIRED' | 'ACCEPTED';
}

export interface InvitationBody {
  email: string;
  role: Role;
  message?: string;
}

// Owner-only: a project's pending (not yet accepted) email invitations, expired ones included.
export function listInvitations(pid: number): Promise<Invitation[]> {
  return api<Invitation[]>(`/api/projects/${pid}/invitations`);
}

// Creates the invitation and emails it (409 if a live one exists for that address).
export function createInvitation(pid: number, body: InvitationBody): Promise<Invitation> {
  return api<Invitation>(`/api/projects/${pid}/invitations`, { method: 'POST', json: body });
}

// New link + fresh 30-day window, emailed again; the old link stops working.
export function resendInvitation(pid: number, id: number): Promise<Invitation> {
  return api<Invitation>(`/api/projects/${pid}/invitations/${id}/resend`, { method: 'POST' });
}

export function revokeInvitation(pid: number, id: number): Promise<void> {
  return api<void>(`/api/projects/${pid}/invitations/${id}`, { method: 'DELETE' });
}

// Unauthenticated: what an invitation link is for, shown before sign-in.
export function getInvitationPreview(token: string): Promise<InvitationPreview> {
  return api<InvitationPreview>(`/api/public/invitations/${encodeURIComponent(token)}`);
}

export function acceptInvitation(token: string): Promise<{ projectId: number }> {
  return api<{ projectId: number }>(`/api/invitations/${encodeURIComponent(token)}/accept`, {
    method: 'POST',
  });
}
```

- [ ] **Step 4: Write `projects/roles.ts`** and switch `MembersPage` to it

```ts
import type { Role } from '../api/types';

export const ROLES: Role[] = ['owner', 'editor', 'viewer'];
export const ROLE_DATA = ROLES.map((r) => ({ value: r, label: r }));

// "an owner" / "an editor" / "a viewer" -- for invitation copy.
export function roleWithArticle(role: Role): string {
  return `${role === 'viewer' ? 'a' : 'an'} ${role}`;
}
```

In `MembersPage.tsx` delete the local `ROLES` / `ROLE_DATA` constants and add `import { ROLE_DATA } from './roles';`.

- [ ] **Step 5: Write `projects/InviteMemberModal.tsx`**

```tsx
import { Button, Group, Modal, Select, Stack, Textarea, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';
import { notifications } from '@mantine/notifications';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { createInvitation } from '../api/invitations';
import { messageFor } from '../api/client';
import type { Role } from '../api/types';
import { ROLE_DATA } from './roles';

interface Values {
  email: string;
  role: Role;
  message: string;
}

// Owner invites someone who may not be in Blixa yet: the backend emails them a link (owner on CC).
export default function InviteMemberModal({
  projectId,
  opened,
  onClose,
}: {
  projectId: number;
  opened: boolean;
  onClose: () => void;
}) {
  const queryClient = useQueryClient();
  const form = useForm<Values>({
    initialValues: { email: '', role: 'editor', message: '' },
    validate: {
      email: (v) => (/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(v.trim()) ? null : 'Enter a valid email address'),
    },
  });

  const close = () => {
    form.reset();
    onClose();
  };

  const mut = useMutation({
    mutationFn: (v: Values) =>
      createInvitation(projectId, {
        email: v.email.trim(),
        role: v.role,
        ...(v.message.trim() ? { message: v.message.trim() } : {}),
      }),
    onSuccess: (inv) => {
      queryClient.invalidateQueries({ queryKey: ['invitations', projectId] });
      notifications.show({ color: 'green', message: `Invitation sent to ${inv.email}` });
      close();
    },
    onError: (e) => notifications.show({ color: 'red', message: messageFor(e, 'Could not send the invitation') }),
  });

  return (
    <Modal opened={opened} onClose={close} title="Invite by email">
      <form onSubmit={form.onSubmit((v) => mut.mutate(v))}>
        <Stack>
          <TextInput label="Email" withAsterisk {...form.getInputProps('email')} />
          <Select label="Role" data={ROLE_DATA} allowDeselect={false} {...form.getInputProps('role')} />
          <Textarea
            label="Message"
            description="Optional — included in the invitation email"
            autosize
            minRows={3}
            {...form.getInputProps('message')}
          />
          <Group justify="flex-end">
            <Button variant="default" onClick={close}>
              Cancel
            </Button>
            <Button type="submit" loading={mut.isPending}>
              Send invitation
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
```

- [ ] **Step 6: Write `projects/PendingInvitations.tsx`**

```tsx
import { Badge, Button, CopyButton, Group, Paper, Stack, Text, Title } from '@mantine/core';
import { modals } from '@mantine/modals';
import { notifications } from '@mantine/notifications';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { listInvitations, resendInvitation, revokeInvitation } from '../api/invitations';
import { messageFor } from '../api/client';

dayjs.extend(relativeTime);

// Owner-only list of a project's not-yet-accepted email invitations. Copy link is the fallback
// when outgoing mail isn't configured; Resend issues a new link (the old one stops working).
export default function PendingInvitations({ projectId }: { projectId: number }) {
  const queryClient = useQueryClient();
  const { data: invitations } = useQuery({
    queryKey: ['invitations', projectId],
    queryFn: () => listInvitations(projectId),
  });
  const invalidate = () => queryClient.invalidateQueries({ queryKey: ['invitations', projectId] });

  const resendMut = useMutation({
    mutationFn: (id: number) => resendInvitation(projectId, id),
    onSuccess: (inv) => {
      invalidate();
      notifications.show({ color: 'green', message: `Invitation re-sent to ${inv.email}` });
    },
    onError: (e) => notifications.show({ color: 'red', message: messageFor(e, 'Could not resend the invitation') }),
  });
  const revokeMut = useMutation({
    mutationFn: (id: number) => revokeInvitation(projectId, id),
    onSuccess: invalidate,
    onError: (e) => notifications.show({ color: 'red', message: messageFor(e, 'Could not revoke the invitation') }),
  });

  if (!invitations || invitations.length === 0) return null;

  return (
    <Stack gap="xs" mt="xl">
      <Title order={4}>Pending invitations</Title>
      {invitations.map((inv) => (
        <Paper key={inv.id} withBorder p="sm">
          <Group justify="space-between" align="flex-start" wrap="nowrap">
            <Stack gap={2}>
              <Group gap="xs">
                <Text size="sm" fw={500}>
                  {inv.email}
                </Text>
                <Badge size="sm" variant="light">
                  {inv.role}
                </Badge>
                {inv.expired && (
                  <Badge size="sm" variant="light" color="red">
                    expired
                  </Badge>
                )}
              </Group>
              {inv.message && (
                <Text size="sm" c="dimmed">
                  {inv.message}
                </Text>
              )}
              <Text size="xs" c="dimmed">
                invited {inv.invitedBy ? `by ${inv.invitedBy} ` : ''}
                {dayjs(inv.createdAt).fromNow()}
              </Text>
            </Stack>
            <Group gap="xs" wrap="nowrap">
              <CopyButton value={inv.acceptUrl}>
                {({ copied, copy }) => (
                  <Button size="xs" variant="subtle" onClick={copy}>
                    {copied ? 'Copied' : 'Copy link'}
                  </Button>
                )}
              </CopyButton>
              <Button
                size="xs"
                variant="subtle"
                loading={resendMut.isPending && resendMut.variables === inv.id}
                onClick={() => resendMut.mutate(inv.id)}
              >
                Resend
              </Button>
              <Button
                size="xs"
                variant="subtle"
                color="red"
                onClick={() =>
                  modals.openConfirmModal({
                    title: 'Revoke invitation?',
                    children: <Text size="sm">The link sent to {inv.email} will stop working.</Text>,
                    labels: { confirm: 'Revoke', cancel: 'Cancel' },
                    confirmProps: { color: 'red' },
                    onConfirm: () => revokeMut.mutate(inv.id),
                  })
                }
              >
                Revoke
              </Button>
            </Group>
          </Group>
        </Paper>
      ))}
    </Stack>
  );
}
```

- [ ] **Step 7: Wire both into `MembersPage.tsx`**

Add imports:
```tsx
import { useMemo, useState } from 'react';
import InviteMemberModal from './InviteMemberModal';
import PendingInvitations from './PendingInvitations';
```
(replace the existing `import { useMemo } from 'react';`). Inside the component, after `const canManage = ...`:
```tsx
  const [inviteOpen, setInviteOpen] = useState(false);
```
In the owner form's `<Group align="flex-end" mb="md">`, after the "Add / update" button:
```tsx
            <Button variant="light" onClick={() => setInviteOpen(true)}>
              Invite by email
            </Button>
```
After the closing `</form>` of the owner form (still inside `{canManage && ( … )}` — wrap the form and the modal in a fragment):
```tsx
      {canManage && (
        <>
          <form onSubmit={form.onSubmit((v) => setMut.mutate(v))}>
            {/* …unchanged Group with username/role/Add-update + the new Invite button… */}
          </form>
          <InviteMemberModal projectId={id} opened={inviteOpen} onClose={() => setInviteOpen(false)} />
        </>
      )}
```
Directly after `<MantineReactTable table={table} />`:
```tsx
      {canManage && <PendingInvitations projectId={id} />}
```

- [ ] **Step 8: Run the tests — expect PASS**

Run: `cd frontend && npx vitest run src/projects/MembersPage.test.tsx`
Expected: all tests pass (5 existing + 4 new).

- [ ] **Step 9: Type-check**

Run: `cd frontend && npm run build`
Expected: exits 0.

- [ ] **Step 10: Commit**

```bash
git add frontend/src/api/invitations.ts frontend/src/projects/roles.ts \
        frontend/src/projects/InviteMemberModal.tsx frontend/src/projects/PendingInvitations.tsx \
        frontend/src/projects/MembersPage.tsx frontend/src/projects/MembersPage.test.tsx \
        frontend/src/test/server.ts
git commit -m "feat(members): invite by email + pending invitations list for owners

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 7: Frontend — the `/invite/:token` accept page

**Files:**
- Create: `frontend/src/invite/pendingInvite.ts`
- Create: `frontend/src/invite/InviteAcceptPage.tsx`
- Modify: `frontend/src/App.tsx`
- Test: `frontend/src/invite/InviteAcceptPage.test.tsx`

**Interfaces:**
- Consumes: `getInvitationPreview`, `acceptInvitation` (Task 6); `roleWithArticle` (Task 6); `useMe` (`auth/useMe`), `useConfig` (`api/config`), `orcidLoginUrl` (`api/auth`), `messageFor` (`api/client`), `BlixaLogo` (`components/BlixaLogo`).
- Produces (`invite/pendingInvite.ts`): `readPendingInvite(): string | null`, `savePendingInvite(token: string): void`, `clearPendingInvite(): void` — key `blixa.pendingInvite`.
- Route: `/invite/:token` (top level, outside `RequireAuth` and `PublicLayout`).

- [ ] **Step 1: Write the failing test**

`invite/InviteAcceptPage.test.tsx`:
```tsx
import { expect, test } from 'vitest';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { render, screen, waitFor } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import InviteAcceptPage from './InviteAcceptPage';

const KEY = 'blixa.pendingInvite';

const preview = (status: string) =>
  http.get('/api/public/invitations/tok1', () =>
    HttpResponse.json({ projectTitle: 'Beetles', invitedBy: 'Olga Owner', role: 'editor',
      message: 'Please help with the weevils.', status }),
  );

const signedIn = http.get('/api/me', () =>
  HttpResponse.json({ id: 3, username: '0000-0002-9999-0201', email: '', orcid: '0000-0002-9999-0201',
    displayName: 'Ina', admin: false, state: 'PENDING' }),
);

function renderPage() {
  return render(
    <Routes>
      <Route path="/invite/:token" element={<InviteAcceptPage />} />
      <Route path="/projects/:pid" element={<div>PROJECT PAGE</div>} />
    </Routes>,
    { route: '/invite/tok1' },
  );
}

test('signed out: shows the invitation, remembers the token and offers ORCID sign-in', async () => {
  server.use(preview('VALID')); // default /api/me is 401
  renderPage();
  expect(await screen.findByText(/join “Beetles”/i)).toBeInTheDocument();
  expect(screen.getByText(/Olga Owner invited you to join as an editor/i)).toBeInTheDocument();
  expect(screen.getByText('Please help with the weevils.')).toBeInTheDocument();
  const link = await screen.findByRole('link', { name: /sign in with orcid to accept/i });
  expect(link).toHaveAttribute('href', '/oauth2/authorization/orcid');
  await waitFor(() => expect(localStorage.getItem(KEY)).toBe('tok1'));
});

test('signed in: clears the stored token, accepts and opens the project', async () => {
  localStorage.setItem(KEY, 'tok1');
  let accepted = false;
  server.use(
    preview('VALID'),
    signedIn,
    http.post('/api/invitations/tok1/accept', () => {
      accepted = true;
      return HttpResponse.json({ projectId: 7 });
    }),
  );
  renderPage();
  const button = await screen.findByRole('button', { name: /accept invitation/i });
  await waitFor(() => expect(localStorage.getItem(KEY)).toBeNull());
  await userEvent.click(button);
  await waitFor(() => expect(accepted).toBe(true));
  expect(await screen.findByText('PROJECT PAGE')).toBeInTheDocument();
});

test('signed in: shows the server error when accepting fails', async () => {
  server.use(
    preview('VALID'),
    signedIn,
    http.post('/api/invitations/tok1/accept', () =>
      HttpResponse.json({ error: 'account disabled' }, { status: 403 })),
  );
  renderPage();
  await userEvent.click(await screen.findByRole('button', { name: /accept invitation/i }));
  expect(await screen.findByText('account disabled')).toBeInTheDocument();
});

test('expired: explains and offers no action, and forgets the stored token', async () => {
  localStorage.setItem(KEY, 'tok1');
  server.use(preview('EXPIRED'));
  renderPage();
  expect(await screen.findByText(/this invitation has expired/i)).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: /accept/i })).not.toBeInTheDocument();
  expect(screen.queryByRole('link', { name: /sign in/i })).not.toBeInTheDocument();
  await waitFor(() => expect(localStorage.getItem(KEY)).toBeNull());
});

test('already used', async () => {
  server.use(preview('ACCEPTED'));
  renderPage();
  expect(await screen.findByText(/already been used/i)).toBeInTheDocument();
});

test('unknown link', async () => {
  server.use(
    http.get('/api/public/invitations/tok1', () =>
      HttpResponse.json({ error: 'invitation not found' }, { status: 404 })),
  );
  renderPage();
  expect(await screen.findByText(/not valid/i)).toBeInTheDocument();
});
```

- [ ] **Step 2: Run — expect FAIL**

Run: `cd frontend && npx vitest run src/invite/InviteAcceptPage.test.tsx`
Expected: FAIL — `Failed to resolve import "./InviteAcceptPage"`.

- [ ] **Step 3: Write `invite/pendingInvite.ts`**

```ts
// The invitation token carried across the ORCID sign-in round-trip: the backend always lands a fresh
// login on /projects, so InviteAcceptPage stores the token before sending a signed-out visitor to
// sign in and RequireAuth resumes it afterwards. Storage can be unavailable (private mode, blocked
// site data) -- every access is guarded and a failure degrades to "no pending invite" (the invitee
// can simply click the emailed link again).
const KEY = 'blixa.pendingInvite';

export function readPendingInvite(): string | null {
  try {
    return localStorage.getItem(KEY);
  } catch {
    return null;
  }
}

export function savePendingInvite(token: string): void {
  try {
    localStorage.setItem(KEY, token);
  } catch {
    /* storage unavailable */
  }
}

export function clearPendingInvite(): void {
  try {
    localStorage.removeItem(KEY);
  } catch {
    /* storage unavailable */
  }
}
```

- [ ] **Step 4: Write `invite/InviteAcceptPage.tsx`**

```tsx
import { useEffect } from 'react';
import { Alert, Anchor, Blockquote, Button, Card, Center, Loader, Stack, Text, Title } from '@mantine/core';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate, useParams } from 'react-router-dom';
import BlixaLogo from '../components/BlixaLogo';
import { acceptInvitation, getInvitationPreview } from '../api/invitations';
import { orcidLoginUrl } from '../api/auth';
import { useConfig } from '../api/config';
import { messageFor } from '../api/client';
import { useMe } from '../auth/useMe';
import { roleWithArticle } from '../projects/roles';
import { clearPendingInvite, savePendingInvite } from './pendingInvite';

// Landing page of an emailed project invitation. Public: shows what the link is for, then either
// sends a signed-out visitor to sign in (remembering the token for RequireAuth to resume) or lets a
// signed-in user -- including a brand-new PENDING account -- accept and jump into the project.
export default function InviteAcceptPage() {
  const { token = '' } = useParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { data: config, isLoading: configLoading } = useConfig();
  const { data: me, isLoading: meLoading } = useMe();
  const preview = useQuery({
    queryKey: ['invitationPreview', token],
    queryFn: () => getInvitationPreview(token),
  });
  const valid = preview.data?.status === 'VALID';

  useEffect(() => {
    if (preview.isError || (preview.data && !valid)) {
      clearPendingInvite(); // dead link: nothing to carry across sign-in
    } else if (valid && !meLoading) {
      // Signed in: the token is in the URL now, so drop the stored copy -- otherwise RequireAuth
      // would keep bouncing the user back here if they leave without accepting.
      if (me) clearPendingInvite();
      else savePendingInvite(token);
    }
  }, [preview.isError, preview.data, valid, meLoading, me, token]);

  const accept = useMutation({
    mutationFn: () => acceptInvitation(token),
    onSuccess: async ({ projectId }) => {
      clearPendingInvite();
      await queryClient.invalidateQueries({ queryKey: ['me'] });
      await queryClient.invalidateQueries({ queryKey: ['projects'] });
      navigate(`/projects/${projectId}`, { replace: true });
    },
  });

  let body;
  // configLoading too: otherwise a signed-out visitor briefly sees the local "Sign in" button
  // before the ORCID one.
  if (preview.isLoading || meLoading || configLoading) {
    body = (
      <Center py="md">
        <Loader />
      </Center>
    );
  } else if (preview.isError || !preview.data) {
    body = <Alert color="red">This invitation link is not valid.</Alert>;
  } else if (preview.data.status === 'EXPIRED') {
    body = (
      <Alert color="yellow">
        This invitation has expired. Ask the project owner to send you a new one.
      </Alert>
    );
  } else if (preview.data.status === 'ACCEPTED') {
    body = (
      <Alert color="blue">
        This invitation has already been used.{' '}
        <Anchor component={Link} to="/projects">
          Go to your projects
        </Anchor>
      </Alert>
    );
  } else {
    const p = preview.data;
    body = (
      <Stack gap="md">
        <Title order={3}>Join “{p.projectTitle}”</Title>
        <Text>
          {p.invitedBy ?? 'A project owner'} invited you to join as {roleWithArticle(p.role)}.
        </Text>
        {p.message && <Blockquote p="sm">{p.message}</Blockquote>}
        {me ? (
          <>
            {accept.isError && (
              <Alert color="red">{messageFor(accept.error, 'Could not accept the invitation')}</Alert>
            )}
            <Button fullWidth loading={accept.isPending} onClick={() => accept.mutate()}>
              Accept invitation
            </Button>
          </>
        ) : config?.orcidEnabled ? (
          <Button fullWidth variant="default" component="a" href={orcidLoginUrl()}>
            Sign in with ORCID to accept
          </Button>
        ) : (
          <Button fullWidth variant="default" component={Link} to="/signin">
            Sign in to accept
          </Button>
        )}
      </Stack>
    );
  }

  return (
    <div style={{ display: 'flex', justifyContent: 'center', paddingTop: 80 }}>
      <Card withBorder style={{ width: 440, maxWidth: 'calc(100vw - 32px)' }}>
        <BlixaLogo variant="text" height={32} style={{ display: 'block', margin: '4px auto 20px' }} />
        {body}
      </Card>
    </div>
  );
}
```

- [ ] **Step 5: Add the route in `App.tsx`**

```tsx
import InviteAcceptPage from './invite/InviteAcceptPage';
```
and next to the `/signin` route:
```tsx
      {/* Emailed project invitations: public (works signed out), outside RequireAuth so a brand-new
          PENDING account can accept rather than hit the approval gate. */}
      <Route path="/invite/:token" element={<InviteAcceptPage />} />
```

- [ ] **Step 6: Run the tests — expect PASS**

Run: `cd frontend && npx vitest run src/invite/InviteAcceptPage.test.tsx`
Expected: 6 passed.

- [ ] **Step 7: Type-check**

Run: `cd frontend && npm run build`
Expected: exits 0.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/invite/ frontend/src/App.tsx
git commit -m "feat(invite): /invite/:token accept page (sign in with ORCID, then accept)

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 8: Resume a pending invitation after sign-in (`RequireAuth`)

**Files:**
- Modify: `frontend/src/auth/RequireAuth.tsx`
- Test: `frontend/src/auth/RequireAuth.test.tsx` (new)

**Interfaces:**
- Consumes: `readPendingInvite()` (Task 7).

- [ ] **Step 1: Write the failing test**

`auth/RequireAuth.test.tsx`:
```tsx
import { expect, test } from 'vitest';
import { Route, Routes } from 'react-router-dom';
import { render, screen } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import RequireAuth from './RequireAuth';

const me = (state: string) =>
  http.get('/api/me', () =>
    HttpResponse.json({ id: 1, username: 'u', email: '', orcid: '', displayName: 'U', admin: false, state }),
  );

function renderAt(route: string) {
  return render(
    <Routes>
      <Route element={<RequireAuth />}>
        <Route path="/projects" element={<div>PROJECTS</div>} />
      </Route>
      <Route path="/invite/:token" element={<div>INVITE PAGE</div>} />
    </Routes>,
    { route },
  );
}

test('a stored invitation resumes before the PENDING approval gate', async () => {
  localStorage.setItem('blixa.pendingInvite', 'tok1');
  server.use(me('PENDING'));
  renderAt('/projects');
  expect(await screen.findByText('INVITE PAGE')).toBeInTheDocument();
});

test('a stored invitation also resumes for an ACTIVE user', async () => {
  localStorage.setItem('blixa.pendingInvite', 'tok1');
  server.use(me('ACTIVE'));
  renderAt('/projects');
  expect(await screen.findByText('INVITE PAGE')).toBeInTheDocument();
});

test('without a stored invitation the protected route renders', async () => {
  server.use(me('ACTIVE'));
  renderAt('/projects');
  expect(await screen.findByText('PROJECTS')).toBeInTheDocument();
});
```

- [ ] **Step 2: Run — expect FAIL**

Run: `cd frontend && npx vitest run src/auth/RequireAuth.test.tsx`
Expected: the two "stored invitation" tests FAIL (they render the approval page / `PROJECTS` instead of `INVITE PAGE`); the third passes.

- [ ] **Step 3: Implement** — in `auth/RequireAuth.tsx`:

```tsx
import { Navigate, Outlet } from 'react-router-dom';
import { Center, Loader } from '@mantine/core';
import { useMe } from './useMe';
import PendingApprovalPage from './PendingApprovalPage';
import SignInRedirect from './SignInRedirect';
import { readPendingInvite } from '../invite/pendingInvite';
```
and after `if (isError || !data) return <SignInRedirect />;`:
```tsx
  // Back from the ORCID round-trip with an invitation in hand (InviteAcceptPage stored its token
  // before sending the visitor to sign in; a fresh login always lands on /projects): resume it
  // before any other gate, so a brand-new PENDING account can accept instead of waiting for approval.
  // InviteAcceptPage clears the token once a signed-in user sees it, so this can't loop.
  const pendingInvite = readPendingInvite();
  if (pendingInvite) return <Navigate to={`/invite/${encodeURIComponent(pendingInvite)}`} replace />;
```

- [ ] **Step 4: Run — expect PASS**

Run: `cd frontend && npx vitest run src/auth/RequireAuth.test.tsx src/invite/InviteAcceptPage.test.tsx`
Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/auth/RequireAuth.tsx frontend/src/auth/RequireAuth.test.tsx
git commit -m "feat(invite): resume a pending invitation after sign-in, ahead of the approval gate

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 9: Backlog entry + full verification

**Files:**
- Modify: `backlog.md` (section `### 5. Global admin & user lifecycle`)

- [ ] **Step 1: Add the backlog line** — after the "Admin **Users** page" bullet in section 5:

```markdown
- [x] **Project invitations by email** *(spec `docs/superpowers/specs/2026-09-19-email-project-invitations-design.md`)*: an owner invites anyone by email (role + optional message) from the Members page; the email (owner on CC / Reply-To) links to `/invite/<token>`, where the invitee signs in with ORCID and accepts. Accepting **activates a PENDING account** (the owner vouches — no admin step), fills a blank account email, and adds the membership (an existing member's role is never changed). Single-use links, 30-day expiry; owners can Copy link / Resend / Revoke pending invitations. The token survives the ORCID round-trip in `localStorage` (`RequireAuth` resumes it).
```

- [ ] **Step 2: Full frontend suite + type-check**

Run: `cd frontend && npm test && npm run build`
Expected: all vitest files pass; build exits 0.

- [ ] **Step 3: Full backend suite**

Run: `cd backend && mvn -o verify`
Expected: BUILD SUCCESS (unit + all ITs, incl. the three new `invite` ITs).

- [ ] **Step 4: Commit**

```bash
git add backlog.md
git commit -m "docs(backlog): project invitations by email shipped

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```
