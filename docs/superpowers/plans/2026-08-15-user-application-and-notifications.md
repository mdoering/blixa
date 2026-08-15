# User Application & Lifecycle Emails Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Require a self-registering ORCID user to supply an email (+ optional message) to apply, email admins when a new application arrives, and email the applicant when an admin approves them.

**Architecture:** Reuse the existing PENDING/ACTIVE/DISABLED account states. Add an `application_note` column; capture email/note through two new `/api/me/*` endpoints; add a `UserNotifier` (mirroring the existing `DiscussionNotifier`) that uses the generic `EmailService` (lifted into a shared `notify` package). Frontend: an application form on the pending-approval screen, an email field in account settings, and email/message columns on the admin users page.

**Tech Stack:** Backend Spring Boot 4.1 / Java 25, MyBatis, PostgreSQL 17, Flyway; Testcontainers ITs with MockMvc + `@MockitoBean`. Frontend React + TypeScript + Vite + Mantine; vitest + @testing-library + MSW.

## Global Constraints

- **Backend needs JDK 25.** Run `sdk env` (reads `.sdkmanrc`) before any `mvn`. Compile offline: `mvn -o compile` / `mvn -o test-compile`.
- **ITs are Testcontainers-backed** and need Docker (OrbStack): start with `orb start` (a "start VM: timed out" line is harmless; `docker info` confirms). Run one IT: `mvn -o test-compile failsafe:integration-test failsafe:verify -Dit.test=<FQN>`.
- **Schema changes are Flyway migrations only** — `backend/src/main/resources/db/migration/V<n>__*.sql`. Next free version is **V10**.
- **MyBatis has `map-underscore-to-camel-case: true`** — `SELECT *` maps `application_note` → `applicationNote` automatically; `@Insert`/`@Update` column lists are explicit and must be edited.
- **All email sending is best-effort: it must never throw** and must not break the triggering action. It only actually sends when `spring.mail.host` and `coldp.mail.from` are set; otherwise it logs and skips. Reuse config keys `coldp.mail.from` and `coldp.mail.base-url` — no new config.
- **Email is required to apply; the application message is optional.**
- **The approval email fires only on `PENDING → ACTIVE`**, never on `DISABLED → ACTIVE` reactivation.
- **Frontend build is the type-check gate:** `npm run build` (`tsc -b && vite build`). No ESLint. Run one test: `npx vitest run src/<path>`.
- **Notification copy (verbatim):**
  - Applicant approved — subject `Your Blixa account has been approved`; body starts `Your Blixa account has been approved. You can now sign in:` then a blank line and `${base}/signin`.
  - Admins on new application — subject `New Blixa access request from ${name}`; body lists Name/ORCID/Email/Message then a blank line and `${base}/admin/users`.

---

### Task 1: `application_note` column + model + mapper

**Files:**
- Create: `backend/src/main/resources/db/migration/V10__application_note.sql`
- Modify: `backend/src/main/java/org/catalogueoflife/editor/user/AppUser.java`
- Modify: `backend/src/main/java/org/catalogueoflife/editor/user/AppUserMapper.java` (insert + update SQL)
- Test: `backend/src/test/java/org/catalogueoflife/editor/user/ApplicationNoteMapperIT.java`

**Interfaces:**
- Produces: `AppUser.getApplicationNote()` / `setApplicationNote(String)`; `app_user.application_note` column persisted by `AppUserMapper.insert`/`update`.

- [ ] **Step 1: Write the failing test**

Create `ApplicationNoteMapperIT.java`:
```java
package org.catalogueoflife.editor.user;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
class ApplicationNoteMapperIT extends AbstractPostgresIT {

  @Autowired AppUserMapper mapper;

  @Test
  void persistsAndUpdatesApplicationNote() {
    AppUser u = new AppUser();
    u.setUsername("noteUser");
    u.setDisplayName("Note User");
    u.setState("PENDING");
    u.setApplicationNote("please let me in");
    mapper.insert(u);

    assertEquals("please let me in", mapper.findById(u.getId()).getApplicationNote());

    AppUser back = mapper.findById(u.getId());
    back.setApplicationNote("updated note");
    mapper.update(back);
    assertEquals("updated note", mapper.findById(u.getId()).getApplicationNote());
  }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && sdk env && orb start; mvn -o test-compile failsafe:integration-test failsafe:verify -Dit.test=org.catalogueoflife.editor.user.ApplicationNoteMapperIT`
Expected: FAIL — compile error (`setApplicationNote` undefined) or, once the field exists but the migration/mapper don't, an assertion/SQL failure.

- [ ] **Step 3: Add the migration**

Create `V10__application_note.sql`:
```sql
ALTER TABLE app_user ADD COLUMN application_note text;
```

- [ ] **Step 4: Add the model field**

In `AppUser.java`, after the `state` field add:
```java
  private String applicationNote;
```
and with the other accessors:
```java
  public String getApplicationNote() { return applicationNote; }
  public void setApplicationNote(String applicationNote) { this.applicationNote = applicationNote; }
```

- [ ] **Step 5: Add the column to insert + update SQL**

In `AppUserMapper.java`, change the `@Insert` to include `application_note`:
```java
  @Insert("""
      INSERT INTO app_user (orcid, username, email, display_name, given, family, password_hash,
                            admin, state, application_note)
      VALUES (#{orcid}, #{username}, #{email}, #{displayName}, #{given}, #{family}, #{passwordHash},
              #{admin}, COALESCE(#{state}, 'ACTIVE'), #{applicationNote})
      """)
```
and the `@Update` set-list to include it:
```java
  @Update("""
      UPDATE app_user
      SET orcid = #{orcid}, username = #{username}, email = #{email},
          display_name = #{displayName}, given = #{given}, family = #{family},
          password_hash = #{passwordHash}, admin = #{admin}, state = #{state},
          application_note = #{applicationNote}, updated_at = now()
      WHERE id = #{id}
      """)
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `mvn -o test-compile failsafe:integration-test failsafe:verify -Dit.test=org.catalogueoflife.editor.user.ApplicationNoteMapperIT`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/resources/db/migration/V10__application_note.sql \
  backend/src/main/java/org/catalogueoflife/editor/user/AppUser.java \
  backend/src/main/java/org/catalogueoflife/editor/user/AppUserMapper.java \
  backend/src/test/java/org/catalogueoflife/editor/user/ApplicationNoteMapperIT.java
git commit -m "feat(user): application_note column + model/mapper plumbing"
```

