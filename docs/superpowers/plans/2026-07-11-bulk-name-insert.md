# Bulk Name Insert Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let an editor add many names at once — a pasted/uploaded GBIF text-tree (a plain name list is the degenerate all-roots case) — either synchronously under a target taxon (Path A) or, for large trees, asynchronously as a staging project via the existing ColDP import + merge (Path B).

**Architecture:** Path A adds a `BulkInsertService` that parses with `org.gbif:text-tree`, previews (no writes), then inserts by **reusing `NameUsageService.create` + `linkSynonym`** inside one transaction. Path B adds a **source-format-adapter seam** on the existing `ImportRunService`: a text-tree adapter converts the tree to a ColDP `NameUsage.tsv` + `metadata.yaml` in the import temp dir, and the unchanged `@Async run()` loads it into a staging project. DwC-A later slots in as a third adapter.

**Tech Stack:** Java 25 (Spring Boot), Postgres 17 (Testcontainers ITs), MyBatis, `org.gbif:text-tree:1.7.0`, React + Mantine + TanStack Query + Vitest.

## Global Constraints

- Dependency: `org.gbif:text-tree:1.7.0` (already resolvable from the GBIF repo; transitively pulls commons-lang3/commons-io/slf4j/name-parser — all already on the classpath).
- Path A total-name cap: **1000** (accepted + synonyms across all depths). Over → HTTP 400, and the preview reports it as a blocking error pointing to Path B.
- Duplicates are **flagged in the preview but inserted anyway** — never skipped or blocked.
- Path A insert is **one transaction, all-or-nothing**.
- Text-tree grammar (lib-enforced): plain list = all roots; **2-space** indentation per level (tabs/over-indent rejected with a line-numbered message); `[rank]` suffix declares rank; `=` (and `*`) prefix → synonym; `†` prefix → extinct. `SimpleTreeNode` fields used: `id` (long), `name`, `rank` (nullable), `extinct` (boolean), `children`, `synonyms`.
- Status mapping: accepted node → `Status.ACCEPTED`; synonym node → `Status.SYNONYM` linked to its accepted ancestor via `synonym_accepted`.
- Editor role required for every write and for preview (owner or editor).
- Path B makes **no change** to `ImportRunService.run()`, `loadTransactional`, `ImportRunRecovery`, or the merge. The text-tree adapter writes both `NameUsage.tsv` **and** `metadata.yaml` into the dir (the import requires `metadata.yaml`).
- Build/test: backend `JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw ...` from `backend/`; frontend `npx tsc -b` + `npx vitest run` from `frontend/`. Commit directly to `main` (project convention); never stage `todo.md`.

---

## Phase 1 — Path A: synchronous, taxon-anchored bulk insert

### Task 1: Add the text-tree dependency + parsing smoke test

**Files:**
- Modify: `backend/pom.xml` (dependencies section)
- Test: `backend/src/test/java/org/catalogueoflife/editor/name/bulk/TxtTreeLibTest.java`

**Interfaces:**
- Produces: the `org.gbif.txtree.Tree` / `SimpleTreeNode` API is available on the main classpath.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/org/catalogueoflife/editor/name/bulk/TxtTreeLibTest.java`:

```java
package org.catalogueoflife.editor.name.bulk;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringReader;
import org.gbif.txtree.SimpleTreeNode;
import org.gbif.txtree.Tree;
import org.junit.jupiter.api.Test;

// Pins the text-tree behaviours Path A relies on: a plain list = all roots; the [rank] suffix is
// parsed into node.rank; 2-space indentation nests children; the = prefix makes a synonym; the
// dagger marks extinct.
class TxtTreeLibTest {

  @Test
  void plainListIsAllRoots() throws Exception {
    Tree<SimpleTreeNode> t = Tree.simple(new StringReader("Aus bus\nAus cus\n"));
    assertThat(t.getRoot()).hasSize(2);
    assertThat(t.getRoot().get(0).name).isEqualTo("Aus bus");
    assertThat(t.getRoot().get(0).rank).isNull();
  }

  @Test
  void rankSuffixParsed() throws Exception {
    Tree<SimpleTreeNode> t = Tree.simple(new StringReader("Panthera leo [species]\n"));
    assertThat(t.getRoot().get(0).name).isEqualTo("Panthera leo");
    assertThat(t.getRoot().get(0).rank).isEqualTo("species");
  }

  @Test
  void indentationSynonymAndExtinct() throws Exception {
    // 2 spaces per level; = prefix synonym; dagger extinct
    Tree<SimpleTreeNode> t = Tree.simple(new StringReader(
        "Panthera [genus]\n  Panthera leo [species]\n    =Felis leo\n  †Panthera spelaea [species]\n"));
    assertThat(t.getRoot()).hasSize(1);
    SimpleTreeNode genus = t.getRoot().get(0);
    assertThat(genus.children).hasSize(2);
    SimpleTreeNode leo = genus.children.get(0);
    assertThat(leo.synonyms).extracting(n -> n.name).containsExactly("Felis leo");
    assertThat(genus.children.get(1).extinct).isTrue();
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw -q -Dtest=TxtTreeLibTest test`
Expected: FAIL to compile — `package org.gbif.txtree does not exist`.

- [ ] **Step 3: Add the dependency**

In `backend/pom.xml`, add to the `<dependencies>` block (near the other `org.catalogueoflife`/`org.gbif` entries):

```xml
    <dependency>
      <groupId>org.gbif</groupId>
      <artifactId>text-tree</artifactId>
      <version>1.7.0</version>
    </dependency>
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw -q -Dtest=TxtTreeLibTest test`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/pom.xml backend/src/test/java/org/catalogueoflife/editor/name/bulk/TxtTreeLibTest.java
git commit -m "build: add org.gbif:text-tree dep + parsing smoke test"
```

---

### Task 2: Bulk preview endpoint (parse, resolve, count, dup-flag — no writes)

**Files:**
- Create: `backend/src/main/java/org/catalogueoflife/editor/name/bulk/dto/BulkInsertRequest.java`
- Create: `backend/src/main/java/org/catalogueoflife/editor/name/bulk/dto/BulkPreviewResponse.java`
- Create: `backend/src/main/java/org/catalogueoflife/editor/name/bulk/BulkMode.java`
- Create: `backend/src/main/java/org/catalogueoflife/editor/name/bulk/BulkInsertService.java`
- Create: `backend/src/main/java/org/catalogueoflife/editor/name/bulk/BulkInsertController.java`
- Modify: `backend/src/main/java/org/catalogueoflife/editor/name/NameUsageMapper.java` (add `findChildScientificNames`)
- Test: `backend/src/test/java/org/catalogueoflife/editor/name/bulk/BulkPreviewApiIT.java`

**Interfaces:**
- Consumes (existing, exact signatures): `ProjectService.requireRole(int,int) -> String`; `Role.OWNER.dbValue()`/`Role.EDITOR.dbValue()`; `ProjectMapper.findById(int) -> Project` with `Project.getNomCode() -> NomCode`; `NameUsageMapper.findByIdInProject(int projectId, int id) -> NameUsage`; `NameParserService.parseInto(NameUsage, NomCode)`; `NameUsageService.listSynonyms(int userId,int projectId,int id) -> List<NameUsageResponse>` (each has `scientificName()`); `Status.ACCEPTED`.
- Produces: `BulkInsertService.preview(int userId, int projectId, BulkInsertRequest req) -> BulkPreviewResponse`; the private helpers `resolveTarget`, `toUsage`, `countNodes`, `previewNodes` reused by Task 3.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/org/catalogueoflife/editor/name/bulk/BulkPreviewApiIT.java`:

```java
package org.catalogueoflife.editor.name.bulk;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@AutoConfigureMockMvc
@WithMockUser(username = "bulkPrev")
class BulkPreviewApiIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ObjectMapper json;

  private void ensureUser(String u) {
    if (users.requireByUsernameOrNull(u) == null) users.createLocal(u, "pw", u);
  }

  // Creates a project + one accepted genus, returns [projectId, genusId].
  private int[] seed() throws Exception {
    ensureUser("bulkPrev");
    String pj = mvc.perform(post("/api/projects").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"BulkP\",\"nomCode\":\"zoological\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    int pid = json.readTree(pj).get("id").asInt();
    String gj = mvc.perform(post("/api/projects/" + pid + "/usages").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scientificName\":\"Panthera\",\"rank\":\"genus\",\"status\":\"ACCEPTED\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return new int[] {pid, json.readTree(gj).get("id").asInt()};
  }

  private String body(int targetId, String mode, String text) throws Exception {
    return json.writeValueAsString(java.util.Map.of("targetId", targetId, "mode", mode, "text", text));
  }

  @Test
  void previewPlainListAsChildren() throws Exception {
    int[] s = seed();
    mvc.perform(post("/api/projects/" + s[0] + "/usages/bulk/preview").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(s[1], "children", "Panthera leo [species]\nPanthera onca [species]\n")))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.valid").value(true))
       .andExpect(jsonPath("$.total").value(2))
       .andExpect(jsonPath("$.accepted").value(2))
       .andExpect(jsonPath("$.synonyms").value(0))
       .andExpect(jsonPath("$.nodes[0].name").value("Panthera leo"))
       .andExpect(jsonPath("$.nodes[0].rank").value("species"))
       .andExpect(jsonPath("$.nodes[0].status").value("ACCEPTED"));
  }

  @Test
  void previewInfersRankForBareBinomial() throws Exception {
    int[] s = seed();
    mvc.perform(post("/api/projects/" + s[0] + "/usages/bulk/preview").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(s[1], "children", "Panthera leo\n")))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.nodes[0].rank").value("species"));
  }

  @Test
  void previewIndentedWithSynonym() throws Exception {
    int[] s = seed();
    String tree = "Panthera leo [species]\n  =Felis leo\n";
    mvc.perform(post("/api/projects/" + s[0] + "/usages/bulk/preview").with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(body(s[1], "children", tree)))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.total").value(2))
       .andExpect(jsonPath("$.accepted").value(1))
       .andExpect(jsonPath("$.synonyms").value(1))
       .andExpect(jsonPath("$.nodes[0].synonyms[0].name").value("Felis leo"))
       .andExpect(jsonPath("$.nodes[0].synonyms[0].status").value("SYNONYM"));
  }

  @Test
  void previewBadIndentationIsInvalid() throws Exception {
    int[] s = seed();
    // 4-space over-indent jump is rejected by the parser
    mvc.perform(post("/api/projects/" + s[0] + "/usages/bulk/preview").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(s[1], "children", "Aaa [genus]\n    Bbb ccc [species]\n")))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.valid").value(false))
       .andExpect(jsonPath("$.error").isNotEmpty());
  }

