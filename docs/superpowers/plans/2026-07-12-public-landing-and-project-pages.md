# Public Landing + Public Project Pages Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A public landing page (`/`) with a description, the published-project list, and an environment-aware sign-in; and a public per-project page (`/p/{id}`) with metadata, contributors, metrics, and downloadable release history — backed by a lightweight release mechanism.

**Architecture:** Phase 1 adds an owner-toggled `is_public` flag and a `release` table with an async ColDP-snapshot build that mirrors the existing export pipeline, plus a `ReleaseMetricsService` that snapshots rich metrics into a JSONB column. Phase 2 adds unauthenticated `/api/public/**` + `/api/config` endpoints (SecurityConfig `permitAll`). Phase 3 splits the SPA routes into a public subtree (landing + public project page) and the existing authed subtree (project list moves to `/projects`).

**Tech Stack:** Java 25 (Spring Boot), Postgres 17 (Flyway, Testcontainers ITs), MyBatis, Jackson 3 (`tools.jackson`), React + TypeScript + Mantine 7 + TanStack Query + react-router + Vitest + MSW.

## Global Constraints

- Migration file is **`V22__releases_and_public.sql`** (highest existing is V21).
- The public flag column is **`is_public`** (`public` is reserved in Postgres and Java); Java field `isPublic` with getter `isPublic()` (Jackson serializes it as JSON `"public"`).
- Release status literals: **`BUILDING` / `READY` / `FAILED`**.
- Release files live under **`coldp.release.dir`** (default `${java.io.tmpdir}/coldp-releases`), a **separate** directory from `coldp.export.dir`, and there is **no retention sweep** for releases (they persist).
- Publishing/deleting a release and toggling public are **owner-only** (`Role.OWNER.dbValue()` == `"owner"`).
- Public contributors = **all non-viewer members** (owner/editor); never emails, never viewers.
- Public endpoints expose **only `is_public=true` projects**; everything else 404.
- `orcidEnabled` = the `spring.security.oauth2.client.registration.orcid.client-id` property is **not** the sentinel `"unconfigured"`.
- Metrics JSON key for child-entity counts is **`supplementary`**.
- Build/test: backend `cd backend && JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw -q -Dtest=<Class> test`; frontend `cd frontend && npx tsc -b && npx vitest run`. Commit directly to `main`; never stage `todo.md` or `blixa.svg`.

---

## Phase 1 — Releases + public flag + editor UI

### Task 1: Migration + `is_public` flag + owner toggle endpoint

**Files:**
- Create: `backend/src/main/resources/db/migration/V22__releases_and_public.sql`
- Modify: `backend/src/main/java/org/catalogueoflife/editor/project/Project.java`
- Modify: `backend/src/main/java/org/catalogueoflife/editor/project/ProjectMapper.java`
- Modify: `backend/src/main/java/org/catalogueoflife/editor/project/ProjectService.java`
- Modify: `backend/src/main/java/org/catalogueoflife/editor/project/ProjectController.java`
- Test: `backend/src/test/java/org/catalogueoflife/editor/project/ProjectPublicApiIT.java`

**Interfaces:**
- Produces: `project.is_public` column + `release` table (used by Tasks 2-3, 6-7); `Project.isPublic()`; `ProjectService.setPublic(int userId, int projectId, boolean isPublic)`; `PUT /api/projects/{id}/public {public:boolean}`.

- [ ] **Step 1: Write the migration**

Create `backend/src/main/resources/db/migration/V22__releases_and_public.sql`:

```sql
-- Owner-toggled public visibility. `is_public` (not `public`, a reserved word).
ALTER TABLE project ADD COLUMN is_public BOOLEAN NOT NULL DEFAULT false;

-- A release is an immutable, persisted ColDP snapshot of a project. Files live under
-- coldp.release.dir (separate from exports, never retention-swept). name_usage_count is a column
-- for the public list; the rest of the metrics snapshot is the `metrics` JSONB.
CREATE TABLE release (
  id               INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  project_id       INTEGER NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  version          TEXT NOT NULL,
  notes            TEXT,
  status           TEXT NOT NULL,                -- BUILDING | READY | FAILED
  name_usage_count INTEGER,
  metrics          JSONB,
  file_path        TEXT,
  file_name        TEXT,
  file_size        BIGINT,
  error            TEXT,
  created_by       INTEGER REFERENCES app_user(id) ON DELETE SET NULL,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX release_project_idx ON release (project_id, created_at DESC, id DESC);
```

- [ ] **Step 2: Write the failing test**

Create `backend/src/test/java/org/catalogueoflife/editor/project/ProjectPublicApiIT.java`:

```java
package org.catalogueoflife.editor.project;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@AutoConfigureMockMvc
@WithMockUser(username = "pubOwner")
class ProjectPublicApiIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ProjectMemberMapper members;
  @Autowired ObjectMapper json;

  private void ensureUser(String u) {
    if (users.requireByUsernameOrNull(u) == null) users.createLocal(u, "pw", u);
  }

  private int createProject(String owner) throws Exception {
    ensureUser(owner);
    String body = mvc.perform(post("/api/projects").with(csrf()).with(user(owner))
            .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Pub\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.public").value(false))   // default false, serialized as "public"
        .andReturn().getResponse().getContentAsString();
    return json.readTree(body).get("id").asInt();
  }

  @Test
  void ownerTogglesPublic() throws Exception {
    int pid = createProject("pubOwner");
    mvc.perform(put("/api/projects/" + pid + "/public").with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"public\":true}"))
       .andExpect(status().isOk());
    mvc.perform(get("/api/projects/" + pid))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.public").value(true));
  }

  @Test
  void nonOwnerCannotTogglePublic() throws Exception {
    int pid = createProject("pubOwner");
    ensureUser("pubEditor");
    AppUser ed = users.requireByUsernameOrNull("pubEditor");
    members.upsert(new ProjectMember(pid, ed.getId(), Role.EDITOR.dbValue()));
    mvc.perform(put("/api/projects/" + pid + "/public").with(csrf()).with(user("pubEditor"))
            .contentType(MediaType.APPLICATION_JSON).content("{\"public\":true}"))
       .andExpect(status().isForbidden());
  }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw -q -Dtest=ProjectPublicApiIT test`
Expected: FAIL — `$.public` missing / no `PUT …/public` endpoint.

- [ ] **Step 4: Add the `isPublic` field to `Project`**

In `Project.java`, add the field + accessors alongside the others:

```java
  private boolean isPublic;

  public boolean isPublic() { return isPublic; }
  public void setPublic(boolean isPublic) { this.isPublic = isPublic; }
```

(MyBatis `map-underscore-to-camel-case` maps `is_public` → `isPublic`; Jackson serializes `isPublic()` as the JSON property `"public"`.)

- [ ] **Step 5: Add the mapper update**

In `ProjectMapper.java`, add:

```java
  @org.apache.ibatis.annotations.Update("UPDATE project SET is_public = #{isPublic}, updated_at = now() WHERE id = #{id}")
  void updatePublic(@org.apache.ibatis.annotations.Param("id") int id,
      @org.apache.ibatis.annotations.Param("isPublic") boolean isPublic);
```

- [ ] **Step 6: Add the owner-gated service method**

In `ProjectService.java`, add (reuse the existing `requireRole` + `Role.OWNER`):

```java
  @Transactional
  public void setPublic(int userId, int projectId, boolean isPublic) {
    String role = requireRole(userId, projectId);
    if (!role.equals(Role.OWNER.dbValue())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "owner required");
    }
    projects.updatePublic(projectId, isPublic);
  }
```

- [ ] **Step 7: Add the controller endpoint**

In `ProjectController.java`, add (mirror the existing `@PutMapping` style; the body is a small record):

```java
  public record PublicRequest(boolean isPublic) {}

  @org.springframework.web.bind.annotation.PutMapping("/{id}/public")
  public void setPublic(@PathVariable int id, @RequestBody java.util.Map<String, Boolean> body) {
    int uid = currentUser.require().getId();
    service.setPublic(uid, id, Boolean.TRUE.equals(body.get("public")));
  }
```

(Using a `Map<String,Boolean>` avoids a `public`-named record component; the JSON key is `"public"`.)

- [ ] **Step 8: Run the test to verify it passes**

Run: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw -q -Dtest=ProjectPublicApiIT test`
Expected: PASS (2 tests).

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/resources/db/migration/V22__releases_and_public.sql backend/src/main/java/org/catalogueoflife/editor/project/{Project,ProjectMapper,ProjectService,ProjectController}.java backend/src/test/java/org/catalogueoflife/editor/project/ProjectPublicApiIT.java
git commit -m "feat(project): is_public flag + release table migration + owner public toggle"
```

---

### Task 2: Release persistence + async ColDP build

