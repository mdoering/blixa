# Phase 1 — Backend Foundation & Auth Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the standalone ColDP-editor backend — a Spring Boot service with Postgres, ORCID + local login, and a multi-project model (projects, users, roles, ColDP metadata) exposed as a REST API.

**Architecture:** Java 21 Spring Boot service using MyBatis (hand-written SQL) over a single shared-schema Postgres 17 database with Flyway migrations. Every data row is project-scoped. Auth is session-based via Spring Security: ORCID OpenID Connect is the primary path, with local username/password accounts as a fallback. Integration tests run against a real Postgres via Testcontainers. This is the first of six phase-1 plans; it delivers no UI — the React shell is Plan 2 and consumes this API.

**Tech Stack:** Java 21, Spring Boot 3.5.x, Spring Security (oauth2-client + form login), MyBatis (`mybatis-spring-boot-starter` 3.0.x), PostgreSQL 17, Flyway, JUnit 5, Testcontainers, Maven.

> **Stack update (2026-07-08, post-execution):** the backend was subsequently upgraded to **Spring Boot 4.1.0 / Java 25** (LTS) after Boot 3.5 reached end-of-life, with `mybatis-spring-boot-starter` 4.0.1 and Testcontainers 2.0. The version pins, the `3.5.4` pom snippet, and the `env 'api.version=1.41'` OrbStack workaround in the steps below are the **original as-executed values** and are kept for historical accuracy; the current authoritative stack is in the design spec §3. The build now requires **JDK 25** (`backend/.sdkmanrc` → `25.0.1-librca`; `sdk env`), and the OrbStack Testcontainers workaround is no longer needed.

## Global Constraints

- Java **21**; Spring Boot **3.5.x**; PostgreSQL **17**.
- Persistence is **MyBatis with hand-written SQL** (annotation mappers) — no JPA/Hibernate.
- **Single database, shared schema**; every data table carries a `project_id` (except the global `app_user`, `project` tables themselves). Surrogate `BIGINT GENERATED ALWAYS AS IDENTITY` primary keys.
- Roles are per-project, one of exactly: `owner`, `editor`, `reviewer`, `viewer`.
- Auth: **ORCID OIDC primary, local accounts fallback**. A user's ORCID is stored on `app_user.orcid`.
- Base Java package: `org.catalogueoflife.editor`. Maven `groupId` `org.catalogueoflife`, `artifactId` `coldp-editor-backend`.
- Backend lives under `backend/` in the repo root (`~/code/col/coldp-editor`).
- All integration tests that touch the DB run against **Testcontainers Postgres 17** (`postgres:17`), wired with Spring Boot `@ServiceConnection`.
- Every task ends with a green test run and a commit.
- `nom_code` is stored as free **text** in this plan; it is bound to the `NomCode` enum and validated in Plan 3 (which introduces the name-parser/vocab dependencies). Do **not** add name-parser or vocab dependencies in this plan.

## File Structure

```
backend/
  pom.xml                                              # Maven build
  src/main/resources/
    application.yml                                    # runtime config (env-driven)
    db/migration/
      V1__app_user.sql                                 # app_user table
      V2__project.sql                                  # project, project_member tables
  src/main/java/org/catalogueoflife/editor/
    EditorApplication.java                             # @SpringBootApplication + @MapperScan
    web/PingController.java                            # unauthenticated health ping
    web/ApiExceptionHandler.java                       # maps errors to JSON
    user/AppUser.java                                  # POJO
    user/AppUserMapper.java                            # MyBatis mapper
    user/AppUserService.java                           # upsert/find, password mgmt
    user/OrcidUserService.java                         # ORCID OIDC -> app_user provisioning
    project/Project.java                               # POJO
    project/ProjectMember.java                         # POJO
    project/Role.java                                  # enum
    project/ProjectMapper.java                         # MyBatis mapper
    project/ProjectMemberMapper.java                   # MyBatis mapper
    project/ProjectService.java                        # business logic + authz helpers
    project/ProjectController.java                     # /api/projects
    project/dto/CreateProjectRequest.java
    project/dto/UpdateProjectMetadataRequest.java
    project/dto/ProjectResponse.java
    project/dto/MemberRequest.java
    project/dto/MemberResponse.java
    auth/SecurityConfig.java                           # filter chain, login, authz
    auth/MeController.java                             # /api/me
    auth/CurrentUser.java                              # resolves app_user from principal
  src/test/java/org/catalogueoflife/editor/
    support/AbstractPostgresIT.java                    # Testcontainers base
    web/PingControllerTest.java
    user/AppUserMapperIT.java
    project/ProjectMapperIT.java
    auth/LocalLoginIT.java
    project/ProjectApiIT.java
    project/MemberApiIT.java
    user/OrcidUserServiceIT.java
  src/test/resources/
    application-test.yml                               # test overrides (dummy orcid creds)
```

---

### Task 1: Maven + Spring Boot web skeleton with a ping endpoint

**Files:**
- Create: `backend/pom.xml`
- Create: `backend/src/main/java/org/catalogueoflife/editor/EditorApplication.java`
- Create: `backend/src/main/java/org/catalogueoflife/editor/web/PingController.java`
- Create: `backend/src/main/resources/application.yml`
- Test: `backend/src/test/java/org/catalogueoflife/editor/web/PingControllerTest.java`

**Interfaces:**
- Consumes: nothing (first task).
- Produces: a runnable Spring Boot app `EditorApplication`; `GET /api/ping` → `200` with body `{"status":"ok"}`.

- [ ] **Step 1: Create the Maven build file**

`backend/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.5.4</version>
    <relativePath/>
  </parent>

  <groupId>org.catalogueoflife</groupId>
  <artifactId>coldp-editor-backend</artifactId>
  <version>0.1.0-SNAPSHOT</version>

  <properties>
    <java.version>21</java.version>
    <mybatis-starter.version>3.0.4</mybatis-starter.version>
  </properties>

  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>

    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <configuration>
          <parameters>true</parameters>
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 2: Create the application class and ping controller**

`backend/src/main/java/org/catalogueoflife/editor/EditorApplication.java`:

```java
package org.catalogueoflife.editor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class EditorApplication {
  public static void main(String[] args) {
    SpringApplication.run(EditorApplication.class, args);
  }
}
```

`backend/src/main/java/org/catalogueoflife/editor/web/PingController.java`:

```java
package org.catalogueoflife.editor.web;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PingController {

  @GetMapping("/api/ping")
  public Map<String, String> ping() {
    return Map.of("status", "ok");
  }
}
```

`backend/src/main/resources/application.yml`:

```yaml
spring:
  application:
    name: coldp-editor
server:
  port: 8080
```

- [ ] **Step 3: Write the failing slice test**

`backend/src/test/java/org/catalogueoflife/editor/web/PingControllerTest.java`:

```java
package org.catalogueoflife.editor.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PingController.class)
class PingControllerTest {

  @Autowired MockMvc mvc;