  @Test
  void previewSynonymyModeRejectsIndentation() throws Exception {
    int[] s = seed();
    mvc.perform(post("/api/projects/" + s[0] + "/usages/bulk/preview").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(s[1], "synonyms", "Panthera leo\n  Panthera onca\n")))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.valid").value(false))
       .andExpect(jsonPath("$.error").isNotEmpty());
  }

  @Test
  void previewWritesNothing() throws Exception {
    int[] s = seed();
    mvc.perform(post("/api/projects/" + s[0] + "/usages/bulk/preview").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(s[1], "children", "Panthera leo [species]\nPanthera onca [species]\n")))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.total").value(2));
    // A preview must not create anything: the project still has exactly the one seeded genus.
    mvc.perform(get("/api/projects/" + s[0] + "/usages"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.total").value(1));
  }
}
```

Note: `GET /api/projects/{pid}/usages` (no `q`) returns a `UsagePage` whose `total` counts every usage in the project — a strong "nothing was written" assertion (stays 1, the seeded genus). If the codebase exposes a direct "children of X" read, asserting that is 0 is equivalent; the project-count check works regardless.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw -q -Dtest=BulkPreviewApiIT test`
Expected: FAIL — 404/compile (no `/usages/bulk/preview` endpoint / classes missing).

- [ ] **Step 3: Create the DTOs + mode enum**

`backend/src/main/java/org/catalogueoflife/editor/name/bulk/dto/BulkInsertRequest.java`:

```java
package org.catalogueoflife.editor.name.bulk.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

// One bulk request, shared by /usages/bulk/preview and /usages/bulk. `mode` is "children" or
// "synonyms" (parsed tolerantly server-side). `text` is a GBIF text-tree (a plain name list is
// the degenerate all-roots case).
public record BulkInsertRequest(
    @NotNull Integer targetId,
    @NotBlank String mode,
    @NotBlank String text) {}
```

`backend/src/main/java/org/catalogueoflife/editor/name/bulk/BulkMode.java`:

```java
package org.catalogueoflife.editor.name.bulk;

// CHILDREN: each top-level node becomes an accepted child of the target (nested hierarchy + = synonyms
// preserved). SYNONYMS: the input must be a flat list; each line becomes a synonym of the target.
public enum BulkMode {
  CHILDREN,
  SYNONYMS
}
```

`backend/src/main/java/org/catalogueoflife/editor/name/bulk/dto/BulkPreviewResponse.java`:

```java
package org.catalogueoflife.editor.name.bulk.dto;

import java.util.List;

// Structured preview: `valid` gates the confirm button; on a parse error `valid=false` + `error`
// carries the library's line-numbered message. Counts drive the summary; `nodes` renders the tree.
public record BulkPreviewResponse(
    boolean valid,
    String error,
    int total,
    int accepted,
    int synonyms,
    int duplicates,
    List<PreviewNode> nodes) {

  public record PreviewNode(
      String name,
      String rank,
      String status,
      boolean extinct,
      boolean duplicate,
      List<PreviewNode> children,
      List<PreviewNode> synonyms) {}

  static BulkPreviewResponse invalid(String error) {
    return new BulkPreviewResponse(false, error, 0, 0, 0, 0, List.of());
  }
}
```

- [ ] **Step 4: Add the child-name lookup to `NameUsageMapper`**

In `backend/src/main/java/org/catalogueoflife/editor/name/NameUsageMapper.java`, next to `findChildIds` (line ~205), add:

```java
  // Direct accepted children's names (for bulk-insert duplicate flagging). Parent links are only
  // ever accepted->accepted, so this returns the target's accepted children.
  @Select("SELECT scientific_name FROM name_usage WHERE project_id = #{projectId} AND parent_id = #{parentId}")
  java.util.List<String> findChildScientificNames(@Param("projectId") int projectId,
      @Param("parentId") int parentId);
```

- [ ] **Step 5: Create `BulkInsertService` (preview only for now)**

`backend/src/main/java/org/catalogueoflife/editor/name/bulk/BulkInsertService.java`:

```java
package org.catalogueoflife.editor.name.bulk;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.name.NameUsageMapper;
import org.catalogueoflife.editor.name.NameUsageService;
import org.catalogueoflife.editor.name.Status;
import org.catalogueoflife.editor.name.bulk.dto.BulkInsertRequest;
import org.catalogueoflife.editor.name.bulk.dto.BulkPreviewResponse;
import org.catalogueoflife.editor.name.bulk.dto.BulkPreviewResponse.PreviewNode;
import org.catalogueoflife.editor.parse.NameParserService;
import org.catalogueoflife.editor.project.Project;
import org.catalogueoflife.editor.project.ProjectMapper;
import org.catalogueoflife.editor.project.ProjectService;
import org.catalogueoflife.editor.project.Role;
import org.gbif.nameparser.api.NomCode;
import org.gbif.txtree.SimpleTreeNode;
import org.gbif.txtree.Tree;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BulkInsertService {

  static final int MAX_NAMES = 1000;

  private final NameUsageService usageService;
  private final NameUsageMapper usages;
  private final ProjectService projects;
  private final ProjectMapper projectMapper;
  private final NameParserService parser;

  public BulkInsertService(NameUsageService usageService, NameUsageMapper usages,
      ProjectService projects, ProjectMapper projectMapper, NameParserService parser) {
    this.usageService = usageService;
    this.usages = usages;
    this.projects = projects;
    this.projectMapper = projectMapper;
    this.parser = parser;
  }

  public BulkPreviewResponse preview(int userId, int projectId, BulkInsertRequest req) {
    requireEditor(userId, projectId);
    Project project = requireProject(projectId);
    resolveTarget(projectId, req.targetId());       // 400/404 if target invalid
    BulkMode mode = parseMode(req.mode());

    List<SimpleTreeNode> roots;
    try {
      roots = Tree.simple(new StringReader(req.text())).getRoot();
    } catch (IllegalArgumentException | IOException e) {
      return BulkPreviewResponse.invalid(e.getMessage());
    }
    if (roots.isEmpty()) {
      return BulkPreviewResponse.invalid("No names found in the input");
    }
    if (mode == BulkMode.SYNONYMS && !isFlat(roots)) {
      return BulkPreviewResponse.invalid(
          "Synonymy mode requires a flat list of names (no indentation)");
    }
    int total = countNodes(roots, mode);
    if (total > MAX_NAMES) {
      return BulkPreviewResponse.invalid("This list is too large for a direct insert (" + total
          + " > " + MAX_NAMES + "). Import it as a new dataset instead.");
    }

    Set<String> existing = existingNamesLower(userId, projectId, req.targetId(), mode);
    NomCode nomCode = project.getNomCode();
    int[] counts = new int[3]; // accepted, synonyms, duplicates
    List<PreviewNode> nodes = mode == BulkMode.SYNONYMS
        ? previewFlatSynonyms(roots, nomCode, existing, counts)
        : previewChildren(roots, nomCode, existing, counts, true);
    return new BulkPreviewResponse(true, null, counts[0] + counts[1],
        counts[0], counts[1], counts[2], nodes);
  }

  // --- shared helpers (reused by insert() in Task 3) ---

  BulkMode parseMode(String raw) {
    try {
      return BulkMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid mode: " + raw);
    }
  }

  void requireEditor(int userId, int projectId) {
    String role = projects.requireRole(userId, projectId);
    if (!role.equals(Role.OWNER.dbValue()) && !role.equals(Role.EDITOR.dbValue())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "owner or editor required");
    }
  }

  Project requireProject(int projectId) {
    Project p = projectMapper.findById(projectId);
    if (p == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "project not found");
    }
    return p;
  }

  // The target must be an accepted usage in the project (children attach to it; synonyms point at it).
  NameUsage resolveTarget(int projectId, int targetId) {
    NameUsage t = usages.findByIdInProject(projectId, targetId);
    if (t == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "target not in project");
    }
    if (t.getStatus() != Status.ACCEPTED) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "target must be an accepted usage");
    }
    return t;
  }

  static boolean isFlat(List<SimpleTreeNode> roots) {
    return roots.stream().allMatch(n -> n.children.isEmpty() && n.synonyms.isEmpty());
  }

  static int countNodes(List<SimpleTreeNode> roots, BulkMode mode) {
    if (mode == BulkMode.SYNONYMS) {
      return roots.size();
    }
    int n = 0;
    for (SimpleTreeNode r : roots) {
      n += 1 + r.synonyms.size() + countNodes(r.children, BulkMode.CHILDREN);
    }
    return n;
  }

  private Set<String> existingNamesLower(int userId, int projectId, int targetId, BulkMode mode) {
    List<String> names = mode == BulkMode.SYNONYMS
        ? usageService.listSynonyms(userId, projectId, targetId).stream()
            .map(r -> r.scientificName()).collect(Collectors.toList())
        : usages.findChildScientificNames(projectId, targetId);
    return names.stream().filter(s -> s != null)
        .map(s -> s.trim().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
  }

  // Runs the same parse the insert will, so the previewed rank/status match what gets stored. The
  // usage is discarded (never inserted).
  NameUsage toUsage(int projectId, int userId, SimpleTreeNode node, Status status,
      Integer parentId, NomCode nomCode) {
    NameUsage u = new NameUsage();
    u.setProjectId(projectId);
    u.setScientificName(node.name);
    u.setRank(node.rank);
    u.setStatus(status);
    u.setParentId(parentId);
    u.setExtinct(node.extinct ? Boolean.TRUE : null);
    u.setModifiedBy(userId);
    parser.parseInto(u, nomCode);
    if (u.getRank() == null || u.getRank().isBlank()) {
      u.setRank("unranked");
    }
    return u;
  }

  private String effectiveRank(SimpleTreeNode node, NomCode nomCode) {
    return toUsage(0, 0, node, Status.ACCEPTED, null, nomCode).getRank();
  }

  private boolean isDup(SimpleTreeNode node, Set<String> existing) {
    return existing.contains(node.name == null ? "" : node.name.trim().toLowerCase(Locale.ROOT));
  }

  private List<PreviewNode> previewChildren(List<SimpleTreeNode> nodes, NomCode nomCode,
      Set<String> existing, int[] counts, boolean topLevel) {
    List<PreviewNode> out = new ArrayList<>();
    for (SimpleTreeNode n : nodes) {
      counts[0]++;
      boolean dup = topLevel && isDup(n, existing);
      if (dup) counts[2]++;
      List<PreviewNode> syns = new ArrayList<>();
      for (SimpleTreeNode s : n.synonyms) {
        counts[1]++;
        syns.add(new PreviewNode(s.name, effectiveRank(s, nomCode), "SYNONYM", false, false,
            List.of(), List.of()));
      }
      List<PreviewNode> kids = previewChildren(n.children, nomCode, existing, counts, false);
      out.add(new PreviewNode(n.name, effectiveRank(n, nomCode), "ACCEPTED", n.extinct, dup,
          kids, syns));
    }
    return out;
  }

  private List<PreviewNode> previewFlatSynonyms(List<SimpleTreeNode> roots, NomCode nomCode,
      Set<String> existing, int[] counts) {
    List<PreviewNode> out = new ArrayList<>();
    for (SimpleTreeNode n : roots) {
      counts[1]++;
      boolean dup = isDup(n, existing);
      if (dup) counts[2]++;
      out.add(new PreviewNode(n.name, effectiveRank(n, nomCode), "SYNONYM", false, dup,
          List.of(), List.of()));
    }
    return out;
  }
}
```

- [ ] **Step 6: Create `BulkInsertController` (preview endpoint)**

`backend/src/main/java/org/catalogueoflife/editor/name/bulk/BulkInsertController.java`:

```java
package org.catalogueoflife.editor.name.bulk;

import jakarta.validation.Valid;
import org.catalogueoflife.editor.auth.CurrentUser;
import org.catalogueoflife.editor.name.bulk.dto.BulkInsertRequest;
import org.catalogueoflife.editor.name.bulk.dto.BulkPreviewResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{pid}/usages/bulk")
public class BulkInsertController {

  private final BulkInsertService service;
  private final CurrentUser currentUser;

  public BulkInsertController(BulkInsertService service, CurrentUser currentUser) {
    this.service = service;
    this.currentUser = currentUser;
  }

  @PostMapping("/preview")
  public BulkPreviewResponse preview(@PathVariable int pid, @Valid @RequestBody BulkInsertRequest req) {
    int uid = currentUser.require().getId();
    return service.preview(uid, pid, req);
  }
}
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw -q -Dtest=BulkPreviewApiIT test`
Expected: PASS (all preview tests).

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/org/catalogueoflife/editor/name/bulk backend/src/main/java/org/catalogueoflife/editor/name/NameUsageMapper.java backend/src/test/java/org/catalogueoflife/editor/name/bulk/BulkPreviewApiIT.java
git commit -m "feat(bulk): text-tree bulk-insert preview endpoint"
```

---

### Task 3: Bulk insert endpoint (transactional, reuses create + linkSynonym)

**Files:**
- Create: `backend/src/main/java/org/catalogueoflife/editor/name/bulk/dto/BulkInsertResult.java`
- Modify: `backend/src/main/java/org/catalogueoflife/editor/name/bulk/BulkInsertService.java` (add `insert`)
- Modify: `backend/src/main/java/org/catalogueoflife/editor/name/bulk/BulkInsertController.java` (add POST)
- Test: `backend/src/test/java/org/catalogueoflife/editor/name/bulk/BulkInsertApiIT.java`

**Interfaces:**
- Consumes (existing, exact signatures): `NameUsageService.create(int userId,int projectId, CreateNameUsageRequest) -> NameUsageResponse` (`NameUsageResponse.id() -> Integer`); `NameUsageService.linkSynonym(int userId,int projectId,int synonymId,int acceptedId)`; `CreateNameUsageRequest(scientificName, authorship, rank, status, parentId, namePhrase, nomStatus, publishedInReferenceId, publishedInYear, publishedInPage, publishedInPageLink, gender, extinct, environment, temporalRangeStart, temporalRangeEnd, remarks)`; `TreeMapper.lockProject(int)`.
- Produces: `BulkInsertService.insert(int userId,int projectId, BulkInsertRequest) -> BulkInsertResult`.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/org/catalogueoflife/editor/name/bulk/BulkInsertApiIT.java` (reuse the `seed`/`body` helpers from BulkPreviewApiIT — copy them in):