**Files:**
- Create: `backend/src/main/java/org/catalogueoflife/editor/release/Release.java`
- Create: `backend/src/main/java/org/catalogueoflife/editor/release/ReleaseMapper.java`
- Create: `backend/src/main/java/org/catalogueoflife/editor/release/ReleaseAsyncConfig.java`
- Create: `backend/src/main/java/org/catalogueoflife/editor/release/ReleaseService.java`
- Create: `backend/src/main/java/org/catalogueoflife/editor/release/ReleaseRecovery.java`
- Create: `backend/src/main/java/org/catalogueoflife/editor/release/ReleaseController.java`
- Create: `backend/src/main/java/org/catalogueoflife/editor/release/dto/ReleaseResponse.java`
- Test: `backend/src/test/java/org/catalogueoflife/editor/release/ReleaseApiIT.java`

**Interfaces:**
- Consumes: `ColdpWriter.write(int projectId, Path targetZip) -> ColdpWriter.Counts` (fields `nameUsageCount()`, `referenceCount()`); `ProjectService.requireRole`; `Role.OWNER.dbValue()`; `CurrentUser.require().getId()`.
- Produces: `ReleaseService.publish(int userId,int projectId,String version,String notes) -> ReleaseResponse`; `ReleaseService.list(int userId,int projectId)`; `ReleaseService.delete(int userId,int projectId,int releaseId)`; `ReleaseMapper` (used by Task 3 + Phase 2); `ReleaseService.releaseFileForPublic(...)` added in Task 7.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/org/catalogueoflife/editor/release/ReleaseApiIT.java`:

```java
package org.catalogueoflife.editor.release;

import static org.awaitility.Awaitility.await; // if unavailable, use the bounded-loop pattern from ImportApiIT
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserService;
import org.catalogueoflife.editor.project.ProjectMember;
import org.catalogueoflife.editor.project.ProjectMemberMapper;
import org.catalogueoflife.editor.project.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@AutoConfigureMockMvc
@WithMockUser(username = "relOwner")
class ReleaseApiIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ProjectMemberMapper members;
  @Autowired ObjectMapper json;

  private void ensureUser(String u) {
    if (users.requireByUsernameOrNull(u) == null) users.createLocal(u, "pw", u);
  }

  private int project(String owner) throws Exception {
    ensureUser(owner);
    String b = mvc.perform(post("/api/projects").with(csrf()).with(user(owner))
            .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Rel\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    int pid = json.readTree(b).get("id").asInt();
    mvc.perform(post("/api/projects/" + pid + "/usages").with(csrf()).with(user(owner))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scientificName\":\"Panthera\",\"rank\":\"genus\",\"status\":\"ACCEPTED\"}"))
       .andExpect(status().isCreated());
    return pid;
  }

  @Test
  void ownerPublishesReleaseToReady() throws Exception {
    int pid = project("relOwner");
    String started = mvc.perform(post("/api/projects/" + pid + "/releases").with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"version\":\"1.0\",\"notes\":\"first\"}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("BUILDING"))
        .andReturn().getResponse().getContentAsString();
    int rid = json.readTree(started).get("id").asInt();

    await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
        mvc.perform(get("/api/projects/" + pid + "/releases"))
           .andExpect(jsonPath("$[0].id").value(rid))
           .andExpect(jsonPath("$[0].status").value("READY"))
           .andExpect(jsonPath("$[0].version").value("1.0"))
           .andExpect(jsonPath("$[0].nameUsageCount").value(1))
           .andExpect(jsonPath("$[0].fileName").isNotEmpty()));
  }

  @Test
  void nonOwnerCannotPublish() throws Exception {
    int pid = project("relOwner");
    ensureUser("relEditor");
    AppUser ed = users.requireByUsernameOrNull("relEditor");
    members.upsert(new ProjectMember(pid, ed.getId(), Role.EDITOR.dbValue()));
    mvc.perform(post("/api/projects/" + pid + "/releases").with(csrf()).with(user("relEditor"))
            .contentType(MediaType.APPLICATION_JSON).content("{\"version\":\"1.0\"}"))
       .andExpect(status().isForbidden());
  }

  @Test
  void ownerDeletesRelease() throws Exception {
    int pid = project("relOwner");
    String started = mvc.perform(post("/api/projects/" + pid + "/releases").with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"version\":\"1.0\"}"))
        .andReturn().getResponse().getContentAsString();
    int rid = json.readTree(started).get("id").asInt();
    await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
        mvc.perform(get("/api/projects/" + pid + "/releases"))
           .andExpect(jsonPath("$[0].status").value("READY")));
    mvc.perform(delete("/api/projects/" + pid + "/releases/" + rid).with(csrf()))
       .andExpect(status().isOk());
    mvc.perform(get("/api/projects/" + pid + "/releases"))
       .andExpect(jsonPath("$.length()").value(0));
  }
}
```

Note: confirm Awaitility is on the test classpath (grep `org.awaitility` in `backend/pom.xml`); if absent, copy the exact bounded-poll helper from `backend/src/test/java/org/catalogueoflife/editor/coldp/imprt/ImportApiIT.java` instead of `await()`.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw -q -Dtest=ReleaseApiIT test`
Expected: FAIL — release classes/endpoints don't exist.

- [ ] **Step 3: Create the `Release` model**

`backend/src/main/java/org/catalogueoflife/editor/release/Release.java`:

```java
package org.catalogueoflife.editor.release;

import java.time.OffsetDateTime;

public class Release {
  private Integer id;
  private Integer projectId;
  private String version;
  private String notes;
  private String status;            // BUILDING | READY | FAILED
  private Integer nameUsageCount;
  private String metrics;           // JSON string (jsonb)
  private String filePath;
  private String fileName;
  private Long fileSize;
  private String error;
  private Integer createdBy;
  private OffsetDateTime createdAt;

  public Integer getId() { return id; }
  public void setId(Integer id) { this.id = id; }
  public Integer getProjectId() { return projectId; }
  public void setProjectId(Integer projectId) { this.projectId = projectId; }
  public String getVersion() { return version; }
  public void setVersion(String version) { this.version = version; }
  public String getNotes() { return notes; }
  public void setNotes(String notes) { this.notes = notes; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
  public Integer getNameUsageCount() { return nameUsageCount; }
  public void setNameUsageCount(Integer nameUsageCount) { this.nameUsageCount = nameUsageCount; }
  public String getMetrics() { return metrics; }
  public void setMetrics(String metrics) { this.metrics = metrics; }
  public String getFilePath() { return filePath; }
  public void setFilePath(String filePath) { this.filePath = filePath; }
  public String getFileName() { return fileName; }
  public void setFileName(String fileName) { this.fileName = fileName; }
  public Long getFileSize() { return fileSize; }
  public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
  public String getError() { return error; }
  public void setError(String error) { this.error = error; }
  public Integer getCreatedBy() { return createdBy; }
  public void setCreatedBy(Integer createdBy) { this.createdBy = createdBy; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
```

- [ ] **Step 4: Create the `ReleaseMapper`**

`backend/src/main/java/org/catalogueoflife/editor/release/ReleaseMapper.java`:

```java
package org.catalogueoflife.editor.release;

import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ReleaseMapper {

  @Insert("INSERT INTO release (project_id, version, notes, status, created_by) "
      + "VALUES (#{projectId}, #{version}, #{notes}, 'BUILDING', #{createdBy})")
  @Options(useGeneratedKeys = true, keyProperty = "id")
  void insertBuilding(Release r);

  @Update("UPDATE release SET status = 'READY', name_usage_count = #{nameUsageCount}, "
      + "metrics = #{metrics}::jsonb, file_path = #{filePath}, file_name = #{fileName}, "
      + "file_size = #{fileSize} WHERE id = #{id} AND status = 'BUILDING'")
  int ready(@Param("id") int id, @Param("nameUsageCount") int nameUsageCount,
      @Param("metrics") String metrics, @Param("filePath") String filePath,
      @Param("fileName") String fileName, @Param("fileSize") long fileSize);

  @Update("UPDATE release SET status = 'FAILED', error = #{error} WHERE id = #{id} AND status = 'BUILDING'")
  int fail(@Param("id") int id, @Param("error") String error);

  @Select("SELECT * FROM release WHERE id = #{id}")
  Release findById(@Param("id") int id);

  @Select("SELECT * FROM release WHERE project_id = #{projectId} ORDER BY created_at DESC, id DESC")
  List<Release> findByProject(@Param("projectId") int projectId);

  // Latest READY release of a project (for the previous-release metrics boundary + public list/page).
  @Select("SELECT * FROM release WHERE project_id = #{projectId} AND status = 'READY' "
      + "ORDER BY created_at DESC, id DESC LIMIT 1")
  Release findLatestReady(@Param("projectId") int projectId);

  // The created_at of the previous READY release (metrics boundary); null if none yet.
  @Select("SELECT max(created_at) FROM release WHERE project_id = #{projectId} AND status = 'READY'")
  java.time.OffsetDateTime latestReadyCreatedAt(@Param("projectId") int projectId);

  @Select("SELECT * FROM release WHERE project_id = #{projectId} AND status = 'READY' "
      + "ORDER BY created_at DESC, id DESC")
  List<Release> findReadyByProject(@Param("projectId") int projectId);

  @Delete("DELETE FROM release WHERE id = #{id}")
  void delete(@Param("id") int id);

  @Update("UPDATE release SET status = 'FAILED', error = 'interrupted by restart' WHERE status = 'BUILDING'")
  int failStaleBuilding();
}
```