---

### Task 2: `email` in `/api/me` + `PUT /api/me/email`

**Files:**
- Modify: `backend/src/main/java/org/catalogueoflife/editor/user/AppUserService.java`
- Modify: `backend/src/main/java/org/catalogueoflife/editor/auth/MeController.java`
- Test: `backend/src/test/java/org/catalogueoflife/editor/auth/MeEmailIT.java`

**Interfaces:**
- Produces: `AppUserService.updateEmail(int userId, String rawEmail): AppUser`; `GET /api/me` payload key `email`; `PUT /api/me/email {email}` → updated me map.

- [ ] **Step 1: Write the failing test**

Create `MeEmailIT.java`:
```java
package org.catalogueoflife.editor.auth;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
@ActiveProfiles("test")
class MeEmailIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;

  @Test
  @WithMockUser(username = "emailUser")
  void setsAndValidatesEmail() throws Exception {
    if (users.requireByUsernameOrNull("emailUser") == null) users.createLocal("emailUser", "pw", "E");

    // malformed -> 400
    mvc.perform(put("/api/me/email").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"not-an-email\"}"))
       .andExpect(status().isBadRequest());

    // blank -> 400
    mvc.perform(put("/api/me/email").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"\"}"))
       .andExpect(status().isBadRequest());

    // valid -> 200 and echoed back on /api/me
    mvc.perform(put("/api/me/email").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"emailuser@example.org\"}"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.email").value("emailuser@example.org"));

    mvc.perform(get("/api/me"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.email").value("emailuser@example.org"));
  }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn -o test-compile failsafe:integration-test failsafe:verify -Dit.test=org.catalogueoflife.editor.auth.MeEmailIT`
Expected: FAIL — no `/api/me/email` mapping (404/405) / `$.email` missing.

- [ ] **Step 3: Add `updateEmail` to the service**

In `AppUserService.java`, add the pattern near the other patterns:
```java
  // A permissive email shape check -- we don't verify deliverability, just reject obvious junk.
  private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
```
and the method (next to `updateUsername`):
```java
  @Transactional
  public AppUser updateEmail(int userId, String rawEmail) {
    String email = rawEmail == null ? "" : rawEmail.trim();
    if (!EMAIL.matcher(email).matches()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "a valid email address is required");
    }
    AppUser me = mapper.findById(userId);
    if (me == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found");
    }
    me.setEmail(email);
    mapper.update(me);
    return me;
  }
```

- [ ] **Step 4: Expose email in the me map + add the endpoint**

In `MeController.java`, add `email` to `meMap`:
```java
  private static Map<String, Object> meMap(AppUser u) {
    return Map.of(
        "id", u.getId(),
        "username", u.getUsername(),
        "email", u.getEmail() == null ? "" : u.getEmail(),
        "orcid", u.getOrcid() == null ? "" : u.getOrcid(),
        "displayName", u.getDisplayName() == null ? "" : u.getDisplayName(),
        "admin", u.isAdmin(),
        "state", u.getState() == null ? "ACTIVE" : u.getState());
  }
```
and the endpoint (next to `updateUsername`):
```java
  // Let the signed-in user set/update their contact email.
  @PutMapping("/api/me/email")
  public Map<String, Object> updateEmail(@RequestBody Map<String, String> body) {
    AppUser me = currentUser.require();
    return meMap(users.updateEmail(me.getId(), body.get("email")));
  }
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn -o test-compile failsafe:integration-test failsafe:verify -Dit.test=org.catalogueoflife.editor.auth.MeEmailIT`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/org/catalogueoflife/editor/user/AppUserService.java \
  backend/src/main/java/org/catalogueoflife/editor/auth/MeController.java \
  backend/src/test/java/org/catalogueoflife/editor/auth/MeEmailIT.java
git commit -m "feat(me): expose email on /api/me + PUT /api/me/email"
```

---

### Task 3: Move `EmailService` to `notify` package + add `UserNotifier`

**Files:**
- Move: `backend/src/main/java/org/catalogueoflife/editor/discussion/EmailService.java` → `backend/src/main/java/org/catalogueoflife/editor/notify/EmailService.java`
- Modify: `backend/src/main/java/org/catalogueoflife/editor/discussion/DiscussionNotifier.java` (import)
- Modify: `backend/src/test/java/org/catalogueoflife/editor/discussion/DiscussionFollowIT.java` (import)
- Modify: `backend/src/main/java/org/catalogueoflife/editor/user/AppUserMapper.java` (new query)
- Create: `backend/src/main/java/org/catalogueoflife/editor/notify/UserNotifier.java`
- Test: `backend/src/test/java/org/catalogueoflife/editor/notify/UserNotifierIT.java`

**Interfaces:**
- Consumes: `EmailService.send(String to, String subject, String text)` (now in package `notify`).
- Produces: `AppUserMapper.findActiveAdminsWithEmail(): List<AppUser>`; `UserNotifier.notifyApproved(AppUser)`; `UserNotifier.notifyAdminsOfApplication(AppUser)`.

- [ ] **Step 1: Write the failing test**

Create `UserNotifierIT.java`:
```java
package org.catalogueoflife.editor.notify;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserMapper;
import org.catalogueoflife.editor.user.AppUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@ActiveProfiles("test")
class UserNotifierIT extends AbstractPostgresIT {