```java
package org.catalogueoflife.editor.name.bulk;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserService;
import org.catalogueoflife.editor.project.ProjectMemberMapper;
import org.catalogueoflife.editor.project.ProjectMember;
import org.catalogueoflife.editor.project.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@AutoConfigureMockMvc
@WithMockUser(username = "bulkIns")
class BulkInsertApiIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ProjectMemberMapper members;
  @Autowired ObjectMapper json;

  private void ensureUser(String u) {
    if (users.requireByUsernameOrNull(u) == null) users.createLocal(u, "pw", u);
  }

  private int[] seed(String owner) throws Exception {
    ensureUser(owner);
    String pj = mvc.perform(post("/api/projects").with(csrf()).with(user(owner))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"BulkI\",\"nomCode\":\"zoological\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    int pid = json.readTree(pj).get("id").asInt();
    String gj = mvc.perform(post("/api/projects/" + pid + "/usages").with(csrf()).with(user(owner))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scientificName\":\"Panthera\",\"rank\":\"genus\",\"status\":\"ACCEPTED\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return new int[] {pid, json.readTree(gj).get("id").asInt()};
  }

  private String body(int targetId, String mode, String text) throws Exception {
    return json.writeValueAsString(java.util.Map.of("targetId", targetId, "mode", mode, "text", text));
  }

  @Test
  void insertsChildrenNestedAndSynonym() throws Exception {
    int[] s = seed("bulkIns");
    String tree = "Panthera leo [species]\n  =Felis leo\nUncia [subgenus]\n  Uncia uncia [species]\n";
    mvc.perform(post("/api/projects/" + s[0] + "/usages/bulk").with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(body(s[1], "children", tree)))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.created").value(4))    // leo, Felis leo, Uncia, Uncia uncia
       .andExpect(jsonPath("$.synonymsLinked").value(1));

    // Panthera now has 2 accepted children (Panthera leo, Uncia)
    mvc.perform(get("/api/projects/" + s[0] + "/usages").param("q", "Panthera leo"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.total").value(1));
  }

  @Test
  void synonymyModeLinksToTarget() throws Exception {
    int[] s = seed("bulkIns");
    mvc.perform(post("/api/projects/" + s[0] + "/usages/bulk").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(s[1], "synonyms", "Panthera tigris\nFelis leo\n")))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.created").value(2))
       .andExpect(jsonPath("$.synonymsLinked").value(2));
    // the target genus lists 2 synonyms now
    mvc.perform(get("/api/projects/" + s[0] + "/usages/" + s[1] + "/synonyms"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.length()").value(2));
  }

  @Test
  void overCapIsRejectedAndInsertsNothing() throws Exception {
    int[] s = seed("bulkIns");
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 1001; i++) sb.append("Name").append(i).append(" species\n");
    mvc.perform(post("/api/projects/" + s[0] + "/usages/bulk").with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(body(s[1], "children", sb.toString())))
       .andExpect(status().isBadRequest());
    mvc.perform(get("/api/projects/" + s[0] + "/usages").param("q", "Name0"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.total").value(0));
  }

  @Test
  void nonEditorForbidden() throws Exception {
    int[] s = seed("bulkIns");
    ensureUser("bulkViewer");
    AppUser v = users.requireByUsernameOrNull("bulkViewer");
    members.upsert(new ProjectMember(s[0], v.getId(), Role.VIEWER.dbValue()));
    mvc.perform(post("/api/projects/" + s[0] + "/usages/bulk").with(csrf()).with(user("bulkViewer"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(s[1], "children", "Panthera leo\n")))
       .andExpect(status().isForbidden());
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw -q -Dtest=BulkInsertApiIT test`
Expected: FAIL — 404 (no POST `/usages/bulk`).

- [ ] **Step 3: Create the result DTO**

`backend/src/main/java/org/catalogueoflife/editor/name/bulk/dto/BulkInsertResult.java`:

```java
package org.catalogueoflife.editor.name.bulk.dto;

// Outcome of a bulk insert: `created` = usages created (accepted + synonyms), `synonymsLinked` =
// synonym->accepted links made, `targetId` echoes the anchor.
public record BulkInsertResult(int created, int synonymsLinked, int targetId) {}
```

- [ ] **Step 4: Add `insert` to `BulkInsertService`**

Add these imports to `BulkInsertService.java`:

```java
import org.catalogueoflife.editor.name.bulk.dto.BulkInsertResult;
import org.catalogueoflife.editor.name.dto.CreateNameUsageRequest;
import org.catalogueoflife.editor.name.dto.NameUsageResponse;
import org.catalogueoflife.editor.tree.TreeMapper;
import org.springframework.transaction.annotation.Transactional;
```

Add a `TreeMapper tree` field + constructor param (append to the existing constructor):

```java
  private final TreeMapper tree;
```
(add `TreeMapper tree` as the last constructor argument and `this.tree = tree;`)

Add the methods:

```java
  // All-or-nothing bulk insert. Reuses NameUsageService.create (parse, id-seq, insert, taxon-info,
  // audit, validation event) and linkSynonym, so this stays DRY with single-add. The whole run is
  // one transaction: create() is @Transactional REQUIRED, so each call joins THIS transaction and
  // any failure rolls the entire batch back. The project tree is advisory-locked once up front
  // (create() re-locks reentrantly for each parented child).
  @Transactional
  public BulkInsertResult insert(int userId, int projectId, BulkInsertRequest req) {
    requireEditor(userId, projectId);
    Project project = requireProject(projectId);
    NameUsage target = resolveTarget(projectId, req.targetId());
    BulkMode mode = parseMode(req.mode());
    tree.lockProject(projectId);

    List<SimpleTreeNode> roots;
    try {
      roots = Tree.simple(new StringReader(req.text())).getRoot();
    } catch (IllegalArgumentException | IOException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
    if (roots.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No names found in the input");
    }
    if (mode == BulkMode.SYNONYMS && !isFlat(roots)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Synonymy mode requires a flat list of names (no indentation)");
    }
    int total = countNodes(roots, mode);
    if (total > MAX_NAMES) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "This list is too large for a direct insert (" + total + " > " + MAX_NAMES
              + "). Import it as a new dataset instead.");
    }

    NomCode nomCode = project.getNomCode();
    int[] counts = new int[2]; // created, linked
    if (mode == BulkMode.SYNONYMS) {
      for (SimpleTreeNode n : roots) {
        int synId = createUsage(userId, projectId, n, "SYNONYM", null, nomCode);
        usageService.linkSynonym(userId, projectId, synId, target.getId());
        counts[0]++;
        counts[1]++;
      }
    } else {
      insertChildren(userId, projectId, roots, target.getId(), nomCode, counts);
    }
    return new BulkInsertResult(counts[0], counts[1], target.getId());
  }

  private void insertChildren(int userId, int projectId, List<SimpleTreeNode> nodes,
      Integer parentId, NomCode nomCode, int[] counts) {
    for (SimpleTreeNode n : nodes) {
      int id = createUsage(userId, projectId, n, "ACCEPTED", parentId, nomCode);
      counts[0]++;
      for (SimpleTreeNode s : n.synonyms) {
        int synId = createUsage(userId, projectId, s, "SYNONYM", null, nomCode);
        usageService.linkSynonym(userId, projectId, synId, id);
        counts[0]++;
        counts[1]++;
      }
      insertChildren(userId, projectId, n.children, id, nomCode, counts);
    }
  }

  // Builds a CreateNameUsageRequest and delegates to NameUsageService.create. Rank is pre-resolved
  // to a non-blank value (create requires @NotBlank rank): the [rank] suffix if present, else the
  // parser-inferred rank, else "unranked".
  private int createUsage(int userId, int projectId, SimpleTreeNode node, String status,
      Integer parentId, NomCode nomCode) {
    String rank = effectiveRank(node, nomCode);
    CreateNameUsageRequest r = new CreateNameUsageRequest(
        node.name, null, rank, status, parentId,
        null, null, null, null, null, null, null,
        node.extinct ? Boolean.TRUE : null,
        null, null, null, null);
    NameUsageResponse created = usageService.create(userId, projectId, r);
    return created.id();
  }
```

Note: `effectiveRank` is currently `private`; keep it usable here (it already lives in this class). Confirm the `CreateNameUsageRequest` constructor argument order matches its record component order (scientificName, authorship, rank, status, parentId, namePhrase, nomStatus, publishedInReferenceId, publishedInYear, publishedInPage, publishedInPageLink, gender, extinct, environment, temporalRangeStart, temporalRangeEnd, remarks) — 17 args; the call above passes them positionally.

- [ ] **Step 5: Add the POST endpoint to `BulkInsertController`**

Add import `import org.catalogueoflife.editor.name.bulk.dto.BulkInsertResult;` and the method:

```java
  @PostMapping
  public BulkInsertResult insert(@PathVariable int pid, @Valid @RequestBody BulkInsertRequest req) {
    int uid = currentUser.require().getId();
    return service.insert(uid, pid, req);
  }
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw -q -Dtest=BulkInsertApiIT,BulkPreviewApiIT test`
Expected: PASS (both classes).

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/org/catalogueoflife/editor/name/bulk backend/src/test/java/org/catalogueoflife/editor/name/bulk/BulkInsertApiIT.java
git commit -m "feat(bulk): transactional bulk-insert endpoint (children + synonymy)"
```

---

### Task 4: Frontend — Bulk add modal + API + menu wiring

**Files:**
- Create: `frontend/src/api/bulk.ts`
- Create: `frontend/src/names/BulkAddModal.tsx`
- Modify: `frontend/src/names/useNameActions.ts` (add `bulkTarget` state + `startBulk`/`closeBulk`)
- Modify: `frontend/src/names/NameActionMenu.tsx` (add "Bulk add…" item + render `BulkAddModal`)
- Test: `frontend/src/names/BulkAddModal.test.tsx`

**Interfaces:**
- Consumes: `api` from `../api/client`; `messageFor`; query keys `['treeRoots', pid]`, `['treeChildren', pid]`, `['usageSearch', pid]`, `['usageSynonyms', pid]`.
- Produces: `previewBulk(pid, body) -> Promise<BulkPreview>`, `insertBulk(pid, body) -> Promise<BulkInsertResult>`; `BulkAddModal` component.

- [ ] **Step 1: Write the failing test**

Create `frontend/src/names/BulkAddModal.test.tsx`:

```tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '../test/utils';
import userEvent from '@testing-library/user-event';
import BulkAddModal from './BulkAddModal';
import * as bulkApi from '../api/bulk';

const target = { id: 7, scientificName: 'Panthera' };

describe('BulkAddModal', () => {
  beforeEach(() => vi.restoreAllMocks());

  it('previews then inserts', async () => {
    vi.spyOn(bulkApi, 'previewBulk').mockResolvedValue({
      valid: true, error: null, total: 2, accepted: 2, synonyms: 0, duplicates: 0,
      nodes: [
        { name: 'Panthera leo', rank: 'species', status: 'ACCEPTED', extinct: false, duplicate: false, children: [], synonyms: [] },
        { name: 'Panthera onca', rank: 'species', status: 'ACCEPTED', extinct: false, duplicate: false, children: [], synonyms: [] },
      ],
    });
    const insert = vi.spyOn(bulkApi, 'insertBulk').mockResolvedValue({ created: 2, synonymsLinked: 0, targetId: 7 });

    render(<BulkAddModal pid={1} target={target} opened onClose={() => {}} onDone={() => {}} />);
    await userEvent.type(screen.getByLabelText(/names/i), 'Panthera leo\nPanthera onca');
    await userEvent.click(screen.getByRole('button', { name: /preview/i }));

    expect(await screen.findByText('Panthera leo')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /insert 2 names/i }));
    await waitFor(() => expect(insert).toHaveBeenCalled());
  });

  it('shows a parse error and disables insert', async () => {
    vi.spyOn(bulkApi, 'previewBulk').mockResolvedValue({
      valid: false, error: 'not properly indented on line 2', total: 0, accepted: 0, synonyms: 0, duplicates: 0, nodes: [],
    });
    render(<BulkAddModal pid={1} target={target} opened onClose={() => {}} onDone={() => {}} />);
    await userEvent.type(screen.getByLabelText(/names/i), 'Aaa\n    Bbb');
    await userEvent.click(screen.getByRole('button', { name: /preview/i }));
    expect(await screen.findByText(/not properly indented/i)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /insert/i })).not.toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/names/BulkAddModal.test.tsx`
Expected: FAIL — cannot resolve `./BulkAddModal` / `../api/bulk`.

- [ ] **Step 3: Create the API module**

`frontend/src/api/bulk.ts`:

```ts
import { api } from './client';

export interface BulkPreviewNode {
  name: string;
  rank: string;
  status: string;
  extinct: boolean;
  duplicate: boolean;
  children: BulkPreviewNode[];
  synonyms: BulkPreviewNode[];
}

export interface BulkPreview {
  valid: boolean;
  error: string | null;
  total: number;
  accepted: number;
  synonyms: number;
  duplicates: number;
  nodes: BulkPreviewNode[];
}

export interface BulkInsertResult {
  created: number;
  synonymsLinked: number;
  targetId: number;
}

export interface BulkBody {
  targetId: number;
  mode: 'children' | 'synonyms';
  text: string;
}

export function previewBulk(pid: number, body: BulkBody): Promise<BulkPreview> {
  return api<BulkPreview>(`/api/projects/${pid}/usages/bulk/preview`, { method: 'POST', json: body });
}

export function insertBulk(pid: number, body: BulkBody): Promise<BulkInsertResult> {
  return api<BulkInsertResult>(`/api/projects/${pid}/usages/bulk`, { method: 'POST', json: body });
}
```

- [ ] **Step 4: Create `BulkAddModal`**

`frontend/src/names/BulkAddModal.tsx`:

```tsx
import { Alert, Badge, Button, FileButton, Group, Modal, SegmentedControl, Stack, Text, Textarea } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useEffect, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { messageFor } from '../api/client';
import { insertBulk, previewBulk, type BulkPreview, type BulkPreviewNode } from '../api/bulk';

export interface BulkAddTarget {
  id: number;
  scientificName: string | null;
}

export interface BulkAddModalProps {
  pid: number;
  target: BulkAddTarget;
  opened: boolean;
  onClose: () => void;
  onDone: () => void;
}

function NodeView({ node, depth }: { node: BulkPreviewNode; depth: number }) {
  return (
    <div style={{ paddingLeft: depth * 16 }}>
      <Group gap={6}>
        <Text size="sm" fs="italic">{node.name}</Text>
        <Badge size="xs" variant="light" color={node.status === 'SYNONYM' ? 'gray' : 'blue'}>
          {node.rank}
        </Badge>
        {node.extinct && <Badge size="xs" variant="light" color="dark">†</Badge>}
        {node.duplicate && <Badge size="xs" variant="light" color="yellow">exists</Badge>}
      </Group>
      {node.synonyms.map((s, i) => (
        <div key={`s${i}`} style={{ paddingLeft: (depth + 1) * 16 }}>
          <Text size="sm" c="dimmed">= {s.name}</Text>
        </div>
      ))}
      {node.children.map((c, i) => <NodeView key={`c${i}`} node={c} depth={depth + 1} />)}
    </div>
  );
}