- [ ] **Step 5: Create `ReleaseAsyncConfig`**

`backend/src/main/java/org/catalogueoflife/editor/release/ReleaseAsyncConfig.java` (mirrors `ExportAsyncConfig`; do NOT add `@EnableScheduling`/`@EnableAsync` again — `ExportAsyncConfig` already enables both app-wide):

```java
package org.catalogueoflife.editor.release;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class ReleaseAsyncConfig {

  public static final String EXECUTOR_BEAN = "releaseTaskExecutor";

  @Bean(EXECUTOR_BEAN)
  public Executor releaseTaskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.setQueueCapacity(50);
    executor.setThreadNamePrefix("coldp-release-");
    executor.initialize();
    return executor;
  }
}
```

- [ ] **Step 6: Create `ReleaseService`** (metrics wired in Task 3 — for now pass an empty `{}`)

`backend/src/main/java/org/catalogueoflife/editor/release/ReleaseService.java`:

```java
package org.catalogueoflife.editor.release;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.catalogueoflife.editor.coldp.export.ColdpWriter;
import org.catalogueoflife.editor.project.Project;
import org.catalogueoflife.editor.project.ProjectMapper;
import org.catalogueoflife.editor.project.ProjectService;
import org.catalogueoflife.editor.project.Role;
import org.catalogueoflife.editor.release.dto.ReleaseResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReleaseService {

  private static final Logger log = LoggerFactory.getLogger(ReleaseService.class);

  private final ReleaseMapper releases;
  private final ProjectService projectService;
  private final ProjectMapper projects;
  private final ColdpWriter writer;
  private final Path releaseDir;

  @Autowired @Lazy private ReleaseService self;

  public ReleaseService(ReleaseMapper releases, ProjectService projectService, ProjectMapper projects,
      ColdpWriter writer, @Value("${coldp.release.dir:${java.io.tmpdir}/coldp-releases}") String releaseDir) {
    this.releases = releases;
    this.projectService = projectService;
    this.projects = projects;
    this.writer = writer;
    this.releaseDir = Path.of(releaseDir);
    try {
      Files.createDirectories(this.releaseDir);
    } catch (IOException e) {
      throw new java.io.UncheckedIOException("failed to create release dir " + releaseDir, e);
    }
  }

  public ReleaseResponse publish(int userId, int projectId, String version, String notes) {
    requireOwner(userId, projectId);
    if (version == null || version.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "version is required");
    }
    Release r = new Release();
    r.setProjectId(projectId);
    r.setVersion(version.trim());
    r.setNotes(notes);
    r.setCreatedBy(userId);
    releases.insertBuilding(r);
    try {
      self.build(projectId, r.getId());
    } catch (TaskRejectedException e) {
      releases.fail(r.getId(), "release service busy — try again later");
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "release service busy, try again later");
    }
    return ReleaseResponse.of(releases.findById(r.getId()));
  }

  @Async(ReleaseAsyncConfig.EXECUTOR_BEAN)
  public void build(int projectId, int releaseId) {
    Path target = releaseDir.resolve(releaseId + ".zip");
    try {
      ColdpWriter.Counts counts = writer.write(projectId, target);
      String metrics = "{}"; // Task 3 replaces this with the rich snapshot
      releases.ready(releaseId, counts.nameUsageCount(), metrics, target.toString(),
          downloadFileName(projectId, releaseId), Files.size(target));
    } catch (Exception e) {
      log.warn("release build {} failed for project {}: {}", releaseId, projectId, e.getMessage(), e);
      releases.fail(releaseId, e.getMessage());
      try { Files.deleteIfExists(target); } catch (IOException ignored) { }
    }
  }

  public List<ReleaseResponse> list(int userId, int projectId) {
    projectService.requireRole(userId, projectId); // any member may view the history
    return releases.findByProject(projectId).stream().map(ReleaseResponse::of).toList();
  }

  public void delete(int userId, int projectId, int releaseId) {
    requireOwner(userId, projectId);
    Release r = releases.findById(releaseId);
    if (r == null || !r.getProjectId().equals(projectId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "release not found");
    }
    releases.delete(releaseId);
    // delete the file only after the row-delete transaction commits
    String path = r.getFilePath();
    if (path != null) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override public void afterCommit() {
          try { Files.deleteIfExists(Path.of(path)); } catch (IOException ignored) { }
        }
      });
    }
  }

  private void requireOwner(int userId, int projectId) {
    if (!Role.OWNER.dbValue().equals(projectService.requireRole(userId, projectId))) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "owner required");
    }
  }

  private String downloadFileName(int projectId, int releaseId) {
    Project p = projects.findById(projectId);
    String alias = p != null && p.getAlias() != null && !p.getAlias().isBlank()
        ? p.getAlias() : String.valueOf(projectId);
    Release r = releases.findById(releaseId);
    String v = r != null && r.getVersion() != null ? r.getVersion().replaceAll("[^A-Za-z0-9._-]", "_") : "release";
    return alias + "-" + v + "-coldp.zip";
  }
}
```

Note: `delete` runs without an explicit `@Transactional`; the `registerSynchronization` requires an active transaction. Add `@org.springframework.transaction.annotation.Transactional` to `delete` so the synchronization registers and the file delete happens post-commit.

- [ ] **Step 7: Create `ReleaseRecovery`** (mirrors `ExportRunRecovery`)

`backend/src/main/java/org/catalogueoflife/editor/release/ReleaseRecovery.java`:

```java
package org.catalogueoflife.editor.release;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class ReleaseRecovery {
  private static final Logger log = LoggerFactory.getLogger(ReleaseRecovery.class);
  private final ReleaseMapper releases;

  public ReleaseRecovery(ReleaseMapper releases) { this.releases = releases; }

  @EventListener(ApplicationReadyEvent.class)
  public void onReady() {
    int n = releases.failStaleBuilding();
    if (n > 0) log.warn("Reconciled {} release row(s) left BUILDING by a previous instance as FAILED", n);
    else log.info("No stale BUILDING release rows found at startup");
  }
}
```

- [ ] **Step 8: Create `ReleaseResponse`**

`backend/src/main/java/org/catalogueoflife/editor/release/dto/ReleaseResponse.java`:

```java
package org.catalogueoflife.editor.release.dto;

import java.time.OffsetDateTime;
import org.catalogueoflife.editor.release.Release;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// Editor-facing release. `metrics` is embedded as parsed JSON (not a quoted string) so the UI reads
// it directly; filePath is intentionally omitted.
public record ReleaseResponse(
    Integer id, Integer projectId, String version, String notes, String status,
    Integer nameUsageCount, JsonNode metrics, String fileName, Long fileSize,
    String error, OffsetDateTime createdAt) {

  private static final ObjectMapper JSON = new ObjectMapper();

  public static ReleaseResponse of(Release r) {
    JsonNode m = null;
    if (r.getMetrics() != null && !r.getMetrics().isBlank()) {
      try { m = JSON.readTree(r.getMetrics()); } catch (Exception ignored) { }
    }
    return new ReleaseResponse(r.getId(), r.getProjectId(), r.getVersion(), r.getNotes(),
        r.getStatus(), r.getNameUsageCount(), m, r.getFileName(), r.getFileSize(),
        r.getError(), r.getCreatedAt());
  }
}
```

- [ ] **Step 9: Create `ReleaseController`**

`backend/src/main/java/org/catalogueoflife/editor/release/ReleaseController.java`:

```java
package org.catalogueoflife.editor.release;

import java.util.List;
import java.util.Map;
import org.catalogueoflife.editor.auth.CurrentUser;
import org.catalogueoflife.editor.release.dto.ReleaseResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{pid}/releases")
public class ReleaseController {

  private final ReleaseService service;
  private final CurrentUser currentUser;

  public ReleaseController(ReleaseService service, CurrentUser currentUser) {
    this.service = service;
    this.currentUser = currentUser;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.ACCEPTED)
  public ReleaseResponse publish(@PathVariable int pid, @RequestBody Map<String, String> body) {
    int uid = currentUser.require().getId();
    return service.publish(uid, pid, body.get("version"), body.get("notes"));
  }

  @GetMapping
  public List<ReleaseResponse> list(@PathVariable int pid) {
    int uid = currentUser.require().getId();
    return service.list(uid, pid);
  }

  @DeleteMapping("/{rid}")
  public void delete(@PathVariable int pid, @PathVariable int rid) {
    int uid = currentUser.require().getId();
    service.delete(uid, pid, rid);
  }
}
```

- [ ] **Step 10: Add `@Transactional` to `delete`**

In `ReleaseService.delete`, add `@org.springframework.transaction.annotation.Transactional` above the method (so the post-commit file delete registers).

- [ ] **Step 11: Run the test to verify it passes**

