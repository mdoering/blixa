# Merge Names / References (user-selected) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let an editor multi-select 2+ names (or 2+ references) in the search tables and merge them into a survivor — every FK pointing at a merged record repoints to the survivor, then the merged record is deleted — behind a modal that shows each record's id + associated-info counts and lets the editor pick the survivor.

**Architecture:** A synchronous, transactional record-merge (no async job — it's a bounded operation). New `MergeRecordsMapper` holds the FK-repoint SQL (mirroring `NameUsageMapper.reparentChildren`/`removeReferenceIdFromAll`); `MergeRecordsService` orchestrates preview (per-id association counts) + merge (repoint-then-delete under the project advisory lock). The frontend adds multi-select to `NameSearchPage` (mantine-react-table row selection) and `ReferencesPage` (a plain Mantine table) + a shared `MergeRecordsModal`.

**Tech Stack:** Java 25 (Spring Boot), Postgres 17 (Flyway, Testcontainers ITs), MyBatis, React + TypeScript + Mantine 7 + TanStack Query + mantine-react-table + Vitest.

## Global Constraints

- **Repoint-before-delete is mandatory** — `name_usage.parent_id`/`basionym_id` and `published_in_reference_id` are `ON DELETE SET NULL`; `synonym_accepted`/`taxon_info`/child `usage_id` are `ON DELETE CASCADE`. Deleting the merged row first would null or cascade-destroy the links. Always repoint every FK, THEN delete.
- All tables use compound `(project_id, id)` PKs / `(project_id, <col>)` FKs — every WHERE is `project_id = #{pid} AND …`. Merges are strictly intra-project.
- **Name usage FK-repoint set (survivor S, merged D):** `name_usage.parent_id`, `name_usage.basionym_id`; `synonym_accepted.synonym_id` AND `.accepted_id` (dedup PK collisions + drop `synonym_id = accepted_id` self-links); `taxon_info.usage_id` (PK `(project_id,usage_id)` → drop D's row if S already has one, else repoint); `name_relation.usage_id` AND `.related_usage_id` (the latter has NO FK) + drop `usage_id = related_usage_id` self-relations; `type_material, vernacular, distribution, media, estimate, property` → `usage_id`.
- **Reference FK-repoint set:** `name_usage.published_in_reference_id`; `name_usage.reference_id[]` (array_replace + dedup); `reference_id` on `name_relation, type_material, vernacular, distribution, estimate, property` (media/synonym_accepted/author have none).
- Merge/preview endpoints are **owner/editor** for the merge, **any member** for the preview.
- **Survivor-must-be-accepted** guard (names): if any merged usage has accepted children or the survivor already has children, the survivor must be `Status.ACCEPTED` (accepted children can't hang under a synonym) → 400.
- The modal shows records **sorted by id ascending (oldest first)**, each with its `id` + `alternativeId`, and defaults the survivor to the **most-connected** record (max total counts), tie-broken by lowest id.
- Audit each merge (`AuditService.record`, `Operation.DELETE` for each merged row) and publish `ValidationEvent.forUsage` for the survivor; lock the project first (`TreeMapper.lockProject`).
- Build/test: backend `cd backend && JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw -q -Dtest=<Class> test`; frontend `cd frontend && npx tsc -b && npx vitest run`. Commit to `main`; never stage `todo.md`, `blixa.svg`, `application-dev.yml`.

---

## Phase 1 — Name merge

### Task 1: Name merge preview (association counts)

**Files:**
- Create: `backend/src/main/java/org/catalogueoflife/editor/mergerecords/MergeRecordsMapper.java`
- Create: `backend/src/main/java/org/catalogueoflife/editor/mergerecords/MergeRecordsService.java`
- Create: `backend/src/main/java/org/catalogueoflife/editor/mergerecords/MergeRecordsController.java`
- Create: `backend/src/main/java/org/catalogueoflife/editor/mergerecords/dto/MergeRequest.java`
- Create: `backend/src/main/java/org/catalogueoflife/editor/mergerecords/dto/UsageMergeCandidate.java`
- Test: `backend/src/test/java/org/catalogueoflife/editor/mergerecords/UsageMergePreviewIT.java`

**Interfaces:**
- Consumes: `ProjectService.requireRole(int,int)->String`; `NameUsageMapper.findByIdInProject(int,int)->NameUsage`.
- Produces: `MergeRecordsService.previewUsages(int userId,int projectId,List<Integer> ids)->List<UsageMergeCandidate>`; `POST /api/projects/{pid}/usages/merge/preview`.

- [ ] **Step 1: Write the failing IT**

`backend/src/test/java/org/catalogueoflife/editor/mergerecords/UsageMergePreviewIT.java`:

```java
package org.catalogueoflife.editor.mergerecords;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
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
@WithMockUser(username = "mp")
class UsageMergePreviewIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ObjectMapper json;

  private void ensureUser(String u) { if (users.requireByUsernameOrNull(u) == null) users.createLocal(u, "pw", u); }

  private int createUsage(int pid, String name, String rank, String status, Integer parentId) throws Exception {
    String c = "{\"scientificName\":\"" + name + "\",\"rank\":\"" + rank + "\",\"status\":\"" + status + "\""
        + (parentId == null ? "" : ",\"parentId\":" + parentId) + "}";
    String b = mvc.perform(post("/api/projects/" + pid + "/usages").with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(c))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return json.readTree(b).get("id").asInt();
  }

  @Test
  void previewReturnsCountsSortedByIdAsc() throws Exception {
    ensureUser("mp");
    String pj = mvc.perform(post("/api/projects").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"MP\",\"nomCode\":\"zoological\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    int pid = json.readTree(pj).get("id").asInt();
    int a = createUsage(pid, "Aus", "genus", "ACCEPTED", null);
    int aSpecies = createUsage(pid, "Aus bus", "species", "ACCEPTED", a);  // child of a
    int b = createUsage(pid, "Aus", "genus", "ACCEPTED", null);            // duplicate genus, no children

    mvc.perform(post("/api/projects/" + pid + "/usages/merge/preview").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(java.util.Map.of("ids", java.util.List.of(b, a)))))
       .andExpect(status().isOk())
       // sorted by id ascending: a (lower id) first
       .andExpect(jsonPath("$[0].id").value(a))
       .andExpect(jsonPath("$[0].counts.children").value(1))  // a has 1 accepted child
       .andExpect(jsonPath("$[1].id").value(b))
       .andExpect(jsonPath("$[1].counts.children").value(0));
  }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw -q -Dtest=UsageMergePreviewIT test`
Expected: FAIL — endpoint/classes missing.

- [ ] **Step 3: Create the DTOs**

`dto/MergeRequest.java`:
```java
package org.catalogueoflife.editor.mergerecords.dto;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
// For preview: only `ids` is used. For merge: `survivorId` + `ids` (the full selected set incl. survivor).
public record MergeRequest(List<Integer> ids, Integer survivorId) {}
```

`dto/UsageMergeCandidate.java`:
```java
package org.catalogueoflife.editor.mergerecords.dto;
import java.util.List;
import java.util.Map;
// One selected usage in a merge preview: display fields + its association counts (children, synonyms,
// acceptedOf, basionymOf, nameRelations, vernacular, distribution, media, typeMaterial, property, estimate).
public record UsageMergeCandidate(int id, List<String> alternativeId, String scientificName,
    String authorship, String rank, String status, Map<String, Integer> counts) {}
```

- [ ] **Step 4: Create `MergeRecordsMapper`** (count queries; repoint methods added in Task 2)

`MergeRecordsMapper.java`:
```java
package org.catalogueoflife.editor.mergerecords;

import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MergeRecordsMapper {

  // All association counts for one usage in a single round-trip (subqueries). Column labels are the
  // camelCase count keys; MyBatis maps to a Map<String,Object> (values are Long from count()).
  @Select("""
      SELECT
        (SELECT count(*) FROM name_usage       WHERE project_id = #{pid} AND parent_id = #{id})                       AS children,
        (SELECT count(*) FROM synonym_accepted WHERE project_id = #{pid} AND accepted_id = #{id})                     AS synonyms,
        (SELECT count(*) FROM synonym_accepted WHERE project_id = #{pid} AND synonym_id = #{id})                      AS "acceptedOf",
        (SELECT count(*) FROM name_usage       WHERE project_id = #{pid} AND basionym_id = #{id})                     AS "basionymOf",
        (SELECT count(*) FROM name_relation    WHERE project_id = #{pid} AND (usage_id = #{id} OR related_usage_id = #{id})) AS "nameRelations",
        (SELECT count(*) FROM vernacular       WHERE project_id = #{pid} AND usage_id = #{id})                        AS vernacular,
        (SELECT count(*) FROM distribution     WHERE project_id = #{pid} AND usage_id = #{id})                        AS distribution,
        (SELECT count(*) FROM media            WHERE project_id = #{pid} AND usage_id = #{id})                        AS media,
        (SELECT count(*) FROM type_material    WHERE project_id = #{pid} AND usage_id = #{id})                        AS "typeMaterial",
        (SELECT count(*) FROM property         WHERE project_id = #{pid} AND usage_id = #{id})                        AS property,
        (SELECT count(*) FROM estimate         WHERE project_id = #{pid} AND usage_id = #{id})                        AS estimate
      """)
  Map<String, Object> usageCounts(@Param("pid") int pid, @Param("id") int id);
}
```

- [ ] **Step 5: Create `MergeRecordsService`** (preview only for now)

`MergeRecordsService.java`:
```java
package org.catalogueoflife.editor.mergerecords;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.catalogueoflife.editor.mergerecords.dto.UsageMergeCandidate;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.name.NameUsageMapper;
import org.catalogueoflife.editor.name.Status;
import org.catalogueoflife.editor.project.ProjectService;
import org.catalogueoflife.editor.project.Role;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MergeRecordsService {

  private final MergeRecordsMapper merge;
  private final NameUsageMapper usages;
  private final ProjectService projects;

  public MergeRecordsService(MergeRecordsMapper merge, NameUsageMapper usages, ProjectService projects) {
    this.merge = merge;
    this.usages = usages;
    this.projects = projects;
  }

  public List<UsageMergeCandidate> previewUsages(int userId, int projectId, List<Integer> ids) {
    projects.requireRole(userId, projectId); // any member may preview
    List<Integer> distinct = requireAtLeastTwo(ids);
    List<UsageMergeCandidate> out = new ArrayList<>();
    for (int id : distinct.stream().sorted().toList()) {  // ascending id (oldest first)
      NameUsage u = usages.findByIdInProject(projectId, id);
      if (u == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "usage not in project: " + id);
      Map<String, Object> raw = merge.usageCounts(projectId, id);
      Map<String, Integer> counts = new LinkedHashMap<>();
      raw.forEach((k, v) -> counts.put(k, ((Number) v).intValue()));
      out.add(new UsageMergeCandidate(u.getId(), u.getAlternativeId(), u.getScientificName(),
          u.getAuthorship(), u.getRank(), u.getStatus() == null ? null : u.getStatus().name(), counts));
    }
    return out;
  }

  static List<Integer> requireAtLeastTwo(List<Integer> ids) {
    List<Integer> distinct = ids == null ? List.of() : ids.stream().distinct().toList();
    if (distinct.size() < 2) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "select at least two records to merge");
    }
    return distinct;
  }

  void requireEditor(int userId, int projectId) {
    String role = projects.requireRole(userId, projectId);
    if (!role.equals(Role.OWNER.dbValue()) && !role.equals(Role.EDITOR.dbValue())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "owner or editor required");
    }
  }
}
```

- [ ] **Step 6: Create `MergeRecordsController`** (preview endpoint)

`MergeRecordsController.java`:
```java
package org.catalogueoflife.editor.mergerecords;

import java.util.List;
import org.catalogueoflife.editor.auth.CurrentUser;
import org.catalogueoflife.editor.mergerecords.dto.MergeRequest;
import org.catalogueoflife.editor.mergerecords.dto.UsageMergeCandidate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{pid}")
public class MergeRecordsController {

  private final MergeRecordsService service;
  private final CurrentUser currentUser;

  public MergeRecordsController(MergeRecordsService service, CurrentUser currentUser) {
    this.service = service;
    this.currentUser = currentUser;
  }

  @PostMapping("/usages/merge/preview")
  public List<UsageMergeCandidate> previewUsages(@PathVariable int pid, @RequestBody MergeRequest req) {
    int uid = currentUser.require().getId();
    return service.previewUsages(uid, pid, req.ids());
  }
}
```

- [ ] **Step 7: Run the IT to verify it passes**

Run: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw -q -Dtest=UsageMergePreviewIT test`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/org/catalogueoflife/editor/mergerecords backend/src/test/java/org/catalogueoflife/editor/mergerecords/UsageMergePreviewIT.java
git commit -m "feat(merge): name merge preview (association counts per selected usage)"
```

---

### Task 2: Name merge (repoint + delete)

**Files:**
- Modify: `backend/src/main/java/org/catalogueoflife/editor/mergerecords/MergeRecordsMapper.java` (repoint methods)
- Modify: `backend/src/main/java/org/catalogueoflife/editor/mergerecords/MergeRecordsService.java` (`mergeUsages`)
- Modify: `backend/src/main/java/org/catalogueoflife/editor/mergerecords/MergeRecordsController.java` (merge endpoint)
- Create: `backend/src/main/java/org/catalogueoflife/editor/mergerecords/dto/MergeResult.java`
- Test: `backend/src/test/java/org/catalogueoflife/editor/mergerecords/UsageMergeApplyIT.java`

**Interfaces:**
- Consumes: `TreeMapper.lockProject`; `NameUsageMapper.findByIdInProject`, `NameUsageMapper.delete(pid,id)`, `NameUsageMapper.reparentChildren(pid,oldParent,newParent,modifiedBy)`; `AuditService.record`; `ValidationEvent.forUsage`; `ApplicationEventPublisher`; `Status.ACCEPTED`.
- Produces: `MergeRecordsService.mergeUsages(int userId,int projectId,int survivorId,List<Integer> ids)->MergeResult`; `POST /api/projects/{pid}/usages/merge`.

- [ ] **Step 1: Write the failing IT**

`UsageMergeApplyIT.java` — seed a survivor `S` (accepted genus) and a duplicate `D` (accepted genus) where `D` has: an accepted child, a synonym linked to it, a name-relation, and a vernacular; then `POST …/usages/merge {survivorId:S, ids:[S,D]}`; assert 200, then assert: `D` is gone (`GET …/usages/D` → 404), `D`'s child now has `parentId == S` (search or GET), `S` now lists the synonym (`GET …/usages/S/synonyms` length 1), and the vernacular now hangs under `S` (`GET …/usages/S/vernaculars` length 1). Also a validation test: survivor is a SYNONYM but `D` has accepted children → 400; and non-editor → 403; and `<2` ids → 400. Mirror the seeding helpers from `UsageMergePreviewIT` + existing child-entity ITs (e.g. `ChildEntityTab`/vernacular creation via `POST …/usages/{id}/vernaculars`). Use `GET …/usages/{id}/synonyms` and the vernacular list endpoint to verify repoints.

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw -q -Dtest=UsageMergeApplyIT test`
Expected: FAIL — no merge endpoint.

- [ ] **Step 3: Add the repoint methods to `MergeRecordsMapper`**

Add (each `WHERE project_id = #{pid} AND … = #{merged}`; `taxon_info` + `synonym_accepted` dedup pre-deletes):

```java
  // --- name-usage FK repoints (merged -> survivor). Order-independent; run all BEFORE deleting merged. ---
  @Update("UPDATE name_usage SET basionym_id = #{survivor} WHERE project_id = #{pid} AND basionym_id = #{merged}")
  int repointBasionym(@Param("pid") int pid, @Param("merged") int merged, @Param("survivor") int survivor);

  // synonym_accepted: pre-delete rows that would collide with an existing (survivor,x) pair, then repoint.
  @Delete("""
      DELETE FROM synonym_accepted d WHERE d.project_id = #{pid} AND d.synonym_id = #{merged}
        AND EXISTS (SELECT 1 FROM synonym_accepted x WHERE x.project_id = #{pid}
                    AND x.synonym_id = #{survivor} AND x.accepted_id = d.accepted_id)""")
  int deleteSynonymCollisions(@Param("pid") int pid, @Param("merged") int merged, @Param("survivor") int survivor);
  @Update("UPDATE synonym_accepted SET synonym_id = #{survivor} WHERE project_id = #{pid} AND synonym_id = #{merged}")
  int repointSynonymId(@Param("pid") int pid, @Param("merged") int merged, @Param("survivor") int survivor);

  @Delete("""
      DELETE FROM synonym_accepted d WHERE d.project_id = #{pid} AND d.accepted_id = #{merged}
        AND EXISTS (SELECT 1 FROM synonym_accepted x WHERE x.project_id = #{pid}
                    AND x.accepted_id = #{survivor} AND x.synonym_id = d.synonym_id)""")
  int deleteAcceptedCollisions(@Param("pid") int pid, @Param("merged") int merged, @Param("survivor") int survivor);
  @Update("UPDATE synonym_accepted SET accepted_id = #{survivor} WHERE project_id = #{pid} AND accepted_id = #{merged}")
  int repointAcceptedId(@Param("pid") int pid, @Param("merged") int merged, @Param("survivor") int survivor);

  @Delete("DELETE FROM synonym_accepted WHERE project_id = #{pid} AND synonym_id = accepted_id")
  int dropSynonymSelfLinks(@Param("pid") int pid);

  // taxon_info is 1-row-per-usage: drop merged's row if survivor already has one, else repoint.
  @Delete("""
      DELETE FROM taxon_info WHERE project_id = #{pid} AND usage_id = #{merged}
        AND EXISTS (SELECT 1 FROM taxon_info x WHERE x.project_id = #{pid} AND x.usage_id = #{survivor})""")
  int dropTaxonInfoIfSurvivorHas(@Param("pid") int pid, @Param("merged") int merged, @Param("survivor") int survivor);
  @Update("UPDATE taxon_info SET usage_id = #{survivor} WHERE project_id = #{pid} AND usage_id = #{merged}")
  int repointTaxonInfo(@Param("pid") int pid, @Param("merged") int merged, @Param("survivor") int survivor);

  // name_relation has TWO usage pointers; related_usage_id has no FK. Repoint both, then drop self-relations.
  @Update("UPDATE name_relation SET usage_id = #{survivor} WHERE project_id = #{pid} AND usage_id = #{merged}")
  int repointRelationUsage(@Param("pid") int pid, @Param("merged") int merged, @Param("survivor") int survivor);
  @Update("UPDATE name_relation SET related_usage_id = #{survivor} WHERE project_id = #{pid} AND related_usage_id = #{merged}")
  int repointRelationRelated(@Param("pid") int pid, @Param("merged") int merged, @Param("survivor") int survivor);
  @Delete("DELETE FROM name_relation WHERE project_id = #{pid} AND usage_id = related_usage_id")
  int dropSelfRelations(@Param("pid") int pid);

  // simple child tables (PK (project_id,id) -> no collision on repoint)
  @Update("UPDATE vernacular    SET usage_id = #{survivor} WHERE project_id = #{pid} AND usage_id = #{merged}") int repointVernacular(@Param("pid") int pid, @Param("merged") int merged, @Param("survivor") int survivor);
  @Update("UPDATE distribution  SET usage_id = #{survivor} WHERE project_id = #{pid} AND usage_id = #{merged}") int repointDistribution(@Param("pid") int pid, @Param("merged") int merged, @Param("survivor") int survivor);
  @Update("UPDATE media         SET usage_id = #{survivor} WHERE project_id = #{pid} AND usage_id = #{merged}") int repointMedia(@Param("pid") int pid, @Param("merged") int merged, @Param("survivor") int survivor);
  @Update("UPDATE type_material SET usage_id = #{survivor} WHERE project_id = #{pid} AND usage_id = #{merged}") int repointTypeMaterial(@Param("pid") int pid, @Param("merged") int merged, @Param("survivor") int survivor);
  @Update("UPDATE property      SET usage_id = #{survivor} WHERE project_id = #{pid} AND usage_id = #{merged}") int repointProperty(@Param("pid") int pid, @Param("merged") int merged, @Param("survivor") int survivor);
  @Update("UPDATE estimate      SET usage_id = #{survivor} WHERE project_id = #{pid} AND usage_id = #{merged}") int repointEstimate(@Param("pid") int pid, @Param("merged") int merged, @Param("survivor") int survivor);
```

Add the imports `import org.apache.ibatis.annotations.Delete; import org.apache.ibatis.annotations.Update;`.

- [ ] **Step 4: Create `MergeResult`**

`dto/MergeResult.java`:
```java
package org.catalogueoflife.editor.mergerecords.dto;
public record MergeResult(int survivorId, int mergedCount) {}
```

- [ ] **Step 5: Add `mergeUsages` to `MergeRecordsService`**

Add constructor deps `TreeMapper tree`, `AuditService audit`, `ApplicationEventPublisher events` (+ fields), and:

```java
  @org.springframework.transaction.annotation.Transactional
  public org.catalogueoflife.editor.mergerecords.dto.MergeResult mergeUsages(
      int userId, int projectId, Integer survivorId, java.util.List<Integer> ids) {
    requireEditor(userId, projectId);
    java.util.List<Integer> all = requireAtLeastTwo(ids);
    if (survivorId == null || !all.contains(survivorId)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "survivorId must be one of the selected records");
    }
    tree.lockProject(projectId);
    NameUsage survivor = usages.findByIdInProject(projectId, survivorId);
    if (survivor == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "survivor not in project");
    java.util.List<Integer> mergedIds = all.stream().filter(id -> id != survivorId.intValue()).toList();

    // survivor must be accepted if it will receive children (its own or a merged usage's)
    boolean survivorReceivesChildren = merge.usageCounts(projectId, survivorId).get("children") != null
        && ((Number) merge.usageCounts(projectId, survivorId).get("children")).intValue() > 0;
    for (int d : mergedIds) {
      NameUsage du = usages.findByIdInProject(projectId, d);
      if (du == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "usage not in project: " + d);
      if (((Number) merge.usageCounts(projectId, d).get("children")).intValue() > 0) survivorReceivesChildren = true;
    }
    if (survivorReceivesChildren && survivor.getStatus() != Status.ACCEPTED) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "survivor must be an accepted name to receive children");
    }

    for (int d : mergedIds) {
      NameUsage du = usages.findByIdInProject(projectId, d);
      // repoint EVERY fk before deleting (SET NULL / CASCADE would otherwise lose the links)
      usages.reparentChildren(projectId, d, survivorId, userId);
      merge.repointBasionym(projectId, d, survivorId);
      merge.deleteSynonymCollisions(projectId, d, survivorId);
      merge.repointSynonymId(projectId, d, survivorId);
      merge.deleteAcceptedCollisions(projectId, d, survivorId);
      merge.repointAcceptedId(projectId, d, survivorId);
      merge.dropSynonymSelfLinks(projectId);
      merge.dropTaxonInfoIfSurvivorHas(projectId, d, survivorId);
      merge.repointTaxonInfo(projectId, d, survivorId);
      merge.repointRelationUsage(projectId, d, survivorId);
      merge.repointRelationRelated(projectId, d, survivorId);
      merge.dropSelfRelations(projectId);
      merge.repointVernacular(projectId, d, survivorId);
      merge.repointDistribution(projectId, d, survivorId);
      merge.repointMedia(projectId, d, survivorId);
      merge.repointTypeMaterial(projectId, d, survivorId);
      merge.repointProperty(projectId, d, survivorId);
      merge.repointEstimate(projectId, d, survivorId);
      audit.record(projectId, userId, "name_usage", d,
          org.catalogueoflife.editor.audit.Operation.DELETE, du, null);
      usages.delete(projectId, d);
    }
    events.publishEvent(org.catalogueoflife.editor.validation.ValidationEvent.forUsage(projectId, survivorId));
    return new org.catalogueoflife.editor.mergerecords.dto.MergeResult(survivorId, mergedIds.size());
  }
```

(Verify `NameUsageMapper.delete(int projectId, int id)` and `reparentChildren(int projectId,int oldParentId,Integer newParentId,int modifiedBy)` signatures against the mapper; adjust arg order if needed.)

- [ ] **Step 6: Add the merge endpoint to `MergeRecordsController`**

```java
  @PostMapping("/usages/merge")
  public org.catalogueoflife.editor.mergerecords.dto.MergeResult mergeUsages(
      @PathVariable int pid, @RequestBody MergeRequest req) {
    int uid = currentUser.require().getId();
    return service.mergeUsages(uid, pid, req.survivorId(), req.ids());
  }
```

- [ ] **Step 7: Run the ITs to verify they pass**

Run: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw -q -Dtest=UsageMergeApplyIT,UsageMergePreviewIT test`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/org/catalogueoflife/editor/mergerecords backend/src/test/java/org/catalogueoflife/editor/mergerecords/UsageMergeApplyIT.java
git commit -m "feat(merge): transactional name merge (repoint all FKs to survivor, delete duplicates)"
```

---

### Task 3: Frontend — Names multi-select + MergeRecordsModal

**Files:**
- Create: `frontend/src/api/merge.ts`
- Create: `frontend/src/merge/MergeRecordsModal.tsx`
- Modify: `frontend/src/names/NameSearchPage.tsx` (row selection + Merge toolbar button)
- Test: `frontend/src/merge/MergeRecordsModal.test.tsx`

**Interfaces:**
- Consumes: `POST /api/projects/{pid}/usages/merge/preview`, `POST /api/projects/{pid}/usages/merge`.

- [ ] **Step 1: Write the failing test**

`MergeRecordsModal.test.tsx`: stub `previewUsages` → two candidates (id 1 with counts summing higher, id 2 lower); render `<MergeRecordsModal entity="usage" pid={1} ids={[1,2]} opened .../>`; assert both ids render, the survivor defaults to the most-connected (id 1), and clicking "Merge 1 into …" (after choosing) calls `mergeUsages` and the `onDone` callback fires. Mirror `BulkAddModal.test.tsx`'s `vi.spyOn` + `render` from `../test/utils` style.

- [ ] **Step 2: Run it to verify it fails**

Run: `cd frontend && npx vitest run src/merge/MergeRecordsModal.test.tsx`
Expected: FAIL — module missing.

- [ ] **Step 3: Create `api/merge.ts`**

```ts
import { api } from './client';

export interface MergeCandidate {
  id: number;
  alternativeId: string[] | null;
  scientificName?: string | null;
  authorship?: string | null;
  rank?: string | null;
  status?: string | null;
  citation?: string | null;
  doi?: string | null;
  counts: Record<string, number>;
}
export interface MergeResult { survivorId: number; mergedCount: number; }

export function previewUsageMerge(pid: number, ids: number[]): Promise<MergeCandidate[]> {
  return api<MergeCandidate[]>(`/api/projects/${pid}/usages/merge/preview`, { method: 'POST', json: { ids } });
}
export function mergeUsages(pid: number, survivorId: number, ids: number[]): Promise<MergeResult> {
  return api<MergeResult>(`/api/projects/${pid}/usages/merge`, { method: 'POST', json: { survivorId, ids } });
}
export function previewReferenceMerge(pid: number, ids: number[]): Promise<MergeCandidate[]> {
  return api<MergeCandidate[]>(`/api/projects/${pid}/references/merge/preview`, { method: 'POST', json: { ids } });
}
export function mergeReferences(pid: number, survivorId: number, ids: number[]): Promise<MergeResult> {
  return api<MergeResult>(`/api/projects/${pid}/references/merge`, { method: 'POST', json: { survivorId, ids } });
}
```

- [ ] **Step 4: Create `MergeRecordsModal.tsx`**

A `<Modal>` that, on open, calls the entity's preview (`entity==='usage'` → `previewUsageMerge`, else `previewReferenceMerge`), renders each candidate (already id-ascending from the backend) with its id + alternativeId + label (scientificName+authorship+rank / citation+doi) + counts badges, a `<Radio.Group>` survivor selector defaulted to the id with the max total counts (sum of `counts` values), and a "Merge {ids.length} records into …" button calling the entity's merge fn. On success: notify, call `onDone()` (which clears selection + invalidates queries), close. Props: `{ entity: 'usage'|'reference'; pid: number; ids: number[]; opened: boolean; onClose: () => void; onDone: () => void }`. Compute default survivor: `[...cands].sort((a,b)=> total(b)-total(a) || a.id-b.id)[0].id` where `total(c)=Object.values(c.counts).reduce((s,n)=>s+n,0)`.

- [ ] **Step 5: Wire multi-select into `NameSearchPage.tsx`**

In `useMantineReactTable`: add `enableRowSelection: true`, add `rowSelection` to `state`, add `onRowSelectionChange: setRowSelection` (`const [rowSelection, setRowSelection] = useState({})`). `getRowId` already returns `String(row.id)`. Above the table, when `Object.keys(rowSelection).length >= 2 && canEdit`, render a `<Button onClick={() => setMergeOpen(true)}>Merge {n} selected…</Button>`. Render `<MergeRecordsModal entity="usage" pid={pid} ids={Object.keys(rowSelection).map(Number)} opened={mergeOpen} onClose={() => setMergeOpen(false)} onDone={() => { setRowSelection({}); queryClient.invalidateQueries({ queryKey: ['usageSearch', pid] }); queryClient.invalidateQueries({ queryKey: ['treeChildren', pid] }); queryClient.invalidateQueries({ queryKey: ['treeRoots', pid] }); }} />`.

- [ ] **Step 6: Run the test + typecheck + full suite**

Run: `cd frontend && npx vitest run src/merge/MergeRecordsModal.test.tsx && npx tsc -b && npx vitest run`
Expected: PASS + clean.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/api/merge.ts frontend/src/merge/MergeRecordsModal.tsx frontend/src/merge/MergeRecordsModal.test.tsx frontend/src/names/NameSearchPage.tsx
git commit -m "feat(merge): Names multi-select + merge modal (survivor + counts)"
```

---

## Phase 2 — Reference merge

### Task 4: Reference merge (preview + apply)

**Files:**
- Modify: `MergeRecordsMapper.java` (reference count + repoint methods)
- Modify: `MergeRecordsService.java` (`previewReferences` + `mergeReferences`)
- Modify: `MergeRecordsController.java` (2 endpoints)
- Create: `dto/ReferenceMergeCandidate.java`
- Test: `backend/src/test/java/org/catalogueoflife/editor/mergerecords/ReferenceMergeIT.java`

**Interfaces:**
- Consumes: `ReferenceMapper.findByIdInProject` (or the existing reference read); `ReferenceMapper`/`NameUsageMapper.removeReferenceIdFromAll` precedent for the array column.
- Produces: `MergeRecordsService.previewReferences/mergeReferences`; `POST /api/projects/{pid}/references/merge/preview` + `/references/merge`.

- [ ] **Step 1: Write the failing IT**

`ReferenceMergeIT.java`: create a project + 2 references (survivor `S`, dup `D`); create a usage with `publishedInReferenceId = D` (via the usage create/update or `setUsageReferences`), and a vernacular with `reference_id = D`. Preview `[S,D]` → assert counts (D has ≥1 citing usage). Merge `{survivorId:S, ids:[S,D]}` → assert 200, `D` is gone (`GET …/references/D` → 404), and the usage's `publishedInReferenceId` (and the vernacular's `reference_id`) now equal `S`. Plus non-editor 403, `<2` ids 400.

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw -q -Dtest=ReferenceMergeIT test`
Expected: FAIL.

- [ ] **Step 3: Add reference count + repoint methods to `MergeRecordsMapper`**

```java
  @Select("""
      SELECT
        (SELECT count(*) FROM name_usage    WHERE project_id = #{pid} AND published_in_reference_id = #{id}) AS "publishedIn",
        (SELECT count(*) FROM name_usage    WHERE project_id = #{pid} AND #{id} = ANY(reference_id))          AS "citedBy",
        (SELECT count(*) FROM name_relation WHERE project_id = #{pid} AND reference_id = #{id})               AS "nameRelations",
        (SELECT count(*) FROM type_material WHERE project_id = #{pid} AND reference_id = #{id})               AS "typeMaterial",
        (SELECT count(*) FROM vernacular    WHERE project_id = #{pid} AND reference_id = #{id})               AS vernacular,
        (SELECT count(*) FROM distribution  WHERE project_id = #{pid} AND reference_id = #{id})               AS distribution,
        (SELECT count(*) FROM estimate      WHERE project_id = #{pid} AND reference_id = #{id})               AS estimate,
        (SELECT count(*) FROM property      WHERE project_id = #{pid} AND reference_id = #{id})               AS property
      """)
  Map<String, Object> referenceCounts(@Param("pid") int pid, @Param("id") int id);

  @Update("UPDATE name_usage SET published_in_reference_id = #{survivor} WHERE project_id = #{pid} AND published_in_reference_id = #{merged}")
  int repointPublishedIn(@Param("pid") int pid, @Param("merged") int merged, @Param("survivor") int survivor);
  // array column: replace merged->survivor in every citing usage's reference_id[], then de-dup the array
  @Update("UPDATE name_usage SET reference_id = array_replace(reference_id, #{merged}, #{survivor}) WHERE project_id = #{pid} AND #{merged} = ANY(reference_id)")
  int repointReferenceArray(@Param("pid") int pid, @Param("merged") int merged, @Param("survivor") int survivor);
  @Update("UPDATE name_usage SET reference_id = (SELECT array_agg(DISTINCT e) FROM unnest(reference_id) e) WHERE project_id = #{pid} AND #{survivor} = ANY(reference_id)")
  int dedupReferenceArray(@Param("pid") int pid, @Param("survivor") int survivor);

  @Update("UPDATE name_relation SET reference_id = #{survivor} WHERE project_id = #{pid} AND reference_id = #{merged}") int repointRefNameRelation(@Param("pid") int pid, @Param("merged") int merged, @Param("survivor") int survivor);
  @Update("UPDATE type_material SET reference_id = #{survivor} WHERE project_id = #{pid} AND reference_id = #{merged}") int repointRefTypeMaterial(@Param("pid") int pid, @Param("merged") int merged, @Param("survivor") int survivor);
  @Update("UPDATE vernacular    SET reference_id = #{survivor} WHERE project_id = #{pid} AND reference_id = #{merged}") int repointRefVernacular(@Param("pid") int pid, @Param("merged") int merged, @Param("survivor") int survivor);
  @Update("UPDATE distribution  SET reference_id = #{survivor} WHERE project_id = #{pid} AND reference_id = #{merged}") int repointRefDistribution(@Param("pid") int pid, @Param("merged") int merged, @Param("survivor") int survivor);
  @Update("UPDATE estimate      SET reference_id = #{survivor} WHERE project_id = #{pid} AND reference_id = #{merged}") int repointRefEstimate(@Param("pid") int pid, @Param("merged") int merged, @Param("survivor") int survivor);
  @Update("UPDATE property      SET reference_id = #{survivor} WHERE project_id = #{pid} AND reference_id = #{merged}") int repointRefProperty(@Param("pid") int pid, @Param("merged") int merged, @Param("survivor") int survivor);
  @Delete("DELETE FROM reference WHERE project_id = #{pid} AND id = #{id}")
  int deleteReference(@Param("pid") int pid, @Param("id") int id);
```

- [ ] **Step 4: Add `ReferenceMergeCandidate` DTO + the service methods**

`dto/ReferenceMergeCandidate.java`: `record ReferenceMergeCandidate(int id, List<String> alternativeId, String citation, String doi, Map<String,Integer> counts)`.

In `MergeRecordsService`: add `previewReferences(userId, projectId, ids)` (mirror `previewUsages`, using `referenceCounts` + the reference read for citation/doi/alternativeId — inject `ReferenceMapper`) and `@Transactional mergeReferences(userId, projectId, survivorId, ids)`: `requireEditor`; validate survivor ∈ ids, ≥2; for each merged `D`: `repointPublishedIn`, `repointReferenceArray` + `dedupReferenceArray`, the 6 child `repointRef*`, `audit.record(..., "reference", D, DELETE, ref, null)`, `deleteReference`. (No tree lock needed — references aren't tree nodes; but the whole op is one `@Transactional`.)

- [ ] **Step 5: Add the two reference endpoints to `MergeRecordsController`** (mirror the usage ones at `/references/merge/preview` + `/references/merge`).

- [ ] **Step 6: Run the IT to verify it passes**

Run: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw -q -Dtest=ReferenceMergeIT,UsageMergeApplyIT,UsageMergePreviewIT test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/org/catalogueoflife/editor/mergerecords backend/src/test/java/org/catalogueoflife/editor/mergerecords/ReferenceMergeIT.java
git commit -m "feat(merge): reference merge (repoint published-in + reference_id[] + child refs, delete dups)"
```

---

### Task 5: Frontend — References multi-select + merge

**Files:**
- Modify: `frontend/src/references/ReferencesPage.tsx` (checkboxes + Merge button)
- Test: `frontend/src/references/ReferencesPage.test.tsx` (add a merge case)

**Interfaces:**
- Consumes: `previewReferenceMerge`, `mergeReferences` from `api/merge.ts`; reuses `MergeRecordsModal` with `entity="reference"`.

- [ ] **Step 1: Write the failing test** — add a case to `ReferencesPage.test.tsx`: select 2 reference rows (click their checkboxes), click "Merge 2 selected…", and (with `previewReferenceMerge`/`mergeReferences` stubbed) assert the modal opens and the merge call fires + the list refreshes. Adapt to the file's existing MSW/render helpers.

- [ ] **Step 2: Run it to verify it fails** — `cd frontend && npx vitest run src/references/ReferencesPage.test.tsx` → FAIL.

- [ ] **Step 3: Add checkboxes + merge to `ReferencesPage.tsx`** — the page is a plain Mantine `<Table>`; add a leading `<Table.Th />`/`<Table.Td>` with a `<Checkbox>` per row tracking `const [selected, setSelected] = useState<Set<number>>(new Set())` (toggle on change); when `selected.size >= 2 && canEdit`, show a `<Button>Merge {selected.size} selected…</Button>` above the table opening `<MergeRecordsModal entity="reference" pid={pid} ids={[...selected]} opened={mergeOpen} onClose={...} onDone={() => { setSelected(new Set()); queryClient.invalidateQueries({ queryKey: ['references', pid] }); }} />`. (Confirm the references query key from the file.)

- [ ] **Step 4: Run the test + typecheck + full suite** — `cd frontend && npx vitest run src/references/ReferencesPage.test.tsx && npx tsc -b && npx vitest run` → PASS + clean.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/references/ReferencesPage.tsx frontend/src/references/ReferencesPage.test.tsx
git commit -m "feat(merge): References multi-select + merge modal reuse"
```

---

## Final verification (after all tasks)

- [ ] Backend full suite: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw -q test` → green.
- [ ] Frontend gates: `cd frontend && npx tsc -b && npx vitest run` → clean + green.
- [ ] Manual smoke (via `docker compose up`): in Names, select 2 duplicate genera → Merge → confirm the survivor keeps its children + gains the duplicate's; same for two references.

## Notes carried from the spec

- Detection stays with the user (search + later filters — a phase-3 add). No async scan/job.
- Survivor scalar fields win; only dependents move; no field-level "fill gaps"; no undo (the change log records the merge).
- Merge is synchronous + transactional (bounded operation) — no run/poll machinery.