// Bulk-add a text-tree (or plain list) under `target`. Preview -> confirm: previewBulk parses and
// validates server-side; the parsed tree renders here; insertBulk commits everything in one
// transaction. Duplicates are shown but inserted anyway.
export default function BulkAddModal({ pid, target, opened, onClose, onDone }: BulkAddModalProps) {
  const queryClient = useQueryClient();
  const [mode, setMode] = useState<'children' | 'synonyms'>('children');
  const [text, setText] = useState('');
  const [preview, setPreview] = useState<BulkPreview | null>(null);

  useEffect(() => {
    if (opened) {
      setMode('children');
      setText('');
      setPreview(null);
    }
  }, [opened]);

  const previewMut = useMutation({
    mutationFn: () => previewBulk(pid, { targetId: target.id, mode, text }),
    onSuccess: (p) => setPreview(p),
    onError: (e) => notifications.show({ color: 'red', message: messageFor(e, 'Preview failed') }),
  });

  const insertMut = useMutation({
    mutationFn: () => insertBulk(pid, { targetId: target.id, mode, text }),
    onSuccess: async (res) => {
      await queryClient.invalidateQueries({ queryKey: ['treeRoots', pid] });
      await queryClient.invalidateQueries({ queryKey: ['treeChildren', pid] });
      await queryClient.invalidateQueries({ queryKey: ['usageSearch', pid] });
      await queryClient.invalidateQueries({ queryKey: ['usageSynonyms', pid] });
      notifications.show({ message: `Inserted ${res.created} names` });
      onDone();
      onClose();
    },
    onError: (e) => notifications.show({ color: 'red', message: messageFor(e, 'Insert failed') }),
  });

  const readFile = (file: File | null) => {
    if (!file) return;
    file.text().then((t) => { setText(t); setPreview(null); });
  };

  return (
    <Modal opened={opened} onClose={onClose} size="lg" title={
      <Text fw={600}>Bulk add under <Text span fs="italic" inherit>{target.scientificName ?? '—'}</Text></Text>
    }>
      <Stack gap="md">
        <SegmentedControl
          value={mode}
          onChange={(v) => { setMode(v as 'children' | 'synonyms'); setPreview(null); }}
          data={[{ label: 'As accepted children', value: 'children' },
                 { label: 'As synonyms of target', value: 'synonyms' }]}
        />
        <Textarea
          label="Names"
          description="One name per line. [rank] sets rank; 2-space indent nests children; = marks a synonym; † marks extinct."
          autosize minRows={6} maxRows={16}
          value={text}
          onChange={(e) => { setText(e.currentTarget.value); setPreview(null); }}
        />
        <Group>
          <FileButton onChange={readFile} accept=".txt,.tsv,.txtree,.tree">
            {(props) => <Button variant="default" size="xs" {...props}>Upload file…</Button>}
          </FileButton>
          <Button variant="light" size="xs" loading={previewMut.isPending}
            disabled={!text.trim()} onClick={() => previewMut.mutate()}>
            Preview
          </Button>
        </Group>

        {preview && !preview.valid && (
          <Alert color="red" title="Cannot parse">{preview.error}</Alert>
        )}
        {preview && preview.valid && (
          <Stack gap="xs">
            <Group gap="xs">
              <Badge variant="light" color="blue">accepted {preview.accepted}</Badge>
              <Badge variant="light" color="gray">synonyms {preview.synonyms}</Badge>
              {preview.duplicates > 0 && (
                <Badge variant="light" color="yellow">already exist {preview.duplicates}</Badge>
              )}
            </Group>
            <div style={{ maxHeight: 300, overflowY: 'auto' }}>
              {preview.nodes.map((n, i) => <NodeView key={i} node={n} depth={0} />)}
            </div>
            <Group justify="flex-end">
              <Button variant="default" onClick={onClose}>Cancel</Button>
              <Button loading={insertMut.isPending} onClick={() => insertMut.mutate()}>
                Insert {preview.total} names
              </Button>
            </Group>
          </Stack>
        )}
      </Stack>
    </Modal>
  );
}
```

- [ ] **Step 5: Wire into `useNameActions` + `NameActionMenu`**

In `frontend/src/names/useNameActions.ts`, add state + actions mirroring the existing `clbImportTarget` pattern:

```ts
  const [bulkTarget, setBulkTarget] = useState<{ id: number; scientificName: string | null } | null>(null);
```
and in the returned object:
```ts
    bulkTarget,
    startBulk: (usage: { id: number; scientificName: string | null }) =>
      setBulkTarget({ id: usage.id, scientificName: usage.scientificName }),
    closeBulk: () => setBulkTarget(null),
```

In `frontend/src/names/NameActionMenu.tsx`: add the import `import BulkAddModal from './BulkAddModal';`, add a menu item inside the `canHaveChildrenOrSynonyms` block (after "Add synonym"):

```tsx
              <Menu.Item
                leftSection={<IconPlus size={14} />}
                onClick={() => actions.startBulk(usage)}
              >
                Bulk add…
              </Menu.Item>
```

and render the modal near the other modals:

```tsx
      {actions.bulkTarget && (
        <BulkAddModal
          pid={pid}
          target={actions.bulkTarget}
          opened
          onClose={actions.closeBulk}
          onDone={() => onSelect(actions.bulkTarget!.id)}
        />
      )}
```

- [ ] **Step 6: Run the modal test + typecheck**

Run: `cd frontend && npx vitest run src/names/BulkAddModal.test.tsx && npx tsc -b`
Expected: PASS (2 tests) + clean typecheck.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/api/bulk.ts frontend/src/names/BulkAddModal.tsx frontend/src/names/BulkAddModal.test.tsx frontend/src/names/useNameActions.ts frontend/src/names/NameActionMenu.tsx
git commit -m "feat(bulk): Bulk add modal (preview + confirm) in the name action menu"
```

---

## Phase 2 — Path B: async staging import via a format-adapter seam

### Task 5: Text-tree → ColDP converter

**Files:**
- Create: `backend/src/main/java/org/catalogueoflife/editor/coldp/imprt/TxtTreeToColdp.java`
- Test: `backend/src/test/java/org/catalogueoflife/editor/coldp/imprt/TxtTreeToColdpTest.java`