Run: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw -q -Dtest=ReleaseApiIT test`
Expected: PASS (3 tests).

- [ ] **Step 12: Commit**

```bash
git add backend/src/main/java/org/catalogueoflife/editor/release backend/src/test/java/org/catalogueoflife/editor/release/ReleaseApiIT.java
git commit -m "feat(release): async ColDP-snapshot release build (publish/list/delete, owner-gated)"
```

---

### Task 3: `ReleaseMetricsService` — rich metrics snapshot

**Files:**
- Create: `backend/src/main/java/org/catalogueoflife/editor/release/ReleaseMetricsService.java`
- Create: `backend/src/main/java/org/catalogueoflife/editor/release/ReleaseMetricsMapper.java`
- Modify: `backend/src/main/java/org/catalogueoflife/editor/release/ReleaseService.java` (call the metrics service in `build`)
- Test: `backend/src/test/java/org/catalogueoflife/editor/release/ReleaseMetricsIT.java`

**Interfaces:**
- Consumes: `ReleaseMapper.latestReadyCreatedAt(projectId)`.
- Produces: `ReleaseMetricsService.compute(int projectId, OffsetDateTime sinceExclusiveOrNull) -> String` (a JSON string with keys `acceptedByRank`, `synonymsByRank`, `supplementary`, `changesSinceLastRelease`, `contributions`).

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/org/catalogueoflife/editor/release/ReleaseMetricsIT.java`:

```java
package org.catalogueoflife.editor.release;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.name.dto.CreateNameUsageRequest;
import org.catalogueoflife.editor.name.NameUsageService;
import org.catalogueoflife.editor.project.ProjectService;
import org.catalogueoflife.editor.project.dto.CreateProjectRequest;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ReleaseMetricsIT extends AbstractPostgresIT {

  @Autowired ReleaseMetricsService metrics;
  @Autowired ProjectService projects;
  @Autowired NameUsageService usages;
  @Autowired AppUserService users;
  @Autowired ObjectMapper json;

  @Test
  void computesRankBreakdownAndChangeCounts() throws Exception {
    AppUser u = users.createLocal("mUser", "pw", "M User");
    var p = projects.create(u.getId(), new CreateProjectRequest("Metrics", "zoological"));
    int pid = p.getId();
    // 1 accepted genus + 1 accepted species + 1 synonym species
    usages.create(u.getId(), pid, new CreateNameUsageRequest("Aus", null, "genus", "ACCEPTED",
        null, null, null, null, null, null, null, null, null, null, null, null, null));
    usages.create(u.getId(), pid, new CreateNameUsageRequest("Aus bus", null, "species", "ACCEPTED",
        null, null, null, null, null, null, null, null, null, null, null, null, null));
    usages.create(u.getId(), pid, new CreateNameUsageRequest("Aus cus", null, "species", "SYNONYM",
        null, null, null, null, null, null, null, null, null, null, null, null, null));

    JsonNode m = json.readTree(metrics.compute(pid, null));
    assertThat(m.get("acceptedByRank").get("genus").asInt()).isEqualTo(1);
    assertThat(m.get("acceptedByRank").get("species").asInt()).isEqualTo(1);
    assertThat(m.get("synonymsByRank").get("species").asInt()).isEqualTo(1);
    assertThat(m.get("supplementary").get("reference").asInt()).isEqualTo(0);
    // 3 create changes since project start (since = null)
    assertThat(m.get("changesSinceLastRelease").asInt()).isGreaterThanOrEqualTo(3);
    // one contributor with >=3 edits
    assertThat(m.get("contributions").get(0).get("count").asInt()).isGreaterThanOrEqualTo(3);
    assertThat(m.get("contributions").get(0).get("name").asString()).isEqualTo("M User");
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw -q -Dtest=ReleaseMetricsIT test`
Expected: FAIL — `ReleaseMetricsService` doesn't exist.

- [ ] **Step 3: Create `ReleaseMetricsMapper`**

`backend/src/main/java/org/catalogueoflife/editor/release/ReleaseMetricsMapper.java`:

```java
package org.catalogueoflife.editor.release;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ReleaseMetricsMapper {

  // rank -> count for a given status set. Returns rows {rank, cnt}; the service folds to a map.
  @Select({"<script>",
      "SELECT rank AS rank, count(*) AS cnt FROM name_usage",
      "WHERE project_id = #{projectId} AND status IN",
      "<foreach item='s' collection='statuses' open='(' separator=',' close=')'>#{s}</foreach>",
      "GROUP BY rank",
      "</script>"})
  List<Map<String, Object>> countByRank(@Param("projectId") int projectId,
      @Param("statuses") List<String> statuses);

  @Select("SELECT count(*) FROM name_usage WHERE project_id = #{projectId} AND status = 'ACCEPTED'")
  int acceptedTotal(@Param("projectId") int projectId);

  @Select("SELECT count(*) FROM vernacular   WHERE project_id = #{projectId}") int vernacular(@Param("projectId") int p);
  @Select("SELECT count(*) FROM distribution WHERE project_id = #{projectId}") int distribution(@Param("projectId") int p);
  @Select("SELECT count(*) FROM media        WHERE project_id = #{projectId}") int media(@Param("projectId") int p);
  @Select("SELECT count(*) FROM type_material WHERE project_id = #{projectId}") int typeMaterial(@Param("projectId") int p);
  @Select("SELECT count(*) FROM name_relation WHERE project_id = #{projectId}") int nameRelation(@Param("projectId") int p);
  @Select("SELECT count(*) FROM property     WHERE project_id = #{projectId}") int property(@Param("projectId") int p);
  @Select("SELECT count(*) FROM estimate     WHERE project_id = #{projectId}") int estimate(@Param("projectId") int p);
  @Select("SELECT count(*) FROM reference    WHERE project_id = #{projectId}") int reference(@Param("projectId") int p);

  @Select("SELECT count(*) FROM change WHERE project_id = #{projectId} "
      + "AND (#{since} IS NULL OR at > #{since})")
  int changesSince(@Param("projectId") int projectId, @Param("since") OffsetDateTime since);

  // user_id, name (display_name -> username fallback), orcid, count — since the boundary.
  @Select("SELECT c.user_id AS userId, coalesce(u.display_name, u.username) AS name, u.orcid AS orcid, "
      + "count(*) AS cnt FROM change c LEFT JOIN app_user u ON u.id = c.user_id "
      + "WHERE c.project_id = #{projectId} AND (#{since} IS NULL OR c.at > #{since}) "
      + "GROUP BY c.user_id, u.display_name, u.username, u.orcid ORDER BY count(*) DESC")
  List<Map<String, Object>> contributionsSince(@Param("projectId") int projectId,
      @Param("since") OffsetDateTime since);
}
```

- [ ] **Step 4: Create `ReleaseMetricsService`**

`backend/src/main/java/org/catalogueoflife/editor/release/ReleaseMetricsService.java`:

```java
package org.catalogueoflife.editor.release;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class ReleaseMetricsService {

  private static final List<String> SYNONYM_STATUSES = List.of("SYNONYM", "MISAPPLIED");

  private final ReleaseMetricsMapper m;
  private final ObjectMapper json;

  public ReleaseMetricsService(ReleaseMetricsMapper m, ObjectMapper json) {
    this.m = m;
    this.json = json;
  }

  // Returns the metrics snapshot JSON. `since` is the previous READY release's created_at (null for
  // the first release -> counts all changes).
  public String compute(int projectId, OffsetDateTime since) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("acceptedByRank", byRank(projectId, List.of("ACCEPTED")));
    out.put("synonymsByRank", byRank(projectId, SYNONYM_STATUSES));
    Map<String, Integer> supp = new LinkedHashMap<>();
    supp.put("vernacular", m.vernacular(projectId));
    supp.put("distribution", m.distribution(projectId));
    supp.put("media", m.media(projectId));
    supp.put("typeMaterial", m.typeMaterial(projectId));
    supp.put("nameRelation", m.nameRelation(projectId));
    supp.put("property", m.property(projectId));
    supp.put("estimate", m.estimate(projectId));
    supp.put("reference", m.reference(projectId));
    out.put("supplementary", supp);
    out.put("changesSinceLastRelease", m.changesSince(projectId, since));
    out.put("contributions", m.contributionsSince(projectId, since).stream().map(row -> {
      Map<String, Object> c = new LinkedHashMap<>();
      c.put("userId", row.get("userId"));
      c.put("name", row.get("name"));
      c.put("orcid", row.get("orcid"));
      c.put("count", ((Number) row.get("cnt")).intValue());
      return c;
    }).toList());
    return json.writeValueAsString(out);
  }

  private Map<String, Integer> byRank(int projectId, List<String> statuses) {
    Map<String, Integer> map = new LinkedHashMap<>();
    for (Map<String, Object> row : m.countByRank(projectId, statuses)) {
      map.put(String.valueOf(row.get("rank")), ((Number) row.get("cnt")).intValue());
    }
    return map;
  }
}
```

- [ ] **Step 5: Wire the metrics into `ReleaseService.build`**

In `ReleaseService.java`: add a `ReleaseMetricsService metrics` constructor param + field, and in `build(...)` replace `String metrics = "{}";` with:

```java
      OffsetDateTime since = releases.latestReadyCreatedAt(projectId);
      String metrics = this.metrics.compute(projectId, since);
```

(Add `import java.time.OffsetDateTime;`. Rename the local to avoid clashing with the field — e.g. keep the field `metrics` and the local `metricsJson`, passing `metricsJson` to `releases.ready(...)`.)

- [ ] **Step 6: Run the tests to verify they pass**

Run: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw -q -Dtest=ReleaseMetricsIT,ReleaseApiIT test`
Expected: PASS (metrics IT + the 3 release ITs, now with real metrics).

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/org/catalogueoflife/editor/release/ReleaseMetrics*.java backend/src/main/java/org/catalogueoflife/editor/release/ReleaseService.java backend/src/test/java/org/catalogueoflife/editor/release/ReleaseMetricsIT.java
git commit -m "feat(release): rich metrics snapshot (by-rank, supplementary, changes + contributions)"
```

---

### Task 4: Editor UI — Public toggle + Releases section

**Files:**
- Create: `frontend/src/api/releases.ts`
- Modify: `frontend/src/api/projects.ts` (add `setPublic`)
- Modify: `frontend/src/api/types.ts` (add `public` to `Project`)
- Modify: `frontend/src/projects/ProjectMetadataPage.tsx` (Public toggle + Releases section)
- Test: `frontend/src/projects/ProjectMetadataPage.test.tsx` (add cases)

**Interfaces:**
- Consumes: `PUT /api/projects/{id}/public`, `POST/GET/DELETE /api/projects/{id}/releases`.

- [ ] **Step 1: Write the failing test** — add to `ProjectMetadataPage.test.tsx` (mirror its existing MSW style):

```tsx
test('owner can toggle public and publish a release', async () => {
  // reuse the file's existing project/owner MSW setup helper; then:
  let published = false;
  server.use(
    http.get('/api/projects/9/releases', () =>
      HttpResponse.json(published
        ? [{ id: 1, projectId: 9, version: '1.0', status: 'READY', nameUsageCount: 3,
             metrics: {}, fileName: 'x.zip', fileSize: 10, createdAt: '2026-07-12T00:00:00Z' }]
        : [])),
    http.post('/api/projects/9/releases', async () => { published = true;
      return new HttpResponse(JSON.stringify({ id: 1, projectId: 9, version: '1.0', status: 'BUILDING' }),
        { status: 202, headers: { 'content-type': 'application/json' } }); }),
    http.put('/api/projects/9/public', () => new HttpResponse(null, { status: 200 })),
  );
  // render ProjectMetadataPage for project 9 as owner (see the file's existing render helper),
  // click the Public switch, then the Publish-release button, and assert the release row appears.
});
```