  @Autowired UserNotifier notifier;
  @Autowired AppUserService users;
  @Autowired AppUserMapper mapper;
  @MockitoBean EmailService email; // no real SMTP -- verify the notifier's targeting

  private AppUser withEmail(String username, String addr) {
    if (users.requireByUsernameOrNull(username) == null) users.createLocal(username, "pw", username);
    AppUser u = users.requireByUsernameOrNull(username);
    u.setEmail(addr);
    mapper.update(u);
    return u;
  }

  @Test
  void approvedSendsOnlyWhenEmailPresent() {
    AppUser hasEmail = withEmail("appHasEmail", "has@example.org");
    notifier.notifyApproved(hasEmail);
    verify(email).send(eq("has@example.org"), contains("approved"), anyString());

    AppUser noEmail = users.createLocal("appNoEmail", "pw", "No Email");
    notifier.notifyApproved(noEmail); // no email on file -> no send
    // exactly one send total across both calls (the no-email user was skipped)
    verify(email, times(1)).send(anyString(), anyString(), anyString());
  }

  @Test
  void applicationNotifiesOnlyActiveAdminsWithEmail() {
    AppUser admin = withEmail("notifyAdmin", "admin@example.org");
    admin.setAdmin(true);
    mapper.update(admin);
    withEmail("plainWithEmail", "plain@example.org"); // active, not admin -> skipped

    AppUser applicant = new AppUser();
    applicant.setUsername("theApplicant");
    applicant.setDisplayName("The Applicant");
    applicant.setEmail("applicant@example.org");
    applicant.setApplicationNote("I curate beetles");
    applicant.setState("PENDING");
    mapper.insert(applicant);

    notifier.notifyAdminsOfApplication(applicant);
    verify(email).send(eq("admin@example.org"), contains("access request"), anyString());
    verify(email, never()).send(eq("plain@example.org"), anyString(), anyString());
  }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn -o test-compile failsafe:integration-test failsafe:verify -Dit.test=org.catalogueoflife.editor.notify.UserNotifierIT`
Expected: FAIL — `notify.EmailService` / `UserNotifier` don't exist yet.

- [ ] **Step 3: Move `EmailService` into the `notify` package**

Move the file to `backend/src/main/java/org/catalogueoflife/editor/notify/EmailService.java` and change only its first line:
```java
package org.catalogueoflife.editor.notify;
```
(the rest of the class is unchanged).

- [ ] **Step 4: Fix the two importers**

In `DiscussionNotifier.java` add:
```java
import org.catalogueoflife.editor.notify.EmailService;
```
In `DiscussionFollowIT.java` add the same import:
```java
import org.catalogueoflife.editor.notify.EmailService;
```

- [ ] **Step 5: Add the admin lookup query**

In `AppUserMapper.java`, add:
```java
  @Select("""
      SELECT * FROM app_user
      WHERE admin = true AND state = 'ACTIVE' AND email IS NOT NULL AND email <> ''
      """)
  java.util.List<AppUser> findActiveAdminsWithEmail();
```

- [ ] **Step 6: Create `UserNotifier`**

Create `notify/UserNotifier.java`:
```java
package org.catalogueoflife.editor.notify;

import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

// Emails around the account lifecycle (application submitted, application approved). Best-effort:
// never throws, so a notification problem can't break the triggering action.
@Service
public class UserNotifier {

  private final AppUserMapper users;
  private final EmailService email;
  private final String baseUrl;

  public UserNotifier(AppUserMapper users, EmailService email,
      @Value("${coldp.mail.base-url:}") String baseUrl) {
    this.users = users;
    this.email = email;
    this.baseUrl = baseUrl;
  }

  public void notifyApproved(AppUser user) {
    if (user == null || user.getEmail() == null || user.getEmail().isBlank()) return;
    email.send(user.getEmail(), "Your Blixa account has been approved",
        "Your Blixa account has been approved. You can now sign in:\n\n" + baseUrl + "/signin");
  }