**Interfaces:**
- Consumes (existing): `ColdpTsv.writeFile(Path dir, ColdpTerm fileTerm, Iterable<Map<ColdpTerm,String>> rows)` (package `org.catalogueoflife.editor.coldp.io`); `ColdpMetadata.write(Path, ColdpMetadataDto)`, `ColdpMetadata.ColdpMetadataDto(title, alias, description, license, geographicScope, taxonomicScope)`; `org.gbif.txtree.Tree.simple(Reader)`, `SimpleTreeNode`; `life.catalogue.coldp.ColdpTerm`.
- Produces: `TxtTreeToColdp.convert(Reader txtree, Path dir, String title) -> int` (returns row count; writes `dir/NameUsage.tsv` + `dir/metadata.yaml`).

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/org/catalogueoflife/editor/coldp/imprt/TxtTreeToColdpTest.java`:

```java
package org.catalogueoflife.editor.coldp.imprt;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TxtTreeToColdpTest {

  @Test
  void writesNameUsageAndMetadata(@TempDir Path dir) throws Exception {
    String tree = "Panthera [genus]\n  Panthera leo [species]\n    =Felis leo\n";
    int rows = new TxtTreeToColdp().convert(new StringReader(tree), dir, "My Tree");

    assertThat(rows).isEqualTo(3);
    assertThat(Files.exists(dir.resolve("metadata.yaml"))).isTrue();
    Path nu = dir.resolve("NameUsage.tsv");
    assertThat(Files.exists(nu)).isTrue();

    List<String> lines = Files.readAllLines(nu);
    String header = lines.get(0);
    assertThat(header).contains("ID").contains("parentID").contains("status").contains("scientificName");

    // genus row: accepted, no parent
    assertThat(lines).anyMatch(l -> l.contains("Panthera") && l.contains("accepted") && l.contains("genus"));
    // synonym row: status synonym, parentID = the leo row's ID
    assertThat(lines).anyMatch(l -> l.contains("Felis leo") && l.contains("synonym"));
    assertThat(Files.readString(dir.resolve("metadata.yaml"))).contains("My Tree");
  }

  @Test
  void plainListBecomesRoots(@TempDir Path dir) throws Exception {
    int rows = new TxtTreeToColdp().convert(new StringReader("Aus bus\nBus cus\n"), dir, null);
    assertThat(rows).isEqualTo(2);
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw -q -Dtest=TxtTreeToColdpTest test`
Expected: FAIL — `TxtTreeToColdp` does not exist.

- [ ] **Step 3: Implement the converter**

`backend/src/main/java/org/catalogueoflife/editor/coldp/imprt/TxtTreeToColdp.java`:

```java
package org.catalogueoflife.editor.coldp.imprt;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import life.catalogue.coldp.ColdpTerm;
import org.catalogueoflife.editor.coldp.io.ColdpMetadata;
import org.catalogueoflife.editor.coldp.io.ColdpMetadata.ColdpMetadataDto;
import org.catalogueoflife.editor.coldp.io.ColdpTsv;
import org.gbif.txtree.SimpleTreeNode;
import org.gbif.txtree.Tree;
import org.springframework.stereotype.Component;

// Converts a GBIF text-tree into a ColDP staging archive the existing import pipeline can read: a
// combined NameUsage.tsv (accepted taxa with parentID = classification parent; synonyms with
// parentID = their accepted taxon's ID, which the importer turns into a synonym_accepted link) plus
// a minimal metadata.yaml (the import requires it). Node ids are the text-tree's line ids -- unique
// per line and only used to wire parentID within the file; the importer allocates fresh project ids.
@Component
public class TxtTreeToColdp {

  public int convert(Reader txtree, Path dir, String title) throws IOException {
    List<SimpleTreeNode> roots = Tree.simple(txtree).getRoot();
    List<Map<ColdpTerm, String>> rows = new ArrayList<>();
    for (SimpleTreeNode root : roots) {
      emit(root, null, rows);
    }
    Files.createDirectories(dir);
    ColdpTsv.writeFile(dir, ColdpTerm.NameUsage, rows);
    ColdpMetadata.write(dir, new ColdpMetadataDto(
        title == null || title.isBlank() ? null : title, null, null, null, null, null));
    return rows.size();
  }

  private void emit(SimpleTreeNode node, String parentId, List<Map<ColdpTerm, String>> rows) {
    String id = Long.toString(node.id);
    Map<ColdpTerm, String> row = new EnumMap<>(ColdpTerm.class);
    row.put(ColdpTerm.ID, id);
    row.put(ColdpTerm.parentID, parentId);
    row.put(ColdpTerm.status, "accepted");
    row.put(ColdpTerm.scientificName, node.name);
    row.put(ColdpTerm.rank, node.rank);
    if (node.extinct) {
      row.put(ColdpTerm.extinct, "true");
    }
    rows.add(row);

    for (SimpleTreeNode s : node.synonyms) {
      Map<ColdpTerm, String> sr = new EnumMap<>(ColdpTerm.class);
      sr.put(ColdpTerm.ID, Long.toString(s.id));
      sr.put(ColdpTerm.parentID, id); // parentID of a synonym = its accepted taxon's ID
      sr.put(ColdpTerm.status, "synonym");
      sr.put(ColdpTerm.scientificName, s.name);
      sr.put(ColdpTerm.rank, s.rank);
      rows.add(sr);
    }
    for (SimpleTreeNode c : node.children) {
      emit(c, id, rows);
    }
  }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw -q -Dtest=TxtTreeToColdpTest test`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/org/catalogueoflife/editor/coldp/imprt/TxtTreeToColdp.java backend/src/test/java/org/catalogueoflife/editor/coldp/imprt/TxtTreeToColdpTest.java
git commit -m "feat(import): text-tree -> ColDP NameUsage.tsv converter"
```

---

### Task 6: Format-adapter seam on the import pipeline + text-tree end-to-end

**Files:**
- Create: `backend/src/main/java/org/catalogueoflife/editor/coldp/imprt/SourceFormat.java`
- Create: `backend/src/main/java/org/catalogueoflife/editor/coldp/imprt/SourceFormatAdapter.java`
- Create: `backend/src/main/java/org/catalogueoflife/editor/coldp/imprt/ColdpArchiveAdapter.java`
- Create: `backend/src/main/java/org/catalogueoflife/editor/coldp/imprt/TxtTreeAdapter.java`
- Modify: `backend/src/main/java/org/catalogueoflife/editor/coldp/imprt/ImportRunService.java` (`start` uses adapters + `title` param + force `preserveIds=false` for txtree)
- Modify: `backend/src/main/java/org/catalogueoflife/editor/coldp/imprt/ImportRunController.java` (add optional `title`)
- Test: `backend/src/test/java/org/catalogueoflife/editor/coldp/imprt/TxtTreeImportIT.java`

**Interfaces:**
- Consumes (existing): `ImportRunService.self.run(long,Path,int,boolean,String)`; `ColdpZip.extractToTemp(InputStream,Path,long)`; `ImportRun`/`ImportRunResponse.of`; `runs.insertRunning/fail/findById`; `TxtTreeToColdp.convert(Reader,Path,String)`.
- Produces: `SourceFormat.detect(String filename) -> SourceFormat`; `SourceFormatAdapter.materialize(MultipartFile,Path,String title,long maxBytes)`; `ImportRunService.start(int,MultipartFile,boolean,String,String title)` (new 5-arg signature).

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/org/catalogueoflife/editor/coldp/imprt/TxtTreeImportIT.java`:

```java
package org.catalogueoflife.editor.coldp.imprt;

import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.nio.charset.StandardCharsets;
import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@AutoConfigureMockMvc
@WithMockUser(username = "ttImp")
class TxtTreeImportIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ObjectMapper json;

  @Test
  void importsTextTreeAsStagingProject() throws Exception {
    if (users.requireByUsernameOrNull("ttImp") == null) users.createLocal("ttImp", "pw", "ttImp");
    String tree = "Panthera [genus]\n  Panthera leo [species]\n    =Felis leo\n";
    MockMultipartFile file = new MockMultipartFile("file", "cats.txtree",
        "text/plain", tree.getBytes(StandardCharsets.UTF_8));

    String started = mvc.perform(multipart("/api/projects/import").file(file)
            .param("title", "Cats").with(csrf()))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("RUNNING"))
        .andReturn().getResponse().getContentAsString();
    long runId = json.readTree(started).get("id").asLong();

    await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
        mvc.perform(get("/api/projects/import/" + runId))
           .andExpect(jsonPath("$.status").value("DONE"))
           .andExpect(jsonPath("$.nameUsageCount").value(3)));

    // the created staging project has the genus as a root and the synonym linked
    String done = mvc.perform(get("/api/projects/import/" + runId)).andReturn()
        .getResponse().getContentAsString();
    int projectId = json.readTree(done).get("projectId").asInt();
    mvc.perform(get("/api/projects/" + projectId + "/usages").param("q", "Panthera leo"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.total").value(1));
  }
}
```

Note: confirm the project uses Awaitility in existing ITs (grep `org.awaitility` in `backend/pom.xml`/existing import ITs). If not present, poll with a bounded loop matching how `ImportApiIT` waits for DONE — mirror that exact wait pattern instead of Awaitility.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw -q -Dtest=TxtTreeImportIT test`
Expected: FAIL — the `title` param is ignored and a `.txtree` upload fails as an invalid archive.

- [ ] **Step 3: Create the seam types**

`SourceFormat.java`:

```java
package org.catalogueoflife.editor.coldp.imprt;

import java.util.Locale;

// Which import input format an upload is, chosen by filename. New formats (e.g. Darwin Core Archive)
// add a constant + a detect() case + a SourceFormatAdapter, with no change to run()/loadTransactional.
public enum SourceFormat {
  COLDP,
  TXTREE;

  public static SourceFormat detect(String filename) {
    String f = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
    if (f.endsWith(".txtree") || f.endsWith(".tree") || f.endsWith(".txt") || f.endsWith(".tsv")) {
      return TXTREE;
    }
    return COLDP; // .zip and anything else
  }
}
```

`SourceFormatAdapter.java`:

```java
package org.catalogueoflife.editor.coldp.imprt;

import java.io.IOException;
import java.nio.file.Path;
import org.springframework.web.multipart.MultipartFile;

// Turns an uploaded file into a ColDP-readable directory (containing at least NameUsage.tsv +
// metadata.yaml) that ImportRunService.run() then loads into a staging project.
public interface SourceFormatAdapter {
  SourceFormat format();

  void materialize(MultipartFile file, Path dir, String title, long maxBytes) throws IOException;
}
```

`ColdpArchiveAdapter.java`:

```java
package org.catalogueoflife.editor.coldp.imprt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import org.catalogueoflife.editor.coldp.io.ColdpZip;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

// The native format: a ColDP .zip is extracted (zip-bomb-guarded) into the dir. `title` is ignored
// -- a ColDP archive carries its own metadata.yaml.
@Component
public class ColdpArchiveAdapter implements SourceFormatAdapter {

  @Override
  public SourceFormat format() {
    return SourceFormat.COLDP;
  }

  @Override
  public void materialize(MultipartFile file, Path dir, String title, long maxBytes) throws IOException {
    try (InputStream in = file.getInputStream()) {
      ColdpZip.extractToTemp(in, dir, maxBytes);
    }
  }
}
```

`TxtTreeAdapter.java`:

```java
package org.catalogueoflife.editor.coldp.imprt;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

// A GBIF text-tree upload converted into a ColDP staging archive (NameUsage.tsv + metadata.yaml).
@Component
public class TxtTreeAdapter implements SourceFormatAdapter {

  private final TxtTreeToColdp converter;

  public TxtTreeAdapter(TxtTreeToColdp converter) {
    this.converter = converter;
  }

  @Override
  public SourceFormat format() {
    return SourceFormat.TXTREE;
  }

  @Override
  public void materialize(MultipartFile file, Path dir, String title, long maxBytes) throws IOException {
    try (Reader r = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8)) {
      converter.convert(r, dir, title);
    }
  }
}
```

- [ ] **Step 4: Refactor `ImportRunService.start` to use the adapters**

In `ImportRunService.java`: inject the adapters. Add a field + assign in the constructor from an injected `List<SourceFormatAdapter>`:

```java
  private final java.util.Map<SourceFormat, SourceFormatAdapter> adapters;
```
In the constructor signature add parameter `List<SourceFormatAdapter> adapterList`, and in the body:
```java
    this.adapters = adapterList.stream()
        .collect(java.util.stream.Collectors.toMap(SourceFormatAdapter::format, a -> a));
```

Replace the body of `start(...)` with the adapter-dispatching version and the new `title` param:

```java
  public ImportRunResponse start(int userId, MultipartFile file, boolean preserveIds, String idScope,
      String title) {
    if (file == null || file.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "file is required");
    }
    SourceFormat format = SourceFormat.detect(file.getOriginalFilename());
    // Preserve-ids/id-scope only make sense for a ColDP archive with real source ids; a text-tree's
    // synthetic line ids must never be kept as identifiers.
    if (format == SourceFormat.TXTREE) {
      preserveIds = false;
      idScope = null;
    }
    if (preserveIds && (idScope == null || idScope.isBlank())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "idScope is required when preserveIds is set");
    }
    if (file.getSize() > maxBytes) {
      throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE,
          "file exceeds " + maxBytes + " bytes");
    }

    ImportRun run = new ImportRun();
    run.setUserId(userId);
    run.setSourceName(file.getOriginalFilename());
    run.setPreserveIds(preserveIds);
    run.setIdScope(idScope);
    runs.insertRunning(run);
    long runId = run.getId();

    Path dir = importDir.resolve(String.valueOf(runId));
    try {
      adapters.get(format).materialize(file, dir, title, maxBytes);
    } catch (IOException | IllegalArgumentException e) {
      runs.fail(runId, e.getMessage());
      deleteQuietly(dir);
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "invalid " + format.name().toLowerCase(java.util.Locale.ROOT) + ": " + e.getMessage());
    }

    try {
      self.run(runId, dir, userId, preserveIds, idScope);
    } catch (TaskRejectedException e) {
      runs.fail(runId, "import service busy — try again later");
      deleteQuietly(dir);
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
          "import service busy, try again later");
    }

    return ImportRunResponse.of(runs.findById(runId), json);
  }
```

Add any missing imports (`java.io.IOException`, `java.util.List`) if not already present.

- [ ] **Step 5: Update `ImportRunController` to pass the title**

In `ImportRunController.java`, add the optional param and thread it through:

```java
  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.ACCEPTED)
  public ImportRunResponse start(@RequestPart("file") MultipartFile file,
      @RequestParam(defaultValue = "false") boolean preserveIds,
      @RequestParam(required = false) String idScope,
      @RequestParam(required = false) String title) {
    int uid = currentUser.require().getId();
    return service.start(uid, file, preserveIds, idScope, title);
  }
```

- [ ] **Step 6: Run the new IT + the full import regression suite**

Run: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw -q -Dtest='TxtTreeImportIT,ImportApiIT,ImportNameUsageIT,ImportSplitFormIT,ImportChildEntitiesIT,ImportRefAuthorIT,ImportExportRoundTripIT' test`
Expected: PASS — the new text-tree import works AND every existing ColDP import IT still passes (regression gate for the `start()` refactor).

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/org/catalogueoflife/editor/coldp/imprt/SourceFormat.java backend/src/main/java/org/catalogueoflife/editor/coldp/imprt/SourceFormatAdapter.java backend/src/main/java/org/catalogueoflife/editor/coldp/imprt/ColdpArchiveAdapter.java backend/src/main/java/org/catalogueoflife/editor/coldp/imprt/TxtTreeAdapter.java backend/src/main/java/org/catalogueoflife/editor/coldp/imprt/ImportRunService.java backend/src/main/java/org/catalogueoflife/editor/coldp/imprt/ImportRunController.java backend/src/test/java/org/catalogueoflife/editor/coldp/imprt/TxtTreeImportIT.java
git commit -m "feat(import): source-format-adapter seam + text-tree staging import"
```

---

### Task 7: Frontend — accept text-tree in the import modal

**Files:**
- Modify: `frontend/src/api/import.ts` (`startImport` gains optional `title`)
- Modify: `frontend/src/projects/ImportProjectModal.tsx` (accept `.txtree`/`.txt`/`.tsv`, optional title, hide preserveIds for text-tree)
- Modify: `frontend/src/projects/ImportProjectModal.test.tsx` (add a text-tree upload case)

**Interfaces:**
- Consumes: `startImport(file, preserveIds, idScope?, title?)`.
- Produces: none downstream.

- [ ] **Step 1: Write the failing test**

Add to `frontend/src/projects/ImportProjectModal.test.tsx` a case that selects a `.txtree` file and asserts `startImport` is called with a title (mirror the existing `.zip` test's structure; spy on `startImport`):

```tsx
  it('imports a text-tree file with a title', async () => {
    const start = vi.spyOn(importApi, 'startImport')
      .mockResolvedValue({ id: 5, projectId: null, status: 'RUNNING' } as never);
    render(<ImportProjectModal opened onClose={() => {}} />);
    const file = new File(['Aus bus\nBus cus\n'], 'cats.txtree', { type: 'text/plain' });
    await userEvent.upload(screen.getByLabelText(/file/i), file);
    await userEvent.type(screen.getByLabelText(/title/i), 'Cats');
    await userEvent.click(screen.getByRole('button', { name: /import/i }));
    await waitFor(() => expect(start).toHaveBeenCalled());
    // 4th arg is the title
    expect(start.mock.calls[0][3]).toBe('Cats');
  });
```

Adjust the selectors (`/file/i`, `/title/i`, `/import/i`) to match the real modal's labels; read the existing test to reuse its imports/harness.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/projects/ImportProjectModal.test.tsx`
Expected: FAIL — no title field / `startImport` called without a title.

- [ ] **Step 3: Add `title` to `startImport`**

In `frontend/src/api/import.ts`, extend `startImport`:

```ts
export function startImport(
  file: File,
  preserveIds: boolean,
  idScope?: string,
  title?: string,
): Promise<ImportRun> {
  const formData = new FormData();
  formData.append('file', file);
  formData.append('preserveIds', String(preserveIds));
  if (idScope) formData.append('idScope', idScope);
  if (title && title.trim()) formData.append('title', title.trim());
  return api<ImportRun>('/api/projects/import', { method: 'POST', formData });
}
```

- [ ] **Step 4: Update `ImportProjectModal`**

In `frontend/src/projects/ImportProjectModal.tsx`:
- Change the `FileInput` `accept` to `".zip,.txtree,.tree,.txt,.tsv"` and its placeholder to `"Select a .zip or text-tree file"`.
- Derive `const isTxtTree = !!file && /\.(txtree|tree|txt|tsv)$/i.test(file.name);`.
- Add a `TextInput` `label="Title"` (state `const [title, setTitle] = useState('')`), shown always (used by text-tree; harmless for ColDP where metadata.yaml wins). Optionally add help text: "Used as the project title for text-tree imports."
- When `isTxtTree`, hide/disable the preserveIds switch + idScope input (they don't apply).
- Change the mutation to `mutationFn: () => startImport(file as File, isTxtTree ? false : preserveIds, isTxtTree ? undefined : (preserveIds ? idScope.trim() : undefined), title)`.

- [ ] **Step 5: Run the modal test + typecheck + full frontend suite**

Run: `cd frontend && npx vitest run src/projects/ImportProjectModal.test.tsx && npx tsc -b && npx vitest run`
Expected: PASS (new + existing modal tests) + clean typecheck + whole suite green.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/api/import.ts frontend/src/projects/ImportProjectModal.tsx frontend/src/projects/ImportProjectModal.test.tsx
git commit -m "feat(import): accept text-tree uploads (with title) in the import modal"
```

---

## Final verification (after all tasks)

- [ ] Backend full suite: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw -q test` → all green.
- [ ] Frontend gates: `cd frontend && npx tsc -b && npx vitest run` → clean typecheck + all tests.
- [ ] Manual smoke (optional, via `docker-compose.full.yml`): open a taxon → "Bulk add…" → paste a small tree → Preview → Insert; and Project import → upload a `.txtree` with a title → poll to DONE → merge into a project.

## Notes carried from the spec (not re-litigated here)

- Duplicates are flagged in the preview but always inserted (product decision).
- Provisional/homotypic/basionym/misapplied text-tree markers are parsed but not distinctly modeled in v1 (`=` nodes → `SYNONYM`; `†` → extinct; others ignored).
- Path B lands as a standalone staging project (roots = project roots); reconciliation into a real project is via the existing supervised merge, not a taxon anchor.
- Darwin Core Archive is the next feature: add a `SourceFormat.DWCA` constant, a `detect()` case, and a `DwcaAdapter` — no change to `run()`/`loadTransactional`/merge.
- **All-or-nothing is a structural guarantee**, not a dedicated test: `insert` is a single `@Transactional`, and each reused `create`/`linkSynonym` joins it (REQUIRED propagation), so any failure rolls the whole batch back. The spec's "mid-insert rollback" is covered by this design plus the over-cap "inserts nothing" test; a deterministic mid-batch fault is not easily forced (every text-tree node yields a valid `create`), so no brittle fault-injection test is added.