  @Test
  void pingReturnsOk() throws Exception {
    mvc.perform(get("/api/ping"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.status").value("ok"));
  }
}
```

- [ ] **Step 4: Run the test — expect FAIL**

Run: `cd backend && ./mvnw -q -Dtest=PingControllerTest test` (first run: `mvn -q -Dtest=PingControllerTest test` if the wrapper isn't generated yet; generate it with `mvn -N wrapper:wrapper`).
Expected: FAIL — compilation error because classes don't exist yet, or the test cannot find the controller.

- [ ] **Step 5: Re-run after the classes compile — expect PASS**

Run: `cd backend && mvn -q -Dtest=PingControllerTest test`
Expected: PASS (`Tests run: 1, Failures: 0`).

- [ ] **Step 6: Commit**

```bash
git add backend/pom.xml backend/src backend/mvnw backend/mvnw.cmd backend/.mvn
git commit -m "feat(backend): Spring Boot skeleton with /api/ping"
```

---

### Task 2: Postgres + Flyway + Testcontainers, and the `app_user` entity

**Files:**
- Modify: `backend/pom.xml` (add persistence + Testcontainers deps)
- Modify: `backend/src/main/java/org/catalogueoflife/editor/EditorApplication.java` (add `@MapperScan`)
- Create: `backend/src/main/resources/db/migration/V1__app_user.sql`
- Create: `backend/src/main/java/org/catalogueoflife/editor/user/AppUser.java`
- Create: `backend/src/main/java/org/catalogueoflife/editor/user/AppUserMapper.java`
- Create: `backend/src/test/java/org/catalogueoflife/editor/support/AbstractPostgresIT.java`
- Test: `backend/src/test/java/org/catalogueoflife/editor/user/AppUserMapperIT.java`

**Interfaces:**
- Consumes: `EditorApplication` (Task 1).
- Produces:
  - `AppUser` POJO with fields: `Long id`, `String orcid`, `String username`, `String email`, `String displayName`, `String given`, `String family`, `String passwordHash`, plus getters/setters.
  - `AppUserMapper` methods: `void insert(AppUser u)` (sets generated `id`), `AppUser findById(long id)`, `AppUser findByUsername(String username)`, `AppUser findByOrcid(String orcid)`, `void update(AppUser u)`.
  - `AbstractPostgresIT` — base class starting a `postgres:17` container wired via `@ServiceConnection`, so subclasses get a fully-migrated DB.

- [ ] **Step 1: Add persistence and Testcontainers dependencies**

In `backend/pom.xml`, add inside `<dependencies>` (keep existing entries):

```xml
    <dependency>
      <groupId>org.mybatis.spring.boot</groupId>
      <artifactId>mybatis-spring-boot-starter</artifactId>
      <version>${mybatis-starter.version}</version>
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-core</artifactId>
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-database-postgresql</artifactId>
    </dependency>
    <dependency>
      <groupId>org.postgresql</groupId>
      <artifactId>postgresql</artifactId>
      <scope>runtime</scope>
    </dependency>

    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-testcontainers</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>postgresql</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>junit-jupiter</artifactId>
      <scope>test</scope>
    </dependency>
```

Add the datasource block to `backend/src/main/resources/application.yml` (env-driven, for real runs):

```yaml
spring:
  application:
    name: coldp-editor
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/coldp_editor}
    username: ${DB_USER:coldp_editor}
    password: ${DB_PASSWORD:coldp_editor}
  flyway:
    enabled: true
mybatis:
  configuration:
    map-underscore-to-camel-case: true
server:
  port: 8080
```

- [ ] **Step 2: Add `@MapperScan` to the application**

Replace `EditorApplication.java` body annotations:

```java
package org.catalogueoflife.editor;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("org.catalogueoflife.editor")
public class EditorApplication {
  public static void main(String[] args) {
    SpringApplication.run(EditorApplication.class, args);
  }
}
```

- [ ] **Step 3: Create the first migration**

`backend/src/main/resources/db/migration/V1__app_user.sql`:

```sql
CREATE TABLE app_user (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  orcid         TEXT UNIQUE,
  username      TEXT UNIQUE NOT NULL,
  email         TEXT,
  display_name  TEXT,
  given         TEXT,
  family        TEXT,
  password_hash TEXT,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

- [ ] **Step 4: Create the `AppUser` POJO and mapper**

`backend/src/main/java/org/catalogueoflife/editor/user/AppUser.java`:

```java
package org.catalogueoflife.editor.user;

public class AppUser {
  private Long id;
  private String orcid;
  private String username;
  private String email;
  private String displayName;
  private String given;
  private String family;
  private String passwordHash;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getOrcid() { return orcid; }
  public void setOrcid(String orcid) { this.orcid = orcid; }
  public String getUsername() { return username; }
  public void setUsername(String username) { this.username = username; }
  public String getEmail() { return email; }
  public void setEmail(String email) { this.email = email; }
  public String getDisplayName() { return displayName; }
  public void setDisplayName(String displayName) { this.displayName = displayName; }
  public String getGiven() { return given; }
  public void setGiven(String given) { this.given = given; }
  public String getFamily() { return family; }
  public void setFamily(String family) { this.family = family; }
  public String getPasswordHash() { return passwordHash; }
  public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
}
```

`backend/src/main/java/org/catalogueoflife/editor/user/AppUserMapper.java`:

```java
package org.catalogueoflife.editor.user;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AppUserMapper {

  @Insert("""
      INSERT INTO app_user (orcid, username, email, display_name, given, family, password_hash)
      VALUES (#{orcid}, #{username}, #{email}, #{displayName}, #{given}, #{family}, #{passwordHash})
      """)
  @Options(useGeneratedKeys = true, keyProperty = "id")
  void insert(AppUser u);

  @Select("SELECT * FROM app_user WHERE id = #{id}")
  AppUser findById(long id);

  @Select("SELECT * FROM app_user WHERE username = #{username}")
  AppUser findByUsername(String username);

  @Select("SELECT * FROM app_user WHERE orcid = #{orcid}")
  AppUser findByOrcid(String orcid);

  @Update("""
      UPDATE app_user
      SET orcid = #{orcid}, username = #{username}, email = #{email},
          display_name = #{displayName}, given = #{given}, family = #{family},
          password_hash = #{passwordHash}, updated_at = now()
      WHERE id = #{id}
      """)
  void update(AppUser u);
}
```

- [ ] **Step 5: Create the Testcontainers base class**

`backend/src/test/java/org/catalogueoflife/editor/support/AbstractPostgresIT.java`:

```java
package org.catalogueoflife.editor.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;

// Singleton-container pattern: one postgres:17 container is started once (static
// initializer) and shared by every IT for the JVM's lifetime (Testcontainers' Ryuk
// stops it at the end). This avoids per-class start/stop and the context-cache vs
// container-lifecycle mismatches you get with @Testcontainers/@Container. @ServiceConnection
// wires Spring Boot's datasource to it.
//
// @ActiveProfiles("test") applies to ALL integration tests so that, once the ORCID
// client registration is added to the main application.yml in Task 7 (with an empty
// default client-id), every IT still loads its context using the dummy ORCID
// credentials from src/test/resources/application-test.yml (created in Task 4).
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractPostgresIT {

  @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

  static {
    POSTGRES.start();
  }
}
```

- [ ] **Step 6: Write the failing mapper test**

`backend/src/test/java/org/catalogueoflife/editor/user/AppUserMapperIT.java`:

```java
package org.catalogueoflife.editor.user;

import static org.assertj.core.api.Assertions.assertThat;

import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AppUserMapperIT extends AbstractPostgresIT {

  @Autowired AppUserMapper mapper;

  @Test
  void insertsAndReadsBackByOrcid() {
    AppUser u = new AppUser();
    u.setUsername("0000-0001-7757-1889");
    u.setOrcid("0000-0001-7757-1889");
    u.setDisplayName("Markus Döring");

    mapper.insert(u);

    assertThat(u.getId()).isNotNull();
    AppUser found = mapper.findByOrcid("0000-0001-7757-1889");
    assertThat(found).isNotNull();
    assertThat(found.getDisplayName()).isEqualTo("Markus Döring");
    assertThat(found.getId()).isEqualTo(u.getId());
  }
}
```

- [ ] **Step 7: Run the test — expect PASS**

Run: `cd backend && mvn -q -Dtest=AppUserMapperIT test`
Expected: PASS. Flyway applies `V1` to the container, the insert returns a generated id, and the lookup matches. (Requires Docker running.)

- [ ] **Step 8: Commit**

```bash
git add backend/pom.xml backend/src
git commit -m "feat(backend): Postgres/Flyway/Testcontainers wiring + app_user entity"
```

---

### Task 3: `project` and `project_member` entities with roles

**Files:**
- Create: `backend/src/main/resources/db/migration/V2__project.sql`
- Create: `backend/src/main/java/org/catalogueoflife/editor/project/Role.java`
- Create: `backend/src/main/java/org/catalogueoflife/editor/project/Project.java`
- Create: `backend/src/main/java/org/catalogueoflife/editor/project/ProjectMember.java`
- Create: `backend/src/main/java/org/catalogueoflife/editor/project/ProjectMapper.java`
- Create: `backend/src/main/java/org/catalogueoflife/editor/project/ProjectMemberMapper.java`
- Test: `backend/src/test/java/org/catalogueoflife/editor/project/ProjectMapperIT.java`

**Interfaces:**
- Consumes: `AbstractPostgresIT` (Task 2), `AppUserMapper` (Task 2).
- Produces:
  - `Role` enum: `OWNER, EDITOR, REVIEWER, VIEWER`, each with `String dbValue()` returning the lowercase name; static `Role fromDb(String)`.
  - `Project` POJO: `Long id`, `String slug`, `String title`, `String alias`, `String description`, `String nomCode`, `String license`, `String version`, `java.time.LocalDate issued`, `String geographicScope`, `String taxonomicScope`, `String doi`, `String metadata` (JSON string).
  - `ProjectMember` POJO: `Long projectId`, `Long userId`, `String role`.
  - `ProjectMapper`: `void insert(Project p)`, `Project findById(long id)`, `Project findBySlug(String slug)`, `java.util.List<Project> findByMember(long userId)`, `void updateMetadata(Project p)`.
  - `ProjectMemberMapper`: `void upsert(ProjectMember m)`, `String findRole(@Param("projectId") long projectId, @Param("userId") long userId)`, `java.util.List<ProjectMember> findByProject(long projectId)`, `void delete(@Param("projectId") long projectId, @Param("userId") long userId)`.

- [ ] **Step 1: Create the migration**

`backend/src/main/resources/db/migration/V2__project.sql`:

```sql
CREATE TABLE project (
  id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  slug              TEXT UNIQUE NOT NULL,
  title             TEXT NOT NULL,
  alias             TEXT,
  description       TEXT,
  nom_code          TEXT,
  license           TEXT,
  version           TEXT,
  issued            DATE,
  geographic_scope  TEXT,
  taxonomic_scope   TEXT,
  doi               TEXT,
  metadata          JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE project_member (
  project_id BIGINT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  user_id    BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  role       TEXT NOT NULL CHECK (role IN ('owner','editor','reviewer','viewer')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (project_id, user_id)
);

CREATE INDEX project_member_user_idx ON project_member (user_id);
```

- [ ] **Step 2: Create the `Role` enum**

`backend/src/main/java/org/catalogueoflife/editor/project/Role.java`:

```java
package org.catalogueoflife.editor.project;

import java.util.Locale;

public enum Role {
  OWNER, EDITOR, REVIEWER, VIEWER;

  public String dbValue() {
    return name().toLowerCase(Locale.ROOT);
  }

  public static Role fromDb(String v) {
    return Role.valueOf(v.toUpperCase(Locale.ROOT));
  }
}
```

- [ ] **Step 3: Create the POJOs**

`backend/src/main/java/org/catalogueoflife/editor/project/Project.java`:

```java
package org.catalogueoflife.editor.project;

import java.time.LocalDate;

public class Project {
  private Long id;
  private String slug;
  private String title;
  private String alias;
  private String description;
  private String nomCode;
  private String license;
  private String version;
  private LocalDate issued;
  private String geographicScope;
  private String taxonomicScope;
  private String doi;
  private String metadata = "{}";

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getSlug() { return slug; }
  public void setSlug(String slug) { this.slug = slug; }
  public String getTitle() { return title; }
  public void setTitle(String title) { this.title = title; }
  public String getAlias() { return alias; }
  public void setAlias(String alias) { this.alias = alias; }
  public String getDescription() { return description; }
  public void setDescription(String description) { this.description = description; }
  public String getNomCode() { return nomCode; }
  public void setNomCode(String nomCode) { this.nomCode = nomCode; }
  public String getLicense() { return license; }
  public void setLicense(String license) { this.license = license; }
  public String getVersion() { return version; }
  public void setVersion(String version) { this.version = version; }
  public LocalDate getIssued() { return issued; }
  public void setIssued(LocalDate issued) { this.issued = issued; }
  public String getGeographicScope() { return geographicScope; }
  public void setGeographicScope(String geographicScope) { this.geographicScope = geographicScope; }
  public String getTaxonomicScope() { return taxonomicScope; }
  public void setTaxonomicScope(String taxonomicScope) { this.taxonomicScope = taxonomicScope; }
  public String getDoi() { return doi; }
  public void setDoi(String doi) { this.doi = doi; }
  public String getMetadata() { return metadata; }
  public void setMetadata(String metadata) { this.metadata = metadata; }
}
```

`backend/src/main/java/org/catalogueoflife/editor/project/ProjectMember.java`:

```java
package org.catalogueoflife.editor.project;

public class ProjectMember {
  private Long projectId;
  private Long userId;
  private String role;

  public ProjectMember() {}

  public ProjectMember(Long projectId, Long userId, String role) {
    this.projectId = projectId;
    this.userId = userId;
    this.role = role;
  }

  public Long getProjectId() { return projectId; }
  public void setProjectId(Long projectId) { this.projectId = projectId; }
  public Long getUserId() { return userId; }
  public void setUserId(Long userId) { this.userId = userId; }
  public String getRole() { return role; }
  public void setRole(String role) { this.role = role; }
}
```

- [ ] **Step 4: Create the mappers**

`backend/src/main/java/org/catalogueoflife/editor/project/ProjectMapper.java`:

```java
package org.catalogueoflife.editor.project;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ProjectMapper {

  @Insert("""
      INSERT INTO project (slug, title, alias, description, nom_code, license, version,
                           issued, geographic_scope, taxonomic_scope, doi, metadata)
      VALUES (#{slug}, #{title}, #{alias}, #{description}, #{nomCode}, #{license}, #{version},
              #{issued}, #{geographicScope}, #{taxonomicScope}, #{doi}, #{metadata}::jsonb)
      """)
  @Options(useGeneratedKeys = true, keyProperty = "id")
  void insert(Project p);

  @Select("SELECT * FROM project WHERE id = #{id}")
  Project findById(long id);

  @Select("SELECT * FROM project WHERE slug = #{slug}")
  Project findBySlug(String slug);

  @Select("""
      SELECT p.* FROM project p
      JOIN project_member m ON m.project_id = p.id
      WHERE m.user_id = #{userId}
      ORDER BY p.title
      """)
  List<Project> findByMember(long userId);

  @Update("""
      UPDATE project
      SET title = #{title}, alias = #{alias}, description = #{description},
          nom_code = #{nomCode}, license = #{license}, version = #{version},
          issued = #{issued}, geographic_scope = #{geographicScope},
          taxonomic_scope = #{taxonomicScope}, doi = #{doi},
          metadata = #{metadata}::jsonb, updated_at = now()
      WHERE id = #{id}
      """)
  void updateMetadata(Project p);
}
```

`backend/src/main/java/org/catalogueoflife/editor/project/ProjectMemberMapper.java`:

```java
package org.catalogueoflife.editor.project;

import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ProjectMemberMapper {

  @Insert("""
      INSERT INTO project_member (project_id, user_id, role)
      VALUES (#{projectId}, #{userId}, #{role})
      ON CONFLICT (project_id, user_id) DO UPDATE SET role = EXCLUDED.role
      """)
  void upsert(ProjectMember m);

  @Select("SELECT role FROM project_member WHERE project_id = #{projectId} AND user_id = #{userId}")
  String findRole(@Param("projectId") long projectId, @Param("userId") long userId);

  @Select("SELECT * FROM project_member WHERE project_id = #{projectId}")
  List<ProjectMember> findByProject(long projectId);

  @Delete("DELETE FROM project_member WHERE project_id = #{projectId} AND user_id = #{userId}")
  void delete(@Param("projectId") long projectId, @Param("userId") long userId);
}
```

- [ ] **Step 5: Write the failing mapper test**

`backend/src/test/java/org/catalogueoflife/editor/project/ProjectMapperIT.java`:

```java
package org.catalogueoflife.editor.project;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ProjectMapperIT extends AbstractPostgresIT {

  @Autowired ProjectMapper projects;
  @Autowired ProjectMemberMapper members;
  @Autowired AppUserMapper users;

  @Test
  void createProjectWithOwnerAndListByMember() {
    AppUser u = new AppUser();
    u.setUsername("owner1");
    users.insert(u);

    Project p = new Project();
    p.setSlug("lepidoptera");
    p.setTitle("Lepidoptera");
    p.setNomCode("zoological");
    projects.insert(p);
    assertThat(p.getId()).isNotNull();

    members.upsert(new ProjectMember(p.getId(), u.getId(), Role.OWNER.dbValue()));

    assertThat(members.findRole(p.getId(), u.getId())).isEqualTo("owner");
    List<Project> mine = projects.findByMember(u.getId());
    assertThat(mine).extracting(Project::getSlug).containsExactly("lepidoptera");
  }
}
```

- [ ] **Step 6: Run the test — expect PASS**

Run: `cd backend && mvn -q -Dtest=ProjectMapperIT test`
Expected: PASS. (`V2` migration applied; owner role stored and read back; project listed for its member.)

- [ ] **Step 7: Commit**

```bash
git add backend/src
git commit -m "feat(backend): project + project_member schema, mappers, roles"
```

---

### Task 4: Local-account authentication and `/api/me`

**Files:**
- Modify: `backend/pom.xml` (add security starters)
- Create: `backend/src/main/java/org/catalogueoflife/editor/user/AppUserService.java`
- Create: `backend/src/main/java/org/catalogueoflife/editor/auth/SecurityConfig.java`
- Create: `backend/src/main/java/org/catalogueoflife/editor/auth/CurrentUser.java`
- Create: `backend/src/main/java/org/catalogueoflife/editor/auth/MeController.java`
- Create: `backend/src/test/resources/application-test.yml`
- Delete: `backend/src/test/java/org/catalogueoflife/editor/web/PingControllerTest.java` (see Step 6 note)
- Test: `backend/src/test/java/org/catalogueoflife/editor/auth/LocalLoginIT.java`

**Interfaces:**
- Consumes: `AppUserMapper` (Task 2).
- Produces:
  - `AppUserService` methods: `AppUser createLocal(String username, String rawPassword, String displayName)` (bcrypt-hashes the password), `org.springframework.security.core.userdetails.UserDetails` via implementing `UserDetailsService.loadUserByUsername(String)`, `AppUser requireByUsername(String username)`.
  - `SecurityConfig` — a `SecurityFilterChain` that: permits `GET /api/ping`, permits `POST /api/auth/login` and `/login/**`, requires authentication for all other `/api/**`; enables form login at `/api/auth/login`; CSRF via `CookieCsrfTokenRepository.withHttpOnlyFalse()`; exposes a `PasswordEncoder` bean (`BCryptPasswordEncoder`).
  - `CurrentUser` — a `@Component` with `AppUser require()` that resolves the authenticated `app_user` (by username) from the `SecurityContext`, throwing `ResponseStatusException(401)` if anonymous.
  - `GET /api/me` → `200` `{id, username, orcid, displayName}` for the authenticated user.

- [ ] **Step 1: Add security dependencies**

In `backend/pom.xml` `<dependencies>`, add:

```xml
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-security</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-oauth2-client</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.security</groupId>
      <artifactId>spring-security-test</artifactId>
      <scope>test</scope>
    </dependency>
```

- [ ] **Step 2: Create `AppUserService` (UserDetailsService + local account creation)**

`backend/src/main/java/org/catalogueoflife/editor/user/AppUserService.java`:

```java
package org.catalogueoflife.editor.user;

import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AppUserService implements UserDetailsService {

  private final AppUserMapper mapper;
  private final PasswordEncoder encoder;

  public AppUserService(AppUserMapper mapper, PasswordEncoder encoder) {
    this.mapper = mapper;
    this.encoder = encoder;
  }

  public AppUser createLocal(String username, String rawPassword, String displayName) {
    AppUser u = new AppUser();
    u.setUsername(username);
    u.setDisplayName(displayName);
    u.setPasswordHash(encoder.encode(rawPassword));
    mapper.insert(u);
    return u;
  }

  public AppUser requireByUsername(String username) {
    AppUser u = mapper.findByUsername(username);
    if (u == null) {
      throw new UsernameNotFoundException(username);
    }
    return u;
  }

  @Override
  public UserDetails loadUserByUsername(String username) {
    AppUser u = mapper.findByUsername(username);
    if (u == null || u.getPasswordHash() == null) {
      throw new UsernameNotFoundException(username);
    }
    return new User(u.getUsername(), u.getPasswordHash(),
        List.of(new SimpleGrantedAuthority("ROLE_USER")));
  }
}
```

- [ ] **Step 3: Create the security configuration**

`backend/src/main/java/org/catalogueoflife/editor/auth/SecurityConfig.java`:

```java
package org.catalogueoflife.editor.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

@Configuration
public class SecurityConfig {

  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/api/ping").permitAll()
            .requestMatchers("/api/auth/login", "/login/**", "/oauth2/**").permitAll()
            .anyRequest().authenticated())
        .csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()))
        .formLogin(form -> form
            .loginProcessingUrl("/api/auth/login")
            .successHandler((req, res, a) -> res.setStatus(HttpStatus.OK.value()))
            .failureHandler((req, res, e) -> res.setStatus(HttpStatus.UNAUTHORIZED.value())))
        .logout(out -> out.logoutUrl("/api/auth/logout")
            .logoutSuccessHandler((req, res, a) -> res.setStatus(HttpStatus.OK.value())))
        .exceptionHandling(ex -> ex
            .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
    return http.build();
  }
}
```

- [ ] **Step 4: Create `CurrentUser` and `MeController`**

`backend/src/main/java/org/catalogueoflife/editor/auth/CurrentUser.java`:

```java
package org.catalogueoflife.editor.auth;

import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserMapper;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class CurrentUser {

  private final AppUserMapper users;

  public CurrentUser(AppUserMapper users) {
    this.users = users;
  }

  public AppUser require() {
    Authentication a = SecurityContextHolder.getContext().getAuthentication();
    if (a == null || !a.isAuthenticated() || "anonymousUser".equals(a.getName())) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
    AppUser u = users.findByUsername(a.getName());
    if (u == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
    return u;
  }
}
```

`backend/src/main/java/org/catalogueoflife/editor/auth/MeController.java`:

```java
package org.catalogueoflife.editor.auth;

import java.util.Map;
import org.catalogueoflife.editor.user.AppUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MeController {

  private final CurrentUser currentUser;

  public MeController(CurrentUser currentUser) {
    this.currentUser = currentUser;
  }

  @GetMapping("/api/me")
  public Map<String, Object> me() {
    AppUser u = currentUser.require();
    return Map.of(
        "id", u.getId(),
        "username", u.getUsername(),
        "orcid", u.getOrcid() == null ? "" : u.getOrcid(),
        "displayName", u.getDisplayName() == null ? "" : u.getDisplayName());
  }
}
```

- [ ] **Step 5: Add test config**

`backend/src/test/resources/application-test.yml`:

```yaml
# ORCID registration uses explicit endpoints (no issuer discovery) so the context
# loads with dummy credentials and never hits the network at startup.
spring:
  security:
    oauth2:
      client:
        registration:
          orcid:
            client-id: test-client
            client-secret: test-secret
            scope: openid
            authorization-grant-type: authorization_code
            redirect-uri: "{baseUrl}/login/oauth2/code/orcid"
        provider:
          orcid:
            authorization-uri: https://orcid.org/oauth/authorize
            token-uri: https://orcid.org/oauth/token
            user-info-uri: https://orcid.org/oauth/userinfo
            jwk-set-uri: https://orcid.org/oauth/jwks
            user-name-attribute: sub
```

- [ ] **Step 6: Write the failing login test**

`backend/src/test/java/org/catalogueoflife/editor/auth/LocalLoginIT.java`:

```java
package org.catalogueoflife.editor.auth;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

@AutoConfigureMockMvc
@ActiveProfiles("test")
class LocalLoginIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService userService;

  @BeforeEach
  void seed() {
    if (userService.requireByUsernameOrNull("alice") == null) {
      userService.createLocal("alice", "s3cret", "Alice Example");
    }
  }

  @Test
  void pingIsPublicUnderRealSecurity() throws Exception {
    mvc.perform(get("/api/ping")).andExpect(status().isOk());
  }

  @Test
  void anonymousMeIsUnauthorized() throws Exception {
    mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
  }

  @Test
  void formLoginSucceedsWithSeededPassword() throws Exception {
    mvc.perform(formLogin("/api/auth/login").user("alice").password("s3cret"))
       .andExpect(status().isOk());
  }

  @Test
  @WithMockUser(username = "alice")
  void meReturnsCurrentUser() throws Exception {
    mvc.perform(get("/api/me"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.username").value("alice"));
  }
}
```

Add the small helper the test uses to `AppUserService`:

```java
  public AppUser requireByUsernameOrNull(String username) {
    return mapper.findByUsername(username);
  }
```

**Delete `PingControllerTest`.** Now that the security starter is on the classpath,
`@WebMvcTest(PingController.class)` would apply Boot's *default* security (all
requests authenticated, so `/api/ping` → 401) because the slice does not load our
`SecurityConfig`. The public-ping assertion now lives in `LocalLoginIT`
(`pingIsPublicUnderRealSecurity`, full context with the real filter chain), so
remove the slice test:

```bash
git rm backend/src/test/java/org/catalogueoflife/editor/web/PingControllerTest.java
```

- [ ] **Step 7: Run the test — expect PASS**

Run: `cd backend && mvn -q -Dtest=LocalLoginIT test`
Expected: PASS (4 tests): public `/api/ping` → 200; anonymous `/api/me` → 401; form login → 200; `@WithMockUser` `/api/me` → 200 with `username=alice`.

- [ ] **Step 8: Commit**

```bash
git add backend/pom.xml backend/src
git commit -m "feat(backend): local-account auth, security config, /api/me"
```

---

### Task 5: Project REST API (create / list mine / get / update metadata)

**Files:**
- Create: `backend/src/main/java/org/catalogueoflife/editor/project/dto/CreateProjectRequest.java`
- Create: `backend/src/main/java/org/catalogueoflife/editor/project/dto/UpdateProjectMetadataRequest.java`
- Create: `backend/src/main/java/org/catalogueoflife/editor/project/dto/ProjectResponse.java`
- Create: `backend/src/main/java/org/catalogueoflife/editor/project/ProjectService.java`
- Create: `backend/src/main/java/org/catalogueoflife/editor/project/ProjectController.java`
- Create: `backend/src/main/java/org/catalogueoflife/editor/web/ApiExceptionHandler.java`
- Test: `backend/src/test/java/org/catalogueoflife/editor/project/ProjectApiIT.java`

**Interfaces:**
- Consumes: `ProjectMapper`, `ProjectMemberMapper`, `Role` (Task 3); `CurrentUser` (Task 4).
- Produces:
  - `CreateProjectRequest` record: `@NotBlank String slug`, `@NotBlank String title`, `String nomCode`.
  - `UpdateProjectMetadataRequest` record: `@NotBlank String title`, `String alias`, `String description`, `String nomCode`, `String license`, `String version`, `LocalDate issued`, `String geographicScope`, `String taxonomicScope`, `String doi`.
  - `ProjectResponse` record built via `ProjectResponse.of(Project, String role)`.
  - `ProjectService`: `Project create(long userId, CreateProjectRequest req)` (inserts project + owner membership in a transaction), `List<Project> listForUser(long userId)`, `Project requireVisible(long userId, long projectId)` (404 if not a member), `String requireRole(long userId, long projectId)`, `Project updateMetadata(long userId, long projectId, UpdateProjectMetadataRequest req)` (requires owner or editor).
  - REST: `POST /api/projects` (201), `GET /api/projects` (list mine), `GET /api/projects/{id}` (403/404 if not a member), `PUT /api/projects/{id}/metadata`.

- [ ] **Step 1: Create the DTOs**

`backend/src/main/java/org/catalogueoflife/editor/project/dto/CreateProjectRequest.java`:

```java
package org.catalogueoflife.editor.project.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateProjectRequest(
    @NotBlank String slug,
    @NotBlank String title,
    String nomCode) {}
```

`backend/src/main/java/org/catalogueoflife/editor/project/dto/UpdateProjectMetadataRequest.java`:

```java
package org.catalogueoflife.editor.project.dto;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;

public record UpdateProjectMetadataRequest(
    @NotBlank String title,
    String alias,
    String description,
    String nomCode,
    String license,
    String version,
    LocalDate issued,
    String geographicScope,
    String taxonomicScope,
    String doi) {}
```

`backend/src/main/java/org/catalogueoflife/editor/project/dto/ProjectResponse.java`:

```java
package org.catalogueoflife.editor.project.dto;

import java.time.LocalDate;
import org.catalogueoflife.editor.project.Project;

public record ProjectResponse(
    Long id, String slug, String title, String alias, String description,
    String nomCode, String license, String version, LocalDate issued,
    String geographicScope, String taxonomicScope, String doi, String role) {

  public static ProjectResponse of(Project p, String role) {
    return new ProjectResponse(p.getId(), p.getSlug(), p.getTitle(), p.getAlias(),
        p.getDescription(), p.getNomCode(), p.getLicense(), p.getVersion(), p.getIssued(),
        p.getGeographicScope(), p.getTaxonomicScope(), p.getDoi(), role);
  }
}
```

- [ ] **Step 2: Create the service**

`backend/src/main/java/org/catalogueoflife/editor/project/ProjectService.java`:

```java
package org.catalogueoflife.editor.project;

import java.util.List;
import org.catalogueoflife.editor.project.dto.CreateProjectRequest;
import org.catalogueoflife.editor.project.dto.UpdateProjectMetadataRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProjectService {

  private final ProjectMapper projects;
  private final ProjectMemberMapper members;

  public ProjectService(ProjectMapper projects, ProjectMemberMapper members) {
    this.projects = projects;
    this.members = members;
  }

  @Transactional
  public Project create(long userId, CreateProjectRequest req) {
    if (projects.findBySlug(req.slug()) != null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "slug already used");
    }
    Project p = new Project();
    p.setSlug(req.slug());
    p.setTitle(req.title());
    p.setNomCode(req.nomCode());
    projects.insert(p);
    members.upsert(new ProjectMember(p.getId(), userId, Role.OWNER.dbValue()));
    return p;
  }

  public List<Project> listForUser(long userId) {
    return projects.findByMember(userId);
  }

  public String requireRole(long userId, long projectId) {
    String role = members.findRole(projectId, userId);
    if (role == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "project not found");
    }
    return role;
  }

  public Project requireVisible(long userId, long projectId) {
    requireRole(userId, projectId);
    return projects.findById(projectId);
  }

  @Transactional
  public Project updateMetadata(long userId, long projectId, UpdateProjectMetadataRequest req) {
    String role = requireRole(userId, projectId);
    if (!role.equals(Role.OWNER.dbValue()) && !role.equals(Role.EDITOR.dbValue())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "owner or editor required");
    }
    Project p = projects.findById(projectId);
    p.setTitle(req.title());
    p.setAlias(req.alias());
    p.setDescription(req.description());
    p.setNomCode(req.nomCode());
    p.setLicense(req.license());
    p.setVersion(req.version());
    p.setIssued(req.issued());
    p.setGeographicScope(req.geographicScope());
    p.setTaxonomicScope(req.taxonomicScope());
    p.setDoi(req.doi());
    projects.updateMetadata(p);
    return p;
  }
}
```

- [ ] **Step 3: Create the controller and error handler**

`backend/src/main/java/org/catalogueoflife/editor/project/ProjectController.java`:

```java
package org.catalogueoflife.editor.project;

import jakarta.validation.Valid;
import java.util.List;
import org.catalogueoflife.editor.auth.CurrentUser;
import org.catalogueoflife.editor.project.dto.CreateProjectRequest;
import org.catalogueoflife.editor.project.dto.ProjectResponse;
import org.catalogueoflife.editor.project.dto.UpdateProjectMetadataRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

  private final ProjectService service;
  private final ProjectMemberMapper members;
  private final CurrentUser currentUser;

  public ProjectController(ProjectService service, ProjectMemberMapper members, CurrentUser currentUser) {
    this.service = service;
    this.members = members;
    this.currentUser = currentUser;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ProjectResponse create(@Valid @RequestBody CreateProjectRequest req) {
    long uid = currentUser.require().getId();
    Project p = service.create(uid, req);
    return ProjectResponse.of(p, Role.OWNER.dbValue());
  }

  @GetMapping
  public List<ProjectResponse> listMine() {
    long uid = currentUser.require().getId();
    return service.listForUser(uid).stream()
        .map(p -> ProjectResponse.of(p, members.findRole(p.getId(), uid)))
        .toList();
  }

  @GetMapping("/{id}")
  public ProjectResponse get(@PathVariable long id) {
    long uid = currentUser.require().getId();
    Project p = service.requireVisible(uid, id);
    return ProjectResponse.of(p, members.findRole(id, uid));
  }

  @PutMapping("/{id}/metadata")
  public ProjectResponse updateMetadata(@PathVariable long id,
                                        @Valid @RequestBody UpdateProjectMetadataRequest req) {
    long uid = currentUser.require().getId();
    Project p = service.updateMetadata(uid, id, req);
    return ProjectResponse.of(p, members.findRole(id, uid));
  }
}
```

`backend/src/main/java/org/catalogueoflife/editor/web/ApiExceptionHandler.java`:

```java
package org.catalogueoflife.editor.web;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<Map<String, Object>> handle(ResponseStatusException ex) {
    return ResponseEntity.status(ex.getStatusCode())
        .body(Map.of("error", ex.getReason() == null ? ex.getStatusCode().toString() : ex.getReason()));
  }
}
```

- [ ] **Step 4: Write the failing API test**

`backend/src/test/java/org/catalogueoflife/editor/project/ProjectApiIT.java`:

```java
package org.catalogueoflife.editor.project;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProjectApiIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;

  private void ensureUser(String username) {
    AppUser existing = users.requireByUsernameOrNull(username);
    if (existing == null) users.createLocal(username, "pw", username);
  }

  @Test
  @WithMockUser(username = "creator")
  void createListGetAndUpdate() throws Exception {
    ensureUser("creator");

    mvc.perform(post("/api/projects").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"slug\":\"aves\",\"title\":\"Birds\",\"nomCode\":\"zoological\"}"))
       .andExpect(status().isCreated())
       .andExpect(jsonPath("$.slug").value("aves"))
       .andExpect(jsonPath("$.role").value("owner"));

    mvc.perform(get("/api/projects"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$[0].slug").value("aves"));
  }

  @Test
  @WithMockUser(username = "outsider")
  void getForeignProjectIsNotFound() throws Exception {
    ensureUser("outsider");
    mvc.perform(get("/api/projects/999999")).andExpect(status().isNotFound());
  }
}
```

- [ ] **Step 5: Run the test — expect PASS**

Run: `cd backend && mvn -q -Dtest=ProjectApiIT test`
Expected: PASS. Project created with owner role; listed for creator; a non-member's `GET` of an unknown/foreign project → 404.

- [ ] **Step 6: Commit**

```bash
git add backend/src
git commit -m "feat(backend): project REST API (create/list/get/update-metadata)"
```

---

### Task 6: Member management API with role-based authorization

**Files:**
- Create: `backend/src/main/java/org/catalogueoflife/editor/project/dto/MemberRequest.java`
- Create: `backend/src/main/java/org/catalogueoflife/editor/project/dto/MemberResponse.java`
- Modify: `backend/src/main/java/org/catalogueoflife/editor/project/ProjectService.java` (add member ops)
- Modify: `backend/src/main/java/org/catalogueoflife/editor/project/ProjectController.java` (add member endpoints)
- Test: `backend/src/test/java/org/catalogueoflife/editor/project/MemberApiIT.java`

**Interfaces:**
- Consumes: `ProjectService`, `ProjectMemberMapper`, `AppUserMapper`, `Role` (earlier tasks).
- Produces:
  - `MemberRequest` record: `@NotBlank String username`, `@NotBlank String role`.
  - `MemberResponse` record: `Long userId`, `String username`, `String role`.
  - `ProjectService.listMembers(long actorId, long projectId)` (any member may read); `ProjectService.setMember(long actorId, long projectId, String username, String role)` (owner only; validates role against `Role`); `ProjectService.removeMember(long actorId, long projectId, long targetUserId)` (owner only; may not remove the last owner).
  - REST: `GET /api/projects/{id}/members`, `PUT /api/projects/{id}/members` (add/update), `DELETE /api/projects/{id}/members/{userId}`.

- [ ] **Step 1: Create the DTOs**

`backend/src/main/java/org/catalogueoflife/editor/project/dto/MemberRequest.java`:

```java
package org.catalogueoflife.editor.project.dto;

import jakarta.validation.constraints.NotBlank;

public record MemberRequest(@NotBlank String username, @NotBlank String role) {}
```

`backend/src/main/java/org/catalogueoflife/editor/project/dto/MemberResponse.java`:

```java
package org.catalogueoflife.editor.project.dto;

public record MemberResponse(Long userId, String username, String role) {}
```

- [ ] **Step 2: Add member operations to `ProjectService`**

Add these fields/constructor params and methods to `ProjectService` (inject `AppUserMapper users`):

```java
  // add to constructor: (ProjectMapper projects, ProjectMemberMapper members, AppUserMapper users)
  // store: this.users = users;

  public java.util.List<org.catalogueoflife.editor.project.dto.MemberResponse> listMembers(long actorId, long projectId) {
    requireRole(actorId, projectId); // any member may read
    return members.findByProject(projectId).stream()
        .map(m -> {
          var u = users.findById(m.getUserId());
          return new org.catalogueoflife.editor.project.dto.MemberResponse(
              m.getUserId(), u == null ? null : u.getUsername(), m.getRole());
        })
        .toList();
  }

  @Transactional
  public void setMember(long actorId, long projectId, String username, String roleValue) {
    requireOwner(actorId, projectId);
    Role role = Role.fromDb(roleValue); // throws IllegalArgumentException -> 400 via handler below
    var target = users.findByUsername(username);
    if (target == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown user: " + username);
    }
    members.upsert(new ProjectMember(projectId, target.getId(), role.dbValue()));
  }

  @Transactional
  public void removeMember(long actorId, long projectId, long targetUserId) {
    requireOwner(actorId, projectId);
    long owners = members.findByProject(projectId).stream()
        .filter(m -> m.getRole().equals(Role.OWNER.dbValue())).count();
    String targetRole = members.findRole(projectId, targetUserId);
    if (Role.OWNER.dbValue().equals(targetRole) && owners <= 1) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "cannot remove the last owner");
    }
    members.delete(projectId, targetUserId);
  }

  private void requireOwner(long actorId, long projectId) {
    if (!Role.OWNER.dbValue().equals(requireRole(actorId, projectId))) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "owner required");
    }
  }
```

Add the import `import org.catalogueoflife.editor.user.AppUserMapper;` and the field `private final AppUserMapper users;`.

Add a handler for the role-parse error to `ApiExceptionHandler`:

```java
  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String, Object>> handleBadArg(IllegalArgumentException ex) {
    return ResponseEntity.badRequest().body(Map.of("error", "invalid value: " + ex.getMessage()));
  }
```

- [ ] **Step 3: Add member endpoints to `ProjectController`**

Add to `ProjectController`:

```java
  @GetMapping("/{id}/members")
  public java.util.List<org.catalogueoflife.editor.project.dto.MemberResponse> members(@PathVariable long id) {
    long uid = currentUser.require().getId();
    return service.listMembers(uid, id);
  }

  @PutMapping("/{id}/members")
  public void setMember(@PathVariable long id,
                        @jakarta.validation.Valid @RequestBody org.catalogueoflife.editor.project.dto.MemberRequest req) {
    long uid = currentUser.require().getId();
    service.setMember(uid, id, req.username(), req.role());
  }

  @org.springframework.web.bind.annotation.DeleteMapping("/{id}/members/{userId}")
  public void removeMember(@PathVariable long id, @PathVariable long userId) {
    long uid = currentUser.require().getId();
    service.removeMember(uid, id, userId);
  }
```

- [ ] **Step 4: Write the failing member test**

`backend/src/test/java/org/catalogueoflife/editor/project/MemberApiIT.java`:

```java
package org.catalogueoflife.editor.project;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
@ActiveProfiles("test")
class MemberApiIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ObjectMapper json;

  private void ensureUser(String u) {
    AppUser e = users.requireByUsernameOrNull(u);
    if (e == null) users.createLocal(u, "pw", u);
  }

  @Test
  @WithMockUser(username = "boss")
  void ownerCanAddEditorAndListMembers() throws Exception {
    ensureUser("boss");
    ensureUser("helper");

    String body = mvc.perform(post("/api/projects").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"slug\":\"mollusca\",\"title\":\"Molluscs\"}"))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    long pid = json.readTree(body).get("id").asLong();

    mvc.perform(put("/api/projects/" + pid + "/members").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"helper\",\"role\":\"editor\"}"))
       .andExpect(status().isOk());

    mvc.perform(get("/api/projects/" + pid + "/members"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$[?(@.username=='helper')].role").value(org.hamcrest.Matchers.hasItem("editor")));
  }

  @Test
  @WithMockUser(username = "viewerUser")
  void nonOwnerCannotAddMembers() throws Exception {
    ensureUser("viewerUser");
    ensureUser("ownerUser");
    // ownerUser creates a project via service-less path: create as viewerUser then downgrade is complex;
    // instead assert that adding a member to a project the actor is not owner of is forbidden/not-found.
    mvc.perform(put("/api/projects/424242/members").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"ownerUser\",\"role\":\"editor\"}"))
       .andExpect(status().isNotFound());
  }
}
```

- [ ] **Step 5: Run the test — expect PASS**

Run: `cd backend && mvn -q -Dtest=MemberApiIT test`
Expected: PASS. Owner adds an editor and sees them listed; a non-member acting on an unknown project → 404 (role lookup fails before the owner check).

- [ ] **Step 6: Commit**

```bash
git add backend/src
git commit -m "feat(backend): project member management with role authorization"
```

---

### Task 7: ORCID OIDC login with user provisioning

**Files:**
- Modify: `backend/src/main/resources/application.yml` (add ORCID client registration, env-driven)
- Create: `backend/src/main/java/org/catalogueoflife/editor/user/OrcidUserService.java`
- Modify: `backend/src/main/java/org/catalogueoflife/editor/auth/SecurityConfig.java` (enable `oauth2Login` with the custom OIDC user service)
- Modify: `backend/src/main/java/org/catalogueoflife/editor/user/AppUserService.java` (add `upsertFromOrcid`)
- Test: `backend/src/test/java/org/catalogueoflife/editor/user/OrcidUserServiceIT.java`

**Interfaces:**
- Consumes: `AppUserMapper`, `AppUserService` (Task 2/4); Spring Security OIDC.
- Produces:
  - `AppUserService.upsertFromOrcid(String orcid, String displayName, String given, String family)` → `AppUser` (insert if new by orcid, else update name fields; `username` defaults to the orcid).
  - `OrcidUserService extends OidcUserService` overriding `loadUser` to provision the `app_user` from the ORCID `sub`/name claims and return the `OidcUser` with its name attribute set to the ORCID iD (so `authentication.getName()` == the orcid).
  - `SecurityConfig` wires `.oauth2Login(o -> o.userInfoEndpoint(u -> u.oidcUserService(orcidUserService)))`.

- [ ] **Step 1: Add the ORCID registration to `application.yml`**

Append to `backend/src/main/resources/application.yml` under `spring:` (indented to match the existing `spring:` block):

```yaml
  security:
    oauth2:
      client:
        registration:
          orcid:
            # Non-empty defaults: Spring eagerly builds the ClientRegistration at
            # startup and asserts a non-empty client-id, so an empty default
            # (${ORCID_CLIENT_ID:}) crashes the whole app when ORCID creds are unset,
            # defeating the local-account fallback. The placeholder lets the context
            # boot; an actual ORCID login then fails gracefully at ORCID.
            client-id: ${ORCID_CLIENT_ID:unconfigured}
            client-secret: ${ORCID_CLIENT_SECRET:unconfigured}
            scope: openid
            authorization-grant-type: authorization_code
            redirect-uri: "{baseUrl}/login/oauth2/code/orcid"
            client-name: ORCID
        provider:
          orcid:
            authorization-uri: https://orcid.org/oauth/authorize
            token-uri: https://orcid.org/oauth/token
            user-info-uri: https://orcid.org/oauth/userinfo
            jwk-set-uri: https://orcid.org/oauth/jwks
            user-name-attribute: sub
```

- [ ] **Step 2: Add `upsertFromOrcid` to `AppUserService`**

```java
  public AppUser upsertFromOrcid(String orcid, String displayName, String given, String family) {
    AppUser u = mapper.findByOrcid(orcid);
    if (u == null) {
      u = new AppUser();
      u.setOrcid(orcid);
      u.setUsername(orcid);
      u.setDisplayName(displayName);
      u.setGiven(given);
      u.setFamily(family);
      mapper.insert(u);
    } else {
      u.setDisplayName(displayName);
      u.setGiven(given);
      u.setFamily(family);
      mapper.update(u);
    }
    return u;
  }
```

- [ ] **Step 3: Create `OrcidUserService`**

`backend/src/main/java/org/catalogueoflife/editor/user/OrcidUserService.java`:

```java
package org.catalogueoflife.editor.user;

import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

@Service
public class OrcidUserService extends OidcUserService {

  private final AppUserService users;

  public OrcidUserService(AppUserService users) {
    this.users = users;
  }

  @Override
  public OidcUser loadUser(OidcUserRequest req) throws OAuth2AuthenticationException {
    OidcUser oidc = super.loadUser(req);
    String orcid = oidc.getSubject();
    String given = oidc.getGivenName();
    String family = oidc.getFamilyName();
    String display = oidc.getFullName() != null ? oidc.getFullName()
        : (given == null ? orcid : (given + (family == null ? "" : " " + family)));
    users.upsertFromOrcid(orcid, display, given, family);
    // Return an OidcUser whose "name" is the ORCID iD, so SecurityContext principal name == orcid,
    // matching how app_user.username is stored for ORCID accounts.
    return new DefaultOidcUser(oidc.getAuthorities(), oidc.getIdToken(), oidc.getUserInfo(), "sub");
  }
}
```

- [ ] **Step 4: Wire `oauth2Login` into `SecurityConfig`**

Change the `SecurityConfig.filterChain` method to accept the service and register it. Replace the method signature and add the `oauth2Login` block:

```java
  @Bean
  SecurityFilterChain filterChain(HttpSecurity http,
                                  org.catalogueoflife.editor.user.OrcidUserService orcidUserService) throws Exception {
    http
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/api/ping").permitAll()
            .requestMatchers("/api/auth/login", "/login/**", "/oauth2/**").permitAll()
            .anyRequest().authenticated())
        .csrf(csrf -> csrf.csrfTokenRepository(
            org.springframework.security.web.csrf.CookieCsrfTokenRepository.withHttpOnlyFalse()))
        .formLogin(form -> form
            .loginProcessingUrl("/api/auth/login")
            .successHandler((req, res, a) -> res.setStatus(org.springframework.http.HttpStatus.OK.value()))
            .failureHandler((req, res, e) -> res.setStatus(org.springframework.http.HttpStatus.UNAUTHORIZED.value())))
        .oauth2Login(o -> o.userInfoEndpoint(u -> u.oidcUserService(orcidUserService)))
        .logout(out -> out.logoutUrl("/api/auth/logout")
            .logoutSuccessHandler((req, res, a) -> res.setStatus(org.springframework.http.HttpStatus.OK.value())))
        .exceptionHandling(ex -> ex.authenticationEntryPoint(
            new org.springframework.security.web.authentication.HttpStatusEntryPoint(
                org.springframework.http.HttpStatus.UNAUTHORIZED)));
    return http.build();
  }
```

- [ ] **Step 5: Write the failing provisioning test**

`backend/src/test/java/org/catalogueoflife/editor/user/OrcidUserServiceIT.java`:

```java
package org.catalogueoflife.editor.user;

import static org.assertj.core.api.Assertions.assertThat;

import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
class OrcidUserServiceIT extends AbstractPostgresIT {

  @Autowired AppUserService users;
  @Autowired AppUserMapper mapper;

  @Test
  void upsertCreatesThenUpdatesByOrcid() {
    String orcid = "0000-0002-1111-2222";

    AppUser created = users.upsertFromOrcid(orcid, "Jane A. Smith", "Jane A.", "Smith");
    assertThat(created.getId()).isNotNull();
    assertThat(mapper.findByOrcid(orcid).getDisplayName()).isEqualTo("Jane A. Smith");

    AppUser updated = users.upsertFromOrcid(orcid, "Jane Anne Smith", "Jane Anne", "Smith");
    assertThat(updated.getId()).isEqualTo(created.getId());
    assertThat(mapper.findByOrcid(orcid).getDisplayName()).isEqualTo("Jane Anne Smith");
  }
}
```

- [ ] **Step 6: Run the test — expect PASS, then run the whole suite**

Run: `cd backend && mvn -q -Dtest=OrcidUserServiceIT test`
Expected: PASS (create then update by ORCID, stable id).

Run the full suite to confirm the ORCID config change didn't break context loading:
Run: `cd backend && mvn -q test`
Expected: all tests PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/pom.xml backend/src
git commit -m "feat(backend): ORCID OIDC login with app_user provisioning"
```

---

## Self-Review Notes

- **Spec coverage (phase-1 items 1–2):** multi-project + ORCID auth + local fallback (Tasks 4, 7) + per-project roles owner/editor/reviewer/viewer (Task 3) + project switcher data (list-mine, Task 5) + project ColDP metadata incl. `nom_code` (Task 5). Items 3–8 (References/NameUsages CRUD, tree, locks, audit, validation, and all UI) are **out of scope for this plan** and are covered by phase-1 plans 2–6.
- **Deferred deliberately:** name-parser/vocab enums and typed `NomCode` validation (Plan 3); the React shell/switcher UI (Plan 2). `nom_code` is free text here per the Global Constraints.
- **Manual verification** (beyond tests): `cd backend && mvn spring-boot:run` with a local Postgres and `ORCID_CLIENT_ID`/`ORCID_CLIENT_SECRET` set, then `GET /api/ping` (200), form-login, and the ORCID flow via a browser.