  public void notifyAdminsOfApplication(AppUser applicant) {
    try {
      String name = applicant.getDisplayName() == null || applicant.getDisplayName().isBlank()
          ? applicant.getUsername() : applicant.getDisplayName();
      String note = applicant.getApplicationNote() == null || applicant.getApplicationNote().isBlank()
          ? "(no message)" : applicant.getApplicationNote();
      String subject = "New Blixa access request from " + name;
      String body = "A new access request is awaiting review.\n\n"
          + "Name: " + name + "\n"
          + "ORCID: " + (applicant.getOrcid() == null ? "—" : applicant.getOrcid()) + "\n"
          + "Email: " + (applicant.getEmail() == null ? "—" : applicant.getEmail()) + "\n"
          + "Message: " + note + "\n\n" + baseUrl + "/admin/users";
      for (AppUser admin : users.findActiveAdminsWithEmail()) {
        email.send(admin.getEmail(), subject, body);
      }
    } catch (Exception e) {
      // best-effort: swallow any failure so the triggering action still succeeds
    }
  }
}
```

- [ ] **Step 7: Run the new test + the discussion follow IT (regression from the move)**

Run: `mvn -o test-compile failsafe:integration-test failsafe:verify -Dit.test=org.catalogueoflife.editor.notify.UserNotifierIT,org.catalogueoflife.editor.discussion.DiscussionFollowIT`
Expected: PASS for both.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/org/catalogueoflife/editor/notify/ \
  backend/src/main/java/org/catalogueoflife/editor/discussion/DiscussionNotifier.java \
  backend/src/test/java/org/catalogueoflife/editor/discussion/DiscussionFollowIT.java \
  backend/src/main/java/org/catalogueoflife/editor/user/AppUserMapper.java \
  backend/src/test/java/org/catalogueoflife/editor/notify/UserNotifierIT.java
git rm backend/src/main/java/org/catalogueoflife/editor/discussion/EmailService.java 2>/dev/null || true
git commit -m "feat(notify): shared EmailService + UserNotifier (approval/application emails)"
```

---

### Task 4: `PUT /api/me/application` + allow-list + admin notification

**Files:**
- Modify: `backend/src/main/java/org/catalogueoflife/editor/user/AppUserService.java` (`submitApplication` + `ApplicationResult`)
- Modify: `backend/src/main/java/org/catalogueoflife/editor/auth/MeController.java` (endpoint + inject `UserNotifier`)
- Modify: `backend/src/main/java/org/catalogueoflife/editor/auth/ActiveUserFilter.java` (allow-list)
- Test: `backend/src/test/java/org/catalogueoflife/editor/auth/MeApplicationIT.java`

**Interfaces:**
- Consumes: `UserNotifier.notifyAdminsOfApplication(AppUser)`.
- Produces: `AppUserService.submitApplication(int userId, String rawEmail, String rawNote): AppUserService.ApplicationResult` where `ApplicationResult(AppUser user, boolean notifyAdmins)`; `PUT /api/me/application {email, note}` reachable by PENDING users.

- [ ] **Step 1: Write the failing test**

Create `MeApplicationIT.java`:
```java
package org.catalogueoflife.editor.auth;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.catalogueoflife.editor.notify.EmailService;
import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserMapper;
import org.catalogueoflife.editor.user.AppUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
@ActiveProfiles("test")
class MeApplicationIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired AppUserMapper mapper;
  @MockitoBean EmailService email;

  @Test
  void pendingUserAppliesAndAdminsAreNotifiedOnce() throws Exception {
    // an active admin with an email is the notification target
    if (users.requireByUsernameOrNull("appAdmin") == null) users.createLocal("appAdmin", "pw", "Adm");
    users.markAdmin("appAdmin");
    AppUser admin = users.requireByUsernameOrNull("appAdmin");
    admin.setEmail("appadmin@example.org");
    mapper.update(admin);

    // a fresh ORCID self-signup, PENDING with no email
    String orcid = "0000-0002-9999-0001";
    users.upsertFromOrcid(orcid, "New Applicant", "New", "Applicant");

    // blank email -> 400
    mvc.perform(put("/api/me/application").with(user(orcid)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"\",\"note\":\"hi\"}"))
       .andExpect(status().isBadRequest());

    // valid application -> 200 (NOT 403: proves the ActiveUserFilter allow-list), email stored
    mvc.perform(put("/api/me/application").with(user(orcid)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"applicant@example.org\",\"note\":\"I curate beetles\"}"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.email").value("applicant@example.org"));

    verify(email).send(eq("appadmin@example.org"), contains("access request"), anyString());

    // editing the application again does NOT re-notify admins
    mvc.perform(put("/api/me/application").with(user(orcid)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"applicant2@example.org\",\"note\":\"still beetles\"}"))
       .andExpect(status().isOk());
    verify(email, times(1)).send(eq("appadmin@example.org"), contains("access request"), anyString());
  }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn -o test-compile failsafe:integration-test failsafe:verify -Dit.test=org.catalogueoflife.editor.auth.MeApplicationIT`
Expected: FAIL — endpoint missing; a PENDING user currently 403s on `/api/me/application`.

- [ ] **Step 3: Add `submitApplication` + `ApplicationResult` to the service**

In `AppUserService.java` add the result record (top of the class body):
```java
  public record ApplicationResult(AppUser user, boolean notifyAdmins) {}
```
and the method:
```java
  // A PENDING applicant supplies their (required) email and an optional message. Returns the updated
  // user plus whether this was the first completed submission (email was previously blank) so the
  // caller notifies admins exactly once, not on every later edit.
  @Transactional
  public ApplicationResult submitApplication(int userId, String rawEmail, String rawNote) {
    String email = rawEmail == null ? "" : rawEmail.trim();
    if (!EMAIL.matcher(email).matches()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "a valid email address is required");
    }
    AppUser me = mapper.findById(userId);
    if (me == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found");
    }
    boolean wasIncomplete = me.getEmail() == null || me.getEmail().isBlank();
    String note = rawNote == null || rawNote.isBlank() ? null : rawNote.trim();
    me.setEmail(email);
    me.setApplicationNote(note);
    mapper.update(me);
    return new ApplicationResult(me, wasIncomplete);
  }
```

- [ ] **Step 4: Add the endpoint (with `UserNotifier`)**

In `MeController.java`, add the dependency and endpoint. Update the constructor to inject `UserNotifier`:
```java
  private final CurrentUser currentUser;
  private final AppUserService users;
  private final org.catalogueoflife.editor.notify.UserNotifier userNotifier;

  public MeController(CurrentUser currentUser, AppUserService users,
      org.catalogueoflife.editor.notify.UserNotifier userNotifier) {
    this.currentUser = currentUser;
    this.users = users;
    this.userNotifier = userNotifier;
  }
```
and the endpoint:
```java
  // A PENDING applicant submits/updates their required email + optional message. Admins are emailed
  // once, on the first completed application.
  @PutMapping("/api/me/application")
  public Map<String, Object> submitApplication(@RequestBody Map<String, String> body) {
    AppUser me = currentUser.require();
    AppUserService.ApplicationResult result =
        users.submitApplication(me.getId(), body.get("email"), body.get("note"));
    if (result.notifyAdmins()) {
      userNotifier.notifyAdminsOfApplication(result.user());
    }
    return meMap(result.user());
  }
```

- [ ] **Step 5: Allow PENDING users through to the application endpoint**

In `ActiveUserFilter.java`, add `/api/me/application` to the allow-list:
```java
  private static final Set<String> ALLOW =
      Set.of("/api/me", "/api/me/application", "/api/auth/logout", "/api/ping", "/api/config");
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `mvn -o test-compile failsafe:integration-test failsafe:verify -Dit.test=org.catalogueoflife.editor.auth.MeApplicationIT`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/org/catalogueoflife/editor/user/AppUserService.java \
  backend/src/main/java/org/catalogueoflife/editor/auth/MeController.java \
  backend/src/main/java/org/catalogueoflife/editor/auth/ActiveUserFilter.java \
  backend/src/test/java/org/catalogueoflife/editor/auth/MeApplicationIT.java
git commit -m "feat(me): PUT /api/me/application (required email + optional note) notifies admins"
```

---

### Task 5: Approval email on `PENDING → ACTIVE`

**Files:**
- Modify: `backend/src/main/java/org/catalogueoflife/editor/admin/AdminUserService.java`
- Test: `backend/src/test/java/org/catalogueoflife/editor/admin/AdminApprovalNotifyIT.java`

**Interfaces:**
- Consumes: `UserNotifier.notifyApproved(AppUser)`.
- Produces: approval email side-effect on the existing `POST /api/admin/users/{id}/state` when state goes PENDING→ACTIVE.

- [ ] **Step 1: Write the failing test**

Create `AdminApprovalNotifyIT.java`:
```java
package org.catalogueoflife.editor.admin;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.catalogueoflife.editor.notify.EmailService;
import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserMapper;
import org.catalogueoflife.editor.user.AppUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(username = "apprAdmin")
class AdminApprovalNotifyIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired AppUserMapper mapper;
  @MockitoBean EmailService email;

  private void post(int id, String state) throws Exception {
    mvc.perform(post("/api/admin/users/" + id + "/state").with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"" + state + "\"}"))
       .andExpect(status().isOk());
  }

  @Test
  void approvalEmailsApplicantButReactivationDoesNot() throws Exception {
    if (users.requireByUsernameOrNull("apprAdmin") == null) users.createLocal("apprAdmin", "pw", "A");
    users.markAdmin("apprAdmin");

    // PENDING applicant with an email -> approving emails them
    AppUser applicant = users.upsertFromOrcid("0000-0002-7777-0002", "Appl", "A", "Ppl");
    applicant.setEmail("appl@example.org");
    mapper.update(applicant);
    post(applicant.getId(), "ACTIVE");
    verify(email).send(eq("appl@example.org"), contains("approved"), anyString());

    // a separate ACTIVE user with an email, disabled then reactivated -> NOT emailed as "approved"
    AppUser other = users.createLocal("reactivateMe", "pw", "R");
    other.setEmail("react@example.org");
    mapper.update(other);
    post(other.getId(), "DISABLED");
    post(other.getId(), "ACTIVE");
    verify(email, never()).send(eq("react@example.org"), contains("approved"), anyString());
  }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn -o test-compile failsafe:integration-test failsafe:verify -Dit.test=org.catalogueoflife.editor.admin.AdminApprovalNotifyIT`
Expected: FAIL — no email sent (`notifyApproved` not wired).

- [ ] **Step 3: Wire the notifier into `setState`**

In `AdminUserService.java`, inject `UserNotifier` and fire on PENDING→ACTIVE. Update the field/constructor:
```java
  private final AppUserMapper users;
  private final org.catalogueoflife.editor.notify.UserNotifier notifier;

  public AdminUserService(AppUserMapper users, org.catalogueoflife.editor.notify.UserNotifier notifier) {
    this.users = users;
    this.notifier = notifier;
  }
```
and in `setState`, capture the old state and notify after the update:
```java
  @Transactional
  public AppUser setState(int actorId, int userId, String stateRaw) {
    requireAdmin(actorId);
    UserState state = parseState(stateRaw);
    AppUser target = requireUser(userId);
    if (userId == actorId && state != UserState.ACTIVE) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "you can't deactivate your own account");
    }
    String oldState = target.getState();
    target.setState(state.name());
    users.update(target);
    if (UserState.PENDING.name().equals(oldState) && state == UserState.ACTIVE) {
      notifier.notifyApproved(target);
    }
    return target;
  }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -o test-compile failsafe:integration-test failsafe:verify -Dit.test=org.catalogueoflife.editor.admin.AdminApprovalNotifyIT`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/org/catalogueoflife/editor/admin/AdminUserService.java \
  backend/src/test/java/org/catalogueoflife/editor/admin/AdminApprovalNotifyIT.java
git commit -m "feat(admin): email applicant on PENDING->ACTIVE approval"
```

---

### Task 6: Surface `email` + `applicationNote` on the admin users API

**Files:**
- Modify: `backend/src/main/java/org/catalogueoflife/editor/admin/dto/AdminUserResponse.java`
- Test: `backend/src/test/java/org/catalogueoflife/editor/admin/AdminUserFieldsIT.java`

**Interfaces:**
- Produces: `GET /api/admin/users` items include `email` and `applicationNote`.

- [ ] **Step 1: Write the failing test**

Create `AdminUserFieldsIT.java`:
```java
package org.catalogueoflife.editor.admin;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserMapper;
import org.catalogueoflife.editor.user.AppUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(username = "fieldsAdmin")
class AdminUserFieldsIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired AppUserMapper mapper;