(Adapt to the file's existing render/setup helpers and project id; the point is: the Public switch calls `setPublic`, and Publish calls `POST …/releases` then the history shows the READY release.)

- [ ] **Step 2: Run it to verify it fails**

Run: `cd frontend && npx vitest run src/projects/ProjectMetadataPage.test.tsx`
Expected: FAIL — no Public toggle / Releases section.

- [ ] **Step 3: Create `api/releases.ts`**

```ts
import { api } from './client';

export interface Release {
  id: number;
  projectId: number;
  version: string;
  notes: string | null;
  status: 'BUILDING' | 'READY' | 'FAILED';
  nameUsageCount: number | null;
  metrics: unknown | null;
  fileName: string | null;
  fileSize: number | null;
  error: string | null;
  createdAt: string | null;
}

export function listReleases(pid: number): Promise<Release[]> {
  return api<Release[]>(`/api/projects/${pid}/releases`);
}
export function publishRelease(pid: number, version: string, notes?: string): Promise<Release> {
  return api<Release>(`/api/projects/${pid}/releases`, { method: 'POST', json: { version, notes } });
}
export function deleteRelease(pid: number, rid: number): Promise<void> {
  return api<void>(`/api/projects/${pid}/releases/${rid}`, { method: 'DELETE' });
}
```

- [ ] **Step 4: Add `setPublic` + the `public` type**

In `frontend/src/api/projects.ts`:

```ts
export function setPublic(id: number, isPublic: boolean): Promise<void> {
  return api<void>(`/api/projects/${id}/public`, { method: 'PUT', json: { public: isPublic } });
}
```

In `frontend/src/api/types.ts`, add to the `Project` interface: `public: boolean;`

- [ ] **Step 5: Add the Public toggle + Releases section to `ProjectMetadataPage.tsx`**

Add near the top of the returned `Stack` (owner-only; the page already computes `data.role`), a Public switch:

```tsx
{data?.role === 'owner' && (
  <Group justify="space-between">
    <div>
      <Text fw={600}>Public</Text>
      <Text size="sm" c="dimmed">List this project on the public landing page and publish its releases.</Text>
    </div>
    <Switch
      checked={data.public}
      onChange={(e) => publicMut.mutate(e.currentTarget.checked)}
    />
  </Group>
)}
```

with a mutation:

```tsx
const publicMut = useMutation({
  mutationFn: (v: boolean) => setPublic(id, v),
  onSuccess: async () => { await queryClient.invalidateQueries({ queryKey: ['project', id] }); },
  onError: (e) => notifications.show({ color: 'red', message: messageFor(e, 'Could not update visibility') }),
});
```

And a Releases section (owner can publish/delete; a `useQuery(['releases', id])` with `refetchInterval` while any release is `BUILDING`, a version `TextInput`, a "Publish release" button, and a history table with Download link `href={`/api/projects/${id}/releases/${r.id}` ... }`). Since the download endpoint is public-only (Phase 2), the editor's release table links to the **public** download once available, or omits the link until Phase 2 — for Task 4, show version/status/date/size + a Delete button; wire the Download link in Phase 3. Poll: `refetchInterval: (q) => (q.state.data?.some((r) => r.status === 'BUILDING') ? 1500 : false)`.

Import `setPublic`, `listReleases`, `publishRelease`, `deleteRelease`, `Switch`.

- [ ] **Step 6: Run the test + typecheck**

Run: `cd frontend && npx vitest run src/projects/ProjectMetadataPage.test.tsx && npx tsc -b`
Expected: PASS + clean typecheck.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/api/releases.ts frontend/src/api/projects.ts frontend/src/api/types.ts frontend/src/projects/ProjectMetadataPage.tsx frontend/src/projects/ProjectMetadataPage.test.tsx
git commit -m "feat(release): editor Public toggle + Releases section (publish/history/delete)"
```

---

## Phase 2 — Public read API + `/api/config` + security

### Task 5: `/api/config` + SecurityConfig `permitAll` + post-login target

**Files:**
- Create: `backend/src/main/java/org/catalogueoflife/editor/config/ConfigController.java`
- Modify: `backend/src/main/java/org/catalogueoflife/editor/auth/SecurityConfig.java`
- Test: `backend/src/test/java/org/catalogueoflife/editor/config/ConfigApiIT.java`

**Interfaces:**
- Produces: `GET /api/config -> { orcidEnabled: boolean }` (anonymous); `/api/public/**` + `/api/config` permitAll.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/org/catalogueoflife/editor/config/ConfigApiIT.java`:

```java
package org.catalogueoflife.editor.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class ConfigApiIT extends AbstractPostgresIT {
  @Autowired MockMvc mvc;

  @Test
  void configIsPublicAndReportsOrcid() throws Exception {
    // no @WithMockUser: must be reachable anonymously. application-test.yml sets a real client-id.
    mvc.perform(get("/api/config"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.orcidEnabled").value(true));
  }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw -q -Dtest=ConfigApiIT test`
Expected: FAIL — 401 (not permitted anonymously) / no endpoint.

- [ ] **Step 3: Create `ConfigController`**

```java
package org.catalogueoflife.editor.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ConfigController {

  // ORCID is "configured" iff the client-id is not the sentinel default (see application.yml).
  @Value("${spring.security.oauth2.client.registration.orcid.client-id:unconfigured}")
  private String orcidClientId;

  public record ConfigResponse(boolean orcidEnabled) {}

  @GetMapping("/api/config")
  public ConfigResponse config() {
    return new ConfigResponse(!"unconfigured".equals(orcidClientId));
  }
}
```

- [ ] **Step 4: Add the `permitAll` matchers + fix the post-login target**

In `SecurityConfig.java`, extend the matcher chain:

```java
            .requestMatchers("/api/ping").permitAll()
            .requestMatchers("/api/auth/login", "/login/**", "/oauth2/**").permitAll()
            .requestMatchers("/api/public/**", "/api/config").permitAll()
            .requestMatchers("/pdf/**").permitAll()
            .anyRequest().authenticated())
```

And change the ORCID success target from `/` (now the public landing) to the authed app home:

```java
        .oauth2Login(o -> o
            .userInfoEndpoint(u -> u.oidcUserService(orcidUserService))
            .defaultSuccessUrl("/projects", true))
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw -q -Dtest=ConfigApiIT test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/org/catalogueoflife/editor/config/ConfigController.java backend/src/main/java/org/catalogueoflife/editor/auth/SecurityConfig.java backend/src/test/java/org/catalogueoflife/editor/config/ConfigApiIT.java
git commit -m "feat(public): /api/config (orcidEnabled) + permitAll for /api/public + config; land ORCID on /projects"
```

---

### Task 6: Public projects list + project info

**Files:**
- Create: `backend/src/main/java/org/catalogueoflife/editor/publicapi/PublicController.java`
- Create: `backend/src/main/java/org/catalogueoflife/editor/publicapi/PublicProjectMapper.java`
- Create: `backend/src/main/java/org/catalogueoflife/editor/publicapi/dto/PublicProjectSummary.java`
- Create: `backend/src/main/java/org/catalogueoflife/editor/publicapi/dto/PublicProjectInfo.java`
- Create: `backend/src/main/java/org/catalogueoflife/editor/publicapi/dto/PublicContributor.java`
- Test: `backend/src/test/java/org/catalogueoflife/editor/publicapi/PublicProjectApiIT.java`

**Interfaces:**
- Consumes: `ProjectMapper.findById`, `ProjectMemberMapper.findByProject`, `AppUserService`/`AppUserMapper.findById` (orcid, displayName), `ReleaseMapper.findLatestReady`/`findReadyByProject`, `ReleaseMetricsService.compute` (live fallback), `Role.VIEWER.dbValue()`.
- Produces: `GET /api/public/projects`; `GET /api/public/projects/{idOrAlias}` (anonymous).

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/org/catalogueoflife/editor/publicapi/PublicProjectApiIT.java`:

```java
package org.catalogueoflife.editor.publicapi;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserService;
import org.catalogueoflife.editor.project.ProjectMember;
import org.catalogueoflife.editor.project.ProjectMemberMapper;
import org.catalogueoflife.editor.project.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@AutoConfigureMockMvc
class PublicProjectApiIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ProjectMemberMapper members;
  @Autowired ObjectMapper json;

  private void ensureUser(String u) { if (users.requireByUsernameOrNull(u) == null) users.createLocal(u, "pw", u); }

  private int makePublicProject(String owner) throws Exception {
    ensureUser(owner);
    String b = mvc.perform(post("/api/projects").with(csrf()).with(user(owner))
            .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Public One\",\"alias\":\"pub1\"}"))
        .andReturn().getResponse().getContentAsString();
    int pid = json.readTree(b).get("id").asInt();
    mvc.perform(put("/api/projects/" + pid + "/public").with(csrf()).with(user(owner))
            .contentType(MediaType.APPLICATION_JSON).content("{\"public\":true}")).andExpect(status().isOk());
    return pid;
  }

  @Test
  void privateProjectIsNotExposed() throws Exception {
    ensureUser("po");
    String b = mvc.perform(post("/api/projects").with(csrf()).with(user("po"))
            .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Private\"}"))
        .andReturn().getResponse().getContentAsString();
    int pid = json.readTree(b).get("id").asInt();
    mvc.perform(get("/api/public/projects/" + pid)).andExpect(status().isNotFound());
    mvc.perform(get("/api/public/projects"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$[?(@.id == " + pid + ")]").isEmpty());
  }

  @Test
  void publicProjectExposesInfoAndContributorsExcludeViewers() throws Exception {
    int pid = makePublicProject("owner1");
    ensureUser("viewer1");
    AppUser v = users.requireByUsernameOrNull("viewer1");
    members.upsert(new ProjectMember(pid, v.getId(), Role.VIEWER.dbValue()));

    // anonymous read
    mvc.perform(get("/api/public/projects/" + pid))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.id").value(pid))
       .andExpect(jsonPath("$.title").value("Public One"))
       .andExpect(jsonPath("$.contributors[?(@.role == 'viewer')]").isEmpty())
       .andExpect(jsonPath("$.contributors[?(@.role == 'owner')]").exists())
       .andExpect(jsonPath("$.contributors[0].email").doesNotExist());
    // alias resolves to the same canonical id
    mvc.perform(get("/api/public/projects/pub1"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.id").value(pid));
  }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw -q -Dtest=PublicProjectApiIT test`
Expected: FAIL — endpoints missing.

- [ ] **Step 3: Create the DTOs**

`dto/PublicContributor.java`:
```java
package org.catalogueoflife.editor.publicapi.dto;
public record PublicContributor(String name, String orcid, String role) {}
```

`dto/PublicProjectSummary.java`:
```java
package org.catalogueoflife.editor.publicapi.dto;
import java.time.OffsetDateTime;
public record PublicProjectSummary(int id, String title, String alias, String description,
    String latestVersion, OffsetDateTime latestReleasedAt, Integer nameUsageCount) {}
```

`dto/PublicProjectInfo.java`:
```java
package org.catalogueoflife.editor.publicapi.dto;
import java.util.List;
import tools.jackson.databind.JsonNode;
public record PublicProjectInfo(int id, String title, String alias, String description,
    String license, String nomCode, String geographicScope, String taxonomicScope,
    List<PublicContributor> contributors, JsonNode metrics,
    List<PublicRelease> releases) {

  public record PublicRelease(int id, String version, String notes, java.time.OffsetDateTime createdAt,
      String fileName, Long fileSize, Integer nameUsageCount, JsonNode metrics, String downloadUrl) {}
}
```

- [ ] **Step 4: Create `PublicProjectMapper`**

```java
package org.catalogueoflife.editor.publicapi;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.catalogueoflife.editor.project.Project;

@Mapper
public interface PublicProjectMapper {

  @Select("SELECT * FROM project WHERE is_public = true ORDER BY title")
  @Results(id = "pubProject", value = {
      @Result(property = "identifierScopes", column = "identifier_scopes",
          typeHandler = org.catalogueoflife.editor.project.IdentifierScopeListTypeHandler.class)
  })
  List<Project> findPublic();

  @Select("SELECT * FROM project WHERE id = #{id} AND is_public = true")
  @org.apache.ibatis.annotations.ResultMap("pubProject")
  Project findPublicById(@Param("id") int id);

  @Select("SELECT * FROM project WHERE alias = #{alias} AND is_public = true ORDER BY id LIMIT 1")
  @org.apache.ibatis.annotations.ResultMap("pubProject")
  Project findPublicByAlias(@Param("alias") String alias);
}
```

- [ ] **Step 5: Create `PublicController`**

```java
package org.catalogueoflife.editor.publicapi;

import java.util.List;
import org.catalogueoflife.editor.project.Project;
import org.catalogueoflife.editor.project.ProjectMember;
import org.catalogueoflife.editor.project.ProjectMemberMapper;
import org.catalogueoflife.editor.project.Role;
import org.catalogueoflife.editor.publicapi.dto.PublicContributor;
import org.catalogueoflife.editor.publicapi.dto.PublicProjectInfo;
import org.catalogueoflife.editor.publicapi.dto.PublicProjectInfo.PublicRelease;
import org.catalogueoflife.editor.publicapi.dto.PublicProjectSummary;
import org.catalogueoflife.editor.release.Release;
import org.catalogueoflife.editor.release.ReleaseMapper;
import org.catalogueoflife.editor.release.ReleaseMetricsService;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserMapper;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
public class PublicController {

  private final PublicProjectMapper projects;
  private final ProjectMemberMapper members;
  private final AppUserMapper users;
  private final ReleaseMapper releases;
  private final ReleaseMetricsService metricsService;
  private final ObjectMapper json;

  public PublicController(PublicProjectMapper projects, ProjectMemberMapper members, AppUserMapper users,
      ReleaseMapper releases, ReleaseMetricsService metricsService, ObjectMapper json) {
    this.projects = projects;
    this.members = members;
    this.users = users;
    this.releases = releases;
    this.metricsService = metricsService;
    this.json = json;
  }

  @GetMapping("/api/public/projects")
  public List<PublicProjectSummary> list() {
    return projects.findPublic().stream().map(p -> {
      Release latest = releases.findLatestReady(p.getId());
      return new PublicProjectSummary(p.getId(), p.getTitle(), p.getAlias(), p.getDescription(),
          latest == null ? null : latest.getVersion(),
          latest == null ? null : latest.getCreatedAt(),
          latest == null ? null : latest.getNameUsageCount());
    }).toList();
  }

  @GetMapping("/api/public/projects/{idOrAlias}")
  public PublicProjectInfo info(@PathVariable String idOrAlias) {
    Project p = resolve(idOrAlias);
    List<PublicContributor> contributors = members.findByProject(p.getId()).stream()
        .filter(m -> !Role.VIEWER.dbValue().equals(m.getRole()))
        .map(m -> {
          AppUser u = users.findById(m.getUserId());
          String name = u == null ? null : (u.getDisplayName() != null ? u.getDisplayName() : u.getUsername());
          return new PublicContributor(name, u == null ? null : u.getOrcid(), m.getRole());
        }).toList();

    List<Release> ready = releases.findReadyByProject(p.getId());
    List<PublicRelease> rels = ready.stream().map(r -> new PublicRelease(
        r.getId(), r.getVersion(), r.getNotes(), r.getCreatedAt(), r.getFileName(), r.getFileSize(),
        r.getNameUsageCount(), parse(r.getMetrics()),
        "/api/public/projects/" + p.getId() + "/releases/" + r.getId() + "/download")).toList();

    // Headline metrics: latest release snapshot if any, else a live compute (all-time, since=null).
    JsonNode metrics = ready.isEmpty()
        ? parse(metricsService.compute(p.getId(), null))
        : parse(ready.get(0).getMetrics());

    return new PublicProjectInfo(p.getId(), p.getTitle(), p.getAlias(), p.getDescription(),
        p.getLicense() == null ? null : p.getLicense().name(),
        p.getNomCode() == null ? null : p.getNomCode().name().toLowerCase(java.util.Locale.ROOT),
        p.getGeographicScope(), p.getTaxonomicScope(), contributors, metrics, rels);
  }

  private Project resolve(String idOrAlias) {
    Project p = idOrAlias.matches("\\d+")
        ? projects.findPublicById(Integer.parseInt(idOrAlias))
        : projects.findPublicByAlias(idOrAlias);
    if (p == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "not found");
    return p;
  }

  private JsonNode parse(String s) {
    if (s == null || s.isBlank()) return null;
    try { return json.readTree(s); } catch (Exception e) { return null; }
  }
}
```

Note: verify `AppUserMapper.findById` exists and returns an `AppUser` with `getOrcid()`/`getDisplayName()` (it does — `ProjectService.listMembers` uses `users.findById`). Verify `Project.getLicense()` returns a `License` enum with `.name()`; if it's already a String, drop `.name()`.

- [ ] **Step 6: Run the test to verify it passes**

Run: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw -q -Dtest=PublicProjectApiIT test`
Expected: PASS (2 tests).

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/org/catalogueoflife/editor/publicapi backend/src/test/java/org/catalogueoflife/editor/publicapi/PublicProjectApiIT.java
git commit -m "feat(public): public projects list + project info (contributors, metrics, releases)"
```

---

### Task 7: Public release download

**Files:**
- Modify: `backend/src/main/java/org/catalogueoflife/editor/publicapi/PublicController.java` (add the download endpoint)
- Test: `backend/src/test/java/org/catalogueoflife/editor/publicapi/PublicReleaseDownloadIT.java`

**Interfaces:**
- Produces: `GET /api/public/projects/{id}/releases/{rid}/download` (anonymous; public + READY only).

- [ ] **Step 1: Write the failing test**

`PublicReleaseDownloadIT.java` — mirror `ProjectPublicApiIT`/`ReleaseApiIT` setup: create a project, make it public, publish a release, await READY (Awaitility or the ImportApiIT loop), then:
```java
    mvc.perform(get("/api/public/projects/" + pid + "/releases/" + rid + "/download"))
       .andExpect(status().isOk())
       .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")));
```
Plus a negative: a private project's release download returns 404 (toggle public off, or a second private project).

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw -q -Dtest=PublicReleaseDownloadIT test`
Expected: FAIL — no download endpoint.

- [ ] **Step 3: Add the download endpoint to `PublicController`**

```java
  @GetMapping("/api/public/projects/{id}/releases/{rid}/download")
  public org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> download(
      @PathVariable int id, @PathVariable int rid) {
    Project p = projects.findPublicById(id);
    Release r = releases.findById(rid);
    if (p == null || r == null || !r.getProjectId().equals(id) || !"READY".equals(r.getStatus())
        || r.getFilePath() == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "not found");
    }
    java.nio.file.Path path = java.nio.file.Path.of(r.getFilePath());
    org.springframework.core.io.Resource res = new org.springframework.core.io.FileSystemResource(path);
    org.springframework.http.ContentDisposition cd = org.springframework.http.ContentDisposition
        .attachment().filename(r.getFileName()).build();
    return org.springframework.http.ResponseEntity.ok()
        .contentType(org.springframework.http.MediaType.valueOf("application/zip"))
        .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, cd.toString())
        .contentLength(path.toFile().length())
        .body(res);
  }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw -q -Dtest=PublicReleaseDownloadIT test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/org/catalogueoflife/editor/publicapi/PublicController.java backend/src/test/java/org/catalogueoflife/editor/publicapi/PublicReleaseDownloadIT.java
git commit -m "feat(public): public release download (public + READY only)"
```

---

## Phase 3 — Public frontend

### Task 8: Routing split + public API client + config

**Files:**
- Modify: `frontend/src/App.tsx`
- Create: `frontend/src/components/PublicLayout.tsx`
- Create: `frontend/src/pages/LandingPage.tsx` (stub → filled in Task 9)
- Create: `frontend/src/pages/PublicProjectPage.tsx` (stub → filled in Task 10)
- Create: `frontend/src/api/config.ts`
- Create: `frontend/src/api/public.ts`
- Modify: `frontend/src/auth/LoginPage.tsx` (navigate to `/projects` after local login)
- Modify: `frontend/src/test/server.ts` (default `/api/config` handler)
- Modify: `frontend/src/AppRouting.test.tsx` (update the two tests for the new `/` = landing, list at `/projects`)

**Interfaces:**
- Produces: public routes `/`, `/p/:idOrAlias`; `getConfig()`, `getPublicProjects()`, `getPublicProject(idOrAlias)`, `publicReleaseDownloadUrl(id,rid)`.

- [ ] **Step 1: Create the API modules**

`frontend/src/api/config.ts`:
```ts
import { api } from './client';
export interface AppConfig { orcidEnabled: boolean; }
export function getConfig(): Promise<AppConfig> { return api<AppConfig>('/api/config'); }
```

`frontend/src/api/public.ts`:
```ts
import { api } from './client';

export interface PublicProjectSummary {
  id: number; title: string; alias: string | null; description: string | null;
  latestVersion: string | null; latestReleasedAt: string | null; nameUsageCount: number | null;
}
export interface PublicContributor { name: string | null; orcid: string | null; role: string; }
export interface PublicRelease {
  id: number; version: string; notes: string | null; createdAt: string | null;
  fileName: string | null; fileSize: number | null; nameUsageCount: number | null;
  metrics: unknown | null; downloadUrl: string;
}
export interface PublicProjectInfo {
  id: number; title: string; alias: string | null; description: string | null;
  license: string | null; nomCode: string | null; geographicScope: string | null;
  taxonomicScope: string | null; contributors: PublicContributor[]; metrics: unknown | null;
  releases: PublicRelease[];
}
export function getPublicProjects(): Promise<PublicProjectSummary[]> {
  return api<PublicProjectSummary[]>('/api/public/projects');
}
export function getPublicProject(idOrAlias: string): Promise<PublicProjectInfo> {
  return api<PublicProjectInfo>(`/api/public/projects/${idOrAlias}`);
}
```

- [ ] **Step 2: Create the stubs + PublicLayout**

`frontend/src/components/PublicLayout.tsx` — a slim header (Blixa logo linking `/`, a right-side "Sign in" link to `/login` or "My projects" to `/projects` by `useMe`) wrapping `<Outlet/>`:
```tsx
import { AppShell, Anchor, Group } from '@mantine/core';
import { Link, Outlet } from 'react-router-dom';
import BlixaLogo from './BlixaLogo';
import { useMe } from '../auth/useMe';

export default function PublicLayout() {
  const { data: me } = useMe();
  return (
    <AppShell header={{ height: 56 }} padding="md">
      <AppShell.Header>
        <Group h="100%" px="md" justify="space-between">
          <Anchor component={Link} to="/" underline="never" c="inherit"><BlixaLogo variant="header" height={28} /></Anchor>
          {me
            ? <Anchor component={Link} to="/projects">My projects</Anchor>
            : <Anchor component={Link} to="/login">Sign in</Anchor>}
        </Group>
      </AppShell.Header>
      <AppShell.Main><Outlet /></AppShell.Main>
    </AppShell>
  );
}
```

`frontend/src/pages/LandingPage.tsx` and `frontend/src/pages/PublicProjectPage.tsx` — minimal stubs returning a heading (filled in Tasks 9-10):
```tsx
export default function LandingPage() { return <div>Blixa</div>; }
```
```tsx
export default function PublicProjectPage() { return <div>Project</div>; }
```

- [ ] **Step 3: Rewire `App.tsx`**

```tsx
import { Navigate, Route, Routes } from 'react-router-dom';
import LoginPage from './auth/LoginPage';
import RequireAuth from './auth/RequireAuth';
import AppLayout from './components/AppLayout';
import PublicLayout from './components/PublicLayout';
import LandingPage from './pages/LandingPage';
import PublicProjectPage from './pages/PublicProjectPage';
import ProjectListPage from './projects/ProjectListPage';
import ProjectLayout from './projects/ProjectLayout';
import ProjectMetadataPage from './projects/ProjectMetadataPage';
import MembersPage from './projects/MembersPage';
import TreePage from './tree/TreePage';
import NameSearchPage from './names/NameSearchPage';
import IssuesPage from './issues/IssuesPage';
import HistoryPage from './history/HistoryPage';
import ReferencesPage from './references/ReferencesPage';

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route element={<PublicLayout />}>
        <Route index element={<LandingPage />} />
        <Route path="p/:idOrAlias" element={<PublicProjectPage />} />
      </Route>
      <Route element={<RequireAuth />}>
        <Route element={<AppLayout />}>
          <Route path="projects" element={<ProjectListPage />} />
          <Route path="projects/:projectId" element={<ProjectLayout />}>
            <Route index element={<Navigate to="metadata" replace />} />
            <Route path="tree" element={<TreePage />} />
            <Route path="names" element={<NameSearchPage />} />
            <Route path="references" element={<ReferencesPage />} />
            <Route path="issues" element={<IssuesPage />} />
            <Route path="history" element={<HistoryPage />} />
            <Route path="metadata" element={<ProjectMetadataPage />} />
            <Route path="members" element={<MembersPage />} />
          </Route>
        </Route>
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
```

- [ ] **Step 4: Post-login redirect → `/projects`**

In `LoginPage.tsx`, change `navigate('/', { replace: true })` to `navigate('/projects', { replace: true })`.

- [ ] **Step 5: Default `/api/config` MSW handler + fix routing tests**

In `frontend/src/test/server.ts`, add to the default handlers:
```ts
  http.get('/api/config', () => HttpResponse.json({ orcidEnabled: true })),
  http.get('/api/public/projects', () => HttpResponse.json([])),
```
In `frontend/src/AppRouting.test.tsx`, update:
- Test 1 (anonymous): render `<App/>` at `/` → assert the landing renders (e.g. `screen.findByRole('link', { name: /sign in/i })` from the PublicLayout header, or a landing heading). It no longer redirects to the ORCID form.
- Test 2 (authenticated): render at `/projects` (with the `/api/me` + `/api/projects` overrides) → assert the project list (`findByRole('link', { name: 'Lepidoptera' })`). Keep the `Alice` assertion.

- [ ] **Step 6: Run typecheck + routing tests + full suite**

Run: `cd frontend && npx tsc -b && npx vitest run src/AppRouting.test.tsx && npx vitest run`
Expected: clean typecheck; routing tests pass; whole suite green.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/App.tsx frontend/src/components/PublicLayout.tsx frontend/src/pages frontend/src/api/config.ts frontend/src/api/public.ts frontend/src/auth/LoginPage.tsx frontend/src/test/server.ts frontend/src/AppRouting.test.tsx
git commit -m "feat(public): split public vs authed routes; project list moves to /projects"
```

---

### Task 9: LandingPage — description + public projects + env-aware login

**Files:**
- Modify: `frontend/src/pages/LandingPage.tsx`
- Test: `frontend/src/pages/LandingPage.test.tsx`

**Interfaces:**
- Consumes: `getPublicProjects()`, `getConfig()`, `useMe()`, `orcidLoginUrl()`, `localLogin`.

- [ ] **Step 1: Write the failing test**

`frontend/src/pages/LandingPage.test.tsx` (mirror `AppRouting.test.tsx`'s MSW pattern):
```tsx
import { describe, it, expect } from 'vitest';
import { renderWithProviders, screen } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import LandingPage from './LandingPage';

describe('LandingPage', () => {
  it('lists public projects and shows the ORCID button when enabled and anonymous', async () => {
    server.use(
      http.get('/api/me', () => new HttpResponse(null, { status: 401 })),
      http.get('/api/config', () => HttpResponse.json({ orcidEnabled: true })),
      http.get('/api/public/projects', () => HttpResponse.json([
        { id: 5, title: 'World Ferns', alias: 'ferns', description: 'A checklist',
          latestVersion: '1.0', latestReleasedAt: '2026-07-01T00:00:00Z', nameUsageCount: 42 },
      ])),
    );
    renderWithProviders(<LandingPage />);
    expect(await screen.findByRole('link', { name: /world ferns/i })).toBeInTheDocument();
    expect(await screen.findByRole('link', { name: /sign in with orcid/i })).toBeInTheDocument();
  });

  it('shows the local login form when ORCID is not configured', async () => {
    server.use(
      http.get('/api/me', () => new HttpResponse(null, { status: 401 })),
      http.get('/api/config', () => HttpResponse.json({ orcidEnabled: false })),
      http.get('/api/public/projects', () => HttpResponse.json([])),
    );
    renderWithProviders(<LandingPage />);
    expect(await screen.findByLabelText(/username/i)).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd frontend && npx vitest run src/pages/LandingPage.test.tsx`
Expected: FAIL — stub renders nothing matching.

- [ ] **Step 3: Implement `LandingPage`**

Render: a short Blixa description; a grid of `Paper withBorder` cards, each an `Anchor component={Link} to={`/p/${p.id}`}` with title + description + `latestVersion`/`nameUsageCount`; and a login area — `useMe()` + `getConfig()`: if `me` → "My projects" link; else if `orcidEnabled` → `<Button component="a" href={orcidLoginUrl()}>Sign in with ORCID</Button>`; else the local form (reuse the `LoginPage` form logic: username/password `useForm`, `localLogin`, on success invalidate `['me']` and `navigate('/projects')`). Use `useQuery(['publicProjects'], getPublicProjects)` and `useQuery(['config'], getConfig, { staleTime: Infinity })`.

- [ ] **Step 4: Run the tests + typecheck**

Run: `cd frontend && npx vitest run src/pages/LandingPage.test.tsx && npx tsc -b`
Expected: PASS + clean.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/LandingPage.tsx frontend/src/pages/LandingPage.test.tsx
git commit -m "feat(public): landing page (description, public projects, env-aware login)"
```

---

### Task 10: PublicProjectPage — metadata, contributors, metrics, releases

**Files:**
- Modify: `frontend/src/pages/PublicProjectPage.tsx`
- Test: `frontend/src/pages/PublicProjectPage.test.tsx`

**Interfaces:**
- Consumes: `getPublicProject(idOrAlias)`; `useParams`; `useNavigate` (alias→id redirect).

- [ ] **Step 1: Write the failing test**

`frontend/src/pages/PublicProjectPage.test.tsx`: render at `/p/5` via `renderWithProviders(<App/>, { route: '/p/5' })` (or render the page directly inside a `MemoryRouter` with a `:idOrAlias` route). Stub `GET /api/public/projects/5` → a project with one contributor (owner, name "Jane", orcid) and one READY release. Assert: title, the contributor name, the release version, and a Download link (`getByRole('link', { name: /download/i })`) whose href contains `/api/public/projects/5/releases/`. Add a 404 case (`HttpResponse 404` → a "not found" message).

- [ ] **Step 2: Run it to verify it fails**

Run: `cd frontend && npx vitest run src/pages/PublicProjectPage.test.tsx`
Expected: FAIL.

- [ ] **Step 3: Implement `PublicProjectPage`**

`useParams<{ idOrAlias: string }>()` → `useQuery(['publicProject', idOrAlias], () => getPublicProject(idOrAlias))`. On success, if `data.id !== Number(idOrAlias)` and `idOrAlias` is non-numeric, `navigate(`/p/${data.id}`, { replace: true })`. Render: header (title, alias, description); a metadata block (license, nomCode, scopes); a **Contributors** list (name + a linked ORCID icon/anchor `https://orcid.org/{orcid}` when present + role Badge); a **Metrics** block (headline `nameUsageCount` + render `acceptedByRank`/`synonymsByRank`/`supplementary` from `data.metrics` as small tables/badges — guard for null); a **Releases** table (version, date, size, `<Anchor href={r.downloadUrl} download>Download</Anchor>`). On `ApiError` 404 → a friendly "This project is not public or does not exist."

- [ ] **Step 4: Run the tests + typecheck + full suite**

Run: `cd frontend && npx vitest run src/pages/PublicProjectPage.test.tsx && npx tsc -b && npx vitest run`
Expected: PASS + clean + whole suite green.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/PublicProjectPage.tsx frontend/src/pages/PublicProjectPage.test.tsx
git commit -m "feat(public): public project page (metadata, contributors, metrics, release downloads)"
```

---

## Final verification (after all tasks)

- [ ] Backend full suite: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw -q test` → green.
- [ ] Frontend gates: `cd frontend && npx tsc -b && npx vitest run` → clean + green.
- [ ] Manual smoke (via `docker compose up`): sign in, on a project's metadata page toggle **Public** and **Publish release** (version 1.0), wait for READY; sign out; open `/` (landing shows the project), click through to `/p/{id}`, verify metadata/contributors/metrics/release download; confirm ORCID-vs-form login matches the environment.

## Notes carried from the spec

- Lightweight releases (not full CLB-style immutable/id-stable releases).
- No SSR/meta/schema.org for public pages (SPA client-render only).
- The public projects list shows the latest READY release's name count (null if none); the project page falls back to a live metrics compute when a public project has no release.
- `is_public` column / `isPublic` field / JSON `"public"`; contributors are all non-viewer members.