  @Test
  void listExposesEmailAndApplicationNote() throws Exception {
    if (users.requireByUsernameOrNull("fieldsAdmin") == null) users.createLocal("fieldsAdmin", "pw", "F");
    users.markAdmin("fieldsAdmin");

    AppUser applicant = users.upsertFromOrcid("0000-0002-3333-0003", "Fld Applicant", "F", "A");
    applicant.setEmail("fields@example.org");
    applicant.setApplicationNote("beetle curator");
    mapper.update(applicant);

    mvc.perform(get("/api/admin/users"))
       .andExpect(status().isOk())
       .andExpect(content().string(containsString("fields@example.org")))
       .andExpect(content().string(containsString("beetle curator")));
  }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn -o test-compile failsafe:integration-test failsafe:verify -Dit.test=org.catalogueoflife.editor.admin.AdminUserFieldsIT`
Expected: FAIL — response has no `email`/`applicationNote`.

- [ ] **Step 3: Add the fields to the DTO**

Replace `AdminUserResponse.java` with:
```java
package org.catalogueoflife.editor.admin.dto;

import org.catalogueoflife.editor.user.AppUser;

public record AdminUserResponse(Integer id, String username, String orcid, String displayName,
    String email, String applicationNote, String state, boolean admin) {
  public static AdminUserResponse of(AppUser u) {
    return new AdminUserResponse(u.getId(), u.getUsername(), u.getOrcid(), u.getDisplayName(),
        u.getEmail(), u.getApplicationNote(), u.getState(), u.isAdmin());
  }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -o test-compile failsafe:integration-test failsafe:verify -Dit.test=org.catalogueoflife.editor.admin.AdminUserFieldsIT`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/org/catalogueoflife/editor/admin/dto/AdminUserResponse.java \
  backend/src/test/java/org/catalogueoflife/editor/admin/AdminUserFieldsIT.java
git commit -m "feat(admin): include email + applicationNote in the users list"
```

---

### Task 7: Account settings email field (frontend)

**Files:**
- Modify: `frontend/src/api/types.ts` (`Me.email`)
- Modify: `frontend/src/api/auth.ts` (`updateEmail`)
- Modify: `frontend/src/components/AccountModal.tsx`
- Test: `frontend/src/components/AccountModal.test.tsx`

**Interfaces:**
- Produces: `Me.email: string`; `updateEmail(email: string): Promise<Me>` → `PUT /api/me/email`.

- [ ] **Step 1: Write the failing test**

Create `AccountModal.test.tsx`:
```tsx
import { expect, test } from 'vitest';
import userEvent from '@testing-library/user-event';
import { render, screen, waitFor } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import AccountModal from './AccountModal';

test('prefills the email and saves an edited email', async () => {
  server.use(
    http.get('/api/me', () =>
      HttpResponse.json({ id: 1, username: 'alice', email: 'old@example.org', orcid: '',
        displayName: 'Alice', admin: false, state: 'ACTIVE' }),
    ),
  );
  let put: unknown = null;
  server.use(
    http.put('/api/me/email', async ({ request }) => {
      put = await request.json();
      return HttpResponse.json({ id: 1, username: 'alice', email: 'new@example.org', orcid: '',
        displayName: 'Alice', admin: false, state: 'ACTIVE' });
    }),
  );

  render(<AccountModal opened onClose={() => {}} />);
  const emailInput = await screen.findByLabelText(/email/i);
  await waitFor(() => expect(emailInput).toHaveValue('old@example.org'));

  await userEvent.clear(emailInput);
  await userEvent.type(emailInput, 'new@example.org');
  await userEvent.click(screen.getByRole('button', { name: /save/i }));

  await waitFor(() => expect(put).toEqual({ email: 'new@example.org' }));
});
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd frontend && npx vitest run src/components/AccountModal.test.tsx`
Expected: FAIL — no email input; `updateEmail` doesn't exist.

- [ ] **Step 3: Add `email` to the `Me` type**

In `frontend/src/api/types.ts`, add to `Me`:
```ts
  email: string;
```
(place it after `username`).

- [ ] **Step 4: Add the `updateEmail` API wrapper**

In `frontend/src/api/auth.ts`, after `updateUsername`:
```ts
// Set/update the signed-in user's contact email (400 if malformed). Returns the updated Me.
export function updateEmail(email: string): Promise<Me> {
  return api<Me>('/api/me/email', { method: 'PUT', json: { email } });
}
```

- [ ] **Step 5: Add the email field to `AccountModal`**

Edit `frontend/src/components/AccountModal.tsx`. Add `email` state + an input, and save it. Replace the imports/body so it reads:
```tsx
import { Button, Group, Modal, Stack, Text, TextInput } from '@mantine/core';
import { useEffect, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { notifications } from '@mantine/notifications';
import { messageFor } from '../api/client';
import { updateEmail, updateUsername } from '../api/auth';
import { useMe } from '../auth/useMe';

const EMAIL_RE = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;

// Account settings: pick a custom, unique username (the display handle used for @mentions) and set a
// contact email (used for lifecycle notifications). ORCID stays as a read-only reference.
export default function AccountModal({ opened, onClose }: { opened: boolean; onClose: () => void }) {
  const { data: me } = useMe();
  const qc = useQueryClient();
  const [username, setUsername] = useState('');
  const [email, setEmail] = useState('');

  useEffect(() => {
    if (opened) {
      setUsername(me?.username ?? '');
      setEmail(me?.email ?? '');
    }
  }, [opened, me]);

  const save = useMutation({
    mutationFn: async () => {
      if (username.trim() !== (me?.username ?? '')) await updateUsername(username.trim());
      return updateEmail(email.trim());
    },
    onSuccess: async () => {
      await qc.invalidateQueries({ queryKey: ['me'] });
      notifications.show({ message: 'Account updated' });
      onClose();
    },
    onError: (e) =>
      notifications.show({ color: 'red', message: messageFor(e, 'Could not update account') }),
  });

  const emailValid = EMAIL_RE.test(email.trim());

  return (
    <Modal opened={opened} onClose={onClose} title="Account" size="md">
      <Stack>
        <TextInput
          label="Username"
          description="Your unique handle — letters, digits, _ or - (min 2). Used for @mentions."
          value={username}
          onChange={(e) => setUsername(e.currentTarget.value)}
        />
        <TextInput
          label="Email"
          description="Where we send account notifications (e.g. when your application is approved)."
          value={email}
          error={email.trim() && !emailValid ? 'Enter a valid email address' : undefined}
          onChange={(e) => setEmail(e.currentTarget.value)}
        />
        {me?.orcid ? (
          <Text size="xs" c="dimmed">
            ORCID: {me.orcid}
          </Text>
        ) : null}
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>
            Cancel
          </Button>
          <Button
            onClick={() => save.mutate()}
            loading={save.isPending}
            disabled={!username.trim() || !emailValid}
          >
            Save
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `npx vitest run src/components/AccountModal.test.tsx`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/api/types.ts frontend/src/api/auth.ts \
  frontend/src/components/AccountModal.tsx frontend/src/components/AccountModal.test.tsx
git commit -m "feat(account): editable contact email in account settings"
```

---

### Task 8: Application form on the pending-approval screen (frontend)

**Files:**
- Modify: `frontend/src/api/auth.ts` (`submitApplication`)
- Modify: `frontend/src/auth/PendingApprovalPage.tsx`
- Test: `frontend/src/auth/PendingApprovalPage.test.tsx`

**Interfaces:**
- Consumes: `Me.email` (from Task 7).
- Produces: `submitApplication(email: string, note: string): Promise<Me>` → `PUT /api/me/application`.

- [ ] **Step 1: Write the failing test**

Create `PendingApprovalPage.test.tsx`:
```tsx
import { expect, test } from 'vitest';
import userEvent from '@testing-library/user-event';
import { render, screen, waitFor } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import PendingApprovalPage from './PendingApprovalPage';

const me = (over: Record<string, unknown>) =>
  http.get('/api/me', () =>
    HttpResponse.json({ id: 1, username: 'u', email: '', orcid: '', displayName: 'U',
      admin: false, state: 'PENDING', ...over }),
  );

test('DISABLED shows the disabled notice and no application form', async () => {
  server.use(me({ state: 'DISABLED' }));
  render(<PendingApprovalPage state="DISABLED" />);
  expect(await screen.findByText(/disabled/i)).toBeInTheDocument();
  expect(screen.queryByLabelText(/email/i)).not.toBeInTheDocument();
});

test('PENDING without email shows the apply form and submits email + message', async () => {
  server.use(me({ state: 'PENDING', email: '' }));
  let put: unknown = null;
  server.use(
    http.put('/api/me/application', async ({ request }) => {
      put = await request.json();
      return HttpResponse.json({ id: 1, username: 'u', email: 'a@example.org', orcid: '',
        displayName: 'U', admin: false, state: 'PENDING' });
    }),
  );
  render(<PendingApprovalPage state="PENDING" />);
  await userEvent.type(await screen.findByLabelText(/email/i), 'a@example.org');
  await userEvent.type(screen.getByLabelText(/message/i), 'I curate beetles');
  await userEvent.click(screen.getByRole('button', { name: /submit/i }));
  await waitFor(() => expect(put).toEqual({ email: 'a@example.org', note: 'I curate beetles' }));
});

test('PENDING with an email shows the waiting screen with an edit affordance', async () => {
  server.use(me({ state: 'PENDING', email: 'a@example.org' }));
  render(<PendingApprovalPage state="PENDING" />);
  expect(await screen.findByText(/awaiting approval/i)).toBeInTheDocument();
  expect(screen.queryByLabelText(/email/i)).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole('button', { name: /edit application/i }));
  expect(await screen.findByLabelText(/email/i)).toBeInTheDocument();
});
```

- [ ] **Step 2: Run it to verify it fails**

Run: `npx vitest run src/auth/PendingApprovalPage.test.tsx`
Expected: FAIL — no form / `submitApplication` missing.

- [ ] **Step 3: Add the `submitApplication` API wrapper**

In `frontend/src/api/auth.ts`, after `updateEmail`:
```ts
// A pending applicant submits their required email + optional message. Returns the updated Me.
export function submitApplication(email: string, note: string): Promise<Me> {
  return api<Me>('/api/me/application', { method: 'PUT', json: { email, note } });
}
```

- [ ] **Step 4: Rework `PendingApprovalPage`**

Replace `frontend/src/auth/PendingApprovalPage.tsx` with:
```tsx
import { Button, Center, Stack, Text, Textarea, TextInput, Title } from '@mantine/core';
import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { notifications } from '@mantine/notifications';
import { messageFor } from '../api/client';
import { logout, submitApplication } from '../api/auth';
import { useMe } from './useMe';

const EMAIL_RE = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;

// Shown by RequireAuth in place of the app chrome when the signed-in user's account is not ACTIVE.
// A brand-new ORCID self-signup lands in PENDING; before an admin can approve them they must
// complete their application by supplying a (required) email and an optional message. DISABLED
// accounts just see a notice. In all cases the API 403s the rest of the app (ActiveUserFilter),
// except /api/me/application which the applicant needs here.
export default function PendingApprovalPage({ state }: { state: string }) {
  const disabled = state === 'DISABLED';
  const { data: me } = useMe();
  const qc = useQueryClient();
  const [editing, setEditing] = useState(false);
  const [email, setEmail] = useState('');
  const [note, setNote] = useState('');

  const hasEmail = !!me?.email;
  const showForm = !disabled && (!hasEmail || editing);

  const apply = useMutation({
    mutationFn: () => submitApplication(email.trim(), note.trim()),
    onSuccess: async () => {
      await qc.invalidateQueries({ queryKey: ['me'] });
      notifications.show({ message: 'Application submitted' });
      setEditing(false);
    },
    onError: (e) =>
      notifications.show({ color: 'red', message: messageFor(e, 'Could not submit application') }),
  });

  const startEdit = () => {
    setEmail(me?.email ?? '');
    setNote('');
    setEditing(true);
  };

  return (
    <Center mih="100vh" p="md">
      <Stack align="center" maw={440} gap="sm">
        {disabled ? (
          <>
            <Title order={3}>Account disabled</Title>
            <Text ta="center" c="dimmed">
              Your account has been disabled by an administrator. Contact an administrator if you
              think this is a mistake.
            </Text>
          </>
        ) : showForm ? (
          <>
            <Title order={3}>Apply for access</Title>
            <Text ta="center" c="dimmed">
              Tell us how to reach you and (optionally) why you'd like access. An administrator will
              review your request.
            </Text>
            <TextInput
              w="100%"
              label="Email"
              required
              value={email}
              error={email.trim() && !EMAIL_RE.test(email.trim()) ? 'Enter a valid email address' : undefined}
              onChange={(e) => setEmail(e.currentTarget.value)}
            />
            <Textarea
              w="100%"
              label="Message"
              description="Optional — a short note for the administrator."
              autosize
              minRows={2}
              value={note}
              onChange={(e) => setNote(e.currentTarget.value)}
            />
            <Button
              onClick={() => apply.mutate()}
              loading={apply.isPending}
              disabled={!EMAIL_RE.test(email.trim())}
            >
              Submit application
            </Button>
          </>
        ) : (
          <>
            <Title order={3}>Awaiting approval</Title>
            <Text ta="center" c="dimmed">
              Your application is registered and awaiting approval by an administrator. You'll be able
              to use Blixa once your account has been approved.
            </Text>
            <Button variant="subtle" onClick={startEdit}>
              Edit application
            </Button>
          </>
        )}
        <Button
          variant="default"
          onClick={async () => {
            await logout();
            window.location.assign('/signin');
          }}
        >
          Sign out
        </Button>
      </Stack>
    </Center>
  );
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `npx vitest run src/auth/PendingApprovalPage.test.tsx`
Expected: PASS (all three tests).

- [ ] **Step 6: Commit**

```bash
git add frontend/src/api/auth.ts frontend/src/auth/PendingApprovalPage.tsx \
  frontend/src/auth/PendingApprovalPage.test.tsx
git commit -m "feat(auth): application form (required email + optional message) on the pending screen"
```

---

### Task 9: Email + message columns on the admin users page (frontend)

**Files:**
- Modify: `frontend/src/api/admin.ts` (`AdminUser` fields)
- Modify: `frontend/src/admin/AdminUsersPage.tsx`
- Test: `frontend/src/admin/AdminUsersPage.test.tsx`

**Interfaces:**
- Consumes: `GET /api/admin/users` items with `email` + `applicationNote` (from Task 6).

- [ ] **Step 1: Extend the existing test (write failing assertions)**

In `frontend/src/admin/AdminUsersPage.test.tsx`, update the `USERS` fixture to carry the new fields and add assertions. Replace the `USERS` array with:
```tsx
const USERS = [
  { id: 1, username: 'me-admin', orcid: '0000-0001-0000-0001', displayName: 'Me Admin', email: 'admin@example.org', applicationNote: null, state: 'ACTIVE', admin: true },
  { id: 2, username: '0000-0003-1111-2222', orcid: '0000-0003-1111-2222', displayName: 'Pending Person', email: 'pending@example.org', applicationNote: 'I curate beetles', state: 'PENDING', admin: false },
  { id: 3, username: 'active-user', orcid: null, displayName: 'Active User', email: null, applicationNote: null, state: 'ACTIVE', admin: false },
];
```
and add a test inside the `describe` block:
```tsx
  it('shows email and application message', async () => {
    render(<AdminUsersPage />);
    expect(await screen.findByText('pending@example.org')).toBeInTheDocument();
    expect(screen.getByText('I curate beetles')).toBeInTheDocument();
  });
```

- [ ] **Step 2: Run it to verify it fails**

Run: `npx vitest run src/admin/AdminUsersPage.test.tsx`
Expected: FAIL — the email/message text isn't rendered.

- [ ] **Step 3: Add the fields to the `AdminUser` type**

In `frontend/src/api/admin.ts`, add to `AdminUser` (after `displayName`):
```ts
  email: string | null;
  applicationNote: string | null;
```

- [ ] **Step 4: Render the two columns**

In `frontend/src/admin/AdminUsersPage.tsx`, add headers after the ORCID header:
```tsx
            <Table.Th>ORCID</Table.Th>
            <Table.Th>Email</Table.Th>
            <Table.Th>Message</Table.Th>
            <Table.Th>Status</Table.Th>
```
and matching cells after the ORCID cell:
```tsx
                <Table.Td>{u.orcid ?? '—'}</Table.Td>
                <Table.Td>{u.email || '—'}</Table.Td>
                <Table.Td>
                  {u.applicationNote ? (
                    <Text size="xs" style={{ maxWidth: 260, whiteSpace: 'pre-wrap' }}>
                      {u.applicationNote}
                    </Text>
                  ) : (
                    '—'
                  )}
                </Table.Td>
                <Table.Td>
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `npx vitest run src/admin/AdminUsersPage.test.tsx`
Expected: PASS.

- [ ] **Step 6: Full frontend gate + commit**

Run: `npx vitest run && npm run build`
Expected: all tests pass; build succeeds.
```bash
git add frontend/src/api/admin.ts frontend/src/admin/AdminUsersPage.tsx \
  frontend/src/admin/AdminUsersPage.test.tsx
git commit -m "feat(admin): email + message columns on the users page"
```

---

## Final verification

- [ ] Backend full suite (Docker up): `cd backend && sdk env && mvn verify`
- [ ] Frontend full suite + type-check: `cd frontend && npx vitest run && npm run build`
- [ ] Manual smoke (optional, needs `COLDP_MAIL_FROM` + `spring.mail.host` for real send; otherwise watch logs for `email suppressed`): ORCID-login a fresh account → apply with email + message → confirm admin sees it on `/admin/users` → approve → confirm applicant gets the approval email (or a log line).
