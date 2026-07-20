# Homotypic Grouping — Side 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Detect the homotypic structure within one accepted taxon's synonymy, persist it as `name_relation` rows, and render the synonymy nested (basionym-anchored, `≡`/`=`) like the COL portal — making `name_relation` the single source of truth for basionym by dropping the `name_usage.basionym_id` column.

**Architecture:** A compact, pure `HomotypyDetector` clusters an accepted taxon's names by normalized terminal epithet + basionym-or-combination author team (reusing `SciNameNormalizer` + `AuthorshipNormalizer`, both already on the classpath via `org.catalogueoflife:api`), working off the parsed authorship fields already stored on each usage. Detected groups are proposed to the curator, who confirms them into `name_relation` rows. A recursive-CTE closure over `name_relation` drives a nested synonymy read model. The `basionym_id` column is removed; import converts inbound ColDP `basionymID` into a `basionym` relation, export emits basionym only through `NameRelation.tsv`, and the two merge subsystems (which already copy/repoint `name_relation`) shed their now-redundant basionym special-casing.

**Tech Stack:** Java 25 / Spring Boot 4.1 / MyBatis annotation mappers / Postgres 17 (recursive CTE) / Flyway; React + Mantine + React Query + vitest + msw.

## Global Constraints

- Build backend: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw …`.
- Run one IT: `-Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=X -DfailIfNoTests=false verify`; run one unit test: `-Dtest=X test`. Run full `./mvnw clean verify` after schema/record-arity changes.
- Frontend: `cd frontend && npx vitest run <path>` / `npx tsc -b` / `npm run build`.
- Commit directly to `main` (no branches). End every commit message with:
  `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>` and
  `Claude-Session: https://claude.ai/code/session_0187Tf9weEuVzi834TWXv5pi`.
- Per-project compound `(project_id, id)` PKs; allocate child ids via `idSeq.allocate(projectId, entity)`.
- Homotypic relation types (canonical UI form, lowercase): `basionym`, `homotypic`, `spelling correction`, `based on`, `replacement name`, `superfluous`. Stored `name_relation.type` is **not** canonical (import writes it raw), so every comparison normalizes: `lower(type)` with `_`/`-` → space.
- Owner/editor gate for writes (`requireEditor`); any member may read (`requireRole` / `requireVisible`).

---

## File Structure

**Backend — new**
- `db/migration/V34__drop_basionym_id.sql` — drop the column.
- `name/homotypy/HomotypyDetector.java` — pure detection engine.
- `name/homotypy/HomotypicRelations.java` — the canonical homotypic-type set + `normalize(String)` helper (shared by detector + mapper filter).
- `name/homotypy/dto/ProposedRelation.java`, `ProposedGroup.java`, `HomotypyProposal.java`, `ApplyHomotypicRequest.java`, `SynonymEntry.java`, `Synonymy.java`.
- `name/homotypy/SynonymyMapper.java` — recursive-CTE homotypic closure.
- `name/homotypy/HomotypyService.java` — detect / apply / synonymy orchestration.
- `name/homotypy/HomotypyController.java` — the three endpoints.

**Backend — modified**
- `name/NameUsage.java`, `name/NameUsageMapper.java` — drop `basionymId`.
- `coldp/imprt/ImportRunService.java` — Pass 2 basionym → relation; `loadNameRelation` dedup.
- `coldp/export/NameUsageColdpWriter.java` — stop emitting `basionymID`.
- `child/NameRelationMapper.java` — add `exists(...)`.
- `merge/MergeApplyService.java` — drop Pass-2 basionym resolution.
- `mergerecords/MergeRecordsMapper.java`, `mergerecords/MergeRecordsService.java`, `mergerecords/dto/UsageMergeCandidate.java` — drop `repointBasionym` + `basionymOf`.
- `clb/ClbUsageMapper.java` — comment only.

**Backend — tests**
- Modified: `coldp/imprt/ImportNameUsageIT.java`, `coldp/imprt/ImportSplitFormIT.java`, `coldp/imprt/ImportExportRoundTripIT.java` (basionym assertions → relation). Check/patch `coldp/export/NameUsageExportIT.java`, `coldp/export/ExportRoundTripIT.java`, `mergerecords/UsageMergeApplyIT.java`.
- New: `name/homotypy/HomotypyDetectorTest.java`, `name/homotypy/HomotypyApiIT.java`.

**Frontend — new**
- `tree/Synonymy.tsx`, `tree/HomotypicGroupModal.tsx` (+ `.test.tsx` each).

**Frontend — modified**
- `api/usages.ts` (+ types), `tree/TaxonDetail.tsx` (render `Synonymy` for accepted usages).

---

## Task 1: Drop `basionym_id`; store & move basionym via `name_relation`

This is one atomic refactor — the code does not compile between the POJO/mapper change and its call-site updates, so all edits land together and are gated by the existing import/export/merge ITs (updated to assert relations instead of the column).

**Files:**
- Create: `backend/src/main/resources/db/migration/V34__drop_basionym_id.sql`
- Modify: `backend/src/main/java/org/catalogueoflife/editor/name/NameUsage.java`, `name/NameUsageMapper.java`, `coldp/imprt/ImportRunService.java`, `coldp/export/NameUsageColdpWriter.java`, `child/NameRelationMapper.java`, `merge/MergeApplyService.java`, `mergerecords/MergeRecordsMapper.java`, `mergerecords/MergeRecordsService.java`, `mergerecords/dto/UsageMergeCandidate.java`, `clb/ClbUsageMapper.java`
- Test: `coldp/imprt/ImportNameUsageIT.java`, `coldp/imprt/ImportSplitFormIT.java`, `coldp/imprt/ImportExportRoundTripIT.java`

**Interfaces:**
- Produces: `NameRelationMapper.exists(int projectId, int usageId, int relatedUsageId, String type) -> boolean`; `NameUsageMapper.updateHierarchy(int projectId, int id, Integer parentId, int modifiedBy)` (basionymId param removed).
- Consumes: `NameRelationMapper.insert(projectId, id, usageId, NameRelationRequest, modifiedBy)`, `idSeq.allocate(projectId, "name_relation")`, `NameRelationRequest(Integer relatedUsageId, String type, Integer referenceId, String page, String remarks, Integer version)`.

- [ ] **Step 1: Add `exists` to `NameRelationMapper`**

Add after `findById` (line ~30) in `child/NameRelationMapper.java`:

```java
  // True if a relation with this exact (usage_id, related_usage_id, type) already exists, comparing
  // type case-insensitively with _/- normalized to spaces (import stores raw ColDP type values, so
  // the same relation may read as 'basionym' or 'BASIONYM'). Used to dedup import + apply.
  @Select("""
      SELECT count(*) > 0 FROM name_relation
      WHERE project_id = #{projectId} AND usage_id = #{usageId}
        AND related_usage_id = #{relatedUsageId}
        AND lower(regexp_replace(type, '[_-]', ' ', 'g')) = lower(regexp_replace(#{type}, '[_-]', ' ', 'g'))
      """)
  boolean exists(@Param("projectId") int projectId, @Param("usageId") int usageId,
      @Param("relatedUsageId") int relatedUsageId, @Param("type") String type);
```

- [ ] **Step 2: Rewrite the three import/export ITs' basionym assertions (RED)**

`ImportNameUsageIT.java` — add the mapper autowire near the others (after line ~78):

```java
  @Autowired org.catalogueoflife.editor.child.NameRelationMapper nameRelations;
```

Replace lines 212–213:

```java
    // Forward basionym reference (row 4 -> row 10) resolved into a `basionym` name_relation
    // (the basionym_id column was dropped; name_relation is the single source of truth).
    assertThat(nameRelations.findByUsage(pantheraLeo.getProjectId(), pantheraLeo.getId()))
        .anySatisfy(r -> {
          assertThat(r.type()).isEqualToIgnoringCase("basionym");
          assertThat(r.relatedUsageId()).isEqualTo(felisLeo.getId());
        });
```

`ImportSplitFormIT.java` — add the same `@Autowired NameRelationMapper nameRelations;` and replace line 248:

```java
    assertThat(nameRelations.findByUsage(felisLeo.getProjectId(), felisLeo.getId()))
        .anySatisfy(r -> {
          assertThat(r.type()).isEqualToIgnoringCase("basionym");
          assertThat(r.relatedUsageId()).isEqualTo(pantheraLeo.getId());
        });
```

`ImportExportRoundTripIT.java` — add the autowire and replace line 339:

```java
    assertThat(nameRelations.findByUsage(newFelisCatus.getProjectId(), newFelisCatus.getId()))
        .anySatisfy(r -> {
          assertThat(r.type()).isEqualToIgnoringCase("basionym");
          assertThat(r.relatedUsageId()).isEqualTo(newPlainSynonym.getId());
        });
```

- [ ] **Step 3: Run the ITs to verify they fail**

Run: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=ImportNameUsageIT -DfailIfNoTests=false verify`
Expected: FAIL — `findByUsage(...)` returns empty (no basionym relation created yet).

- [ ] **Step 4: Redirect import Pass 2 to create a `basionym` relation**

In `coldp/imprt/ImportRunService.java`, replace the Pass-2 body (lines 550–565) so basionym becomes a relation and `updateHierarchy` no longer takes a basionym id:

```java
    for (Pending p : pending) {
      Map<ColdpTerm, String> row = p.row();
      String rowId = row.get(ColdpTerm.ID);
      Integer basionymNewId =
          resolveUsageRef(ctx, nameIdToUsage, row.get(ColdpTerm.basionymID), "basionym", rowId);
      if (p.usage().getStatus() == Status.ACCEPTED) {
        Integer parentNewId = resolveUsageRef(ctx, null, row.get(ColdpTerm.parentID), "parent", rowId);
        usages.updateHierarchy(ctx.projectId, p.usage().getId(), parentNewId, userId);
      } else {
        Integer acceptedNewId = resolveUsageRef(ctx, null, row.get(ColdpTerm.parentID), "parent", rowId);
        usages.updateHierarchy(ctx.projectId, p.usage().getId(), null, userId);
        if (acceptedNewId != null) {
          synonymAccepted.link(ctx.projectId, p.usage().getId(), acceptedNewId, 0);
        }
      }
      // basionymID is now a `basionym` name_relation (recombination -> basionym), not a column.
      if (basionymNewId != null && !basionymNewId.equals(p.usage().getId())) {
        int relId = idSeq.allocate(ctx.projectId, NAME_RELATION_ENTITY);
        NameRelationRequest r =
            new NameRelationRequest(basionymNewId, "basionym", null, null, null, null);
        nameRelations.insert(ctx.projectId, relId, p.usage().getId(), r, userId);
      }
    }
```

- [ ] **Step 5: Dedup `NameRelation.tsv` basionym rows against Pass 2**

In `loadNameRelation` (line ~608), guard the insert (Pass 2 runs first, so a redundant `NameRelation.tsv` basionym row would double it). Replace the final two lines of the `forEach` body:

```java
      if (nameRelations.exists(ctx.projectId, usageId, relatedUsageId, r.type())) {
        return; // already created (e.g. the basionymID column redirect above) -- don't duplicate
      }
      int id = idSeq.allocate(ctx.projectId, NAME_RELATION_ENTITY);
      nameRelations.insert(ctx.projectId, id, usageId, r, userId);
```

- [ ] **Step 6: Drop `basionym_id` from the `NameUsage` POJO**

In `name/NameUsage.java`: delete `private Integer basionymId;` (line 24) and both accessors (lines 74–75). Update the class-doc line 16 to drop `basionymId` from the "scoped to projectId" list.

- [ ] **Step 7: Drop `basionym_id` from `NameUsageMapper`**

In `name/NameUsageMapper.java`:
- Insert (lines 24–48): remove `basionym_id,` from the column list (line 26) and `#{basionymId},` from the values (line 38).
- `updateHierarchy` (lines 368–374): remove `basionym_id = #{basionymId},` from the SQL and the `@Param("basionymId") Integer basionymId,` parameter:

```java
  @Update("""
      UPDATE name_usage SET parent_id = #{parentId},
         version = version + 1, modified = now(), modified_by = #{modifiedBy}
      WHERE project_id = #{projectId} AND id = #{id}""")
  int updateHierarchy(@Param("projectId") int projectId, @Param("id") int id,
      @Param("parentId") Integer parentId, @Param("modifiedBy") int modifiedBy);
```
- `update` (line 379): remove `basionym_id = #{basionymId}, ` from the SET list (keep `parent_id = #{parentId}, ordinal = #{ordinal},`).

- [ ] **Step 8: Add the migration**

Create `backend/src/main/resources/db/migration/V34__drop_basionym_id.sql`:

```sql
-- name_relation is now the single source of truth for basionym (and every other name relation);
-- the basionym_id column + its compound self-FK are removed. Import converts inbound ColDP
-- basionymID into a `basionym` relation; export emits it only via NameRelation.tsv. CASCADE drops
-- the inline (Postgres auto-named) compound FK (project_id, basionym_id) declared in V3.
ALTER TABLE name_usage DROP COLUMN basionym_id CASCADE;
```

- [ ] **Step 9: Stop exporting `basionymID`**

In `coldp/export/NameUsageColdpWriter.java`, delete line 103 (`row.put(ColdpTerm.basionymID, str(u.getBasionymId()));`). Basionym now exports through `ChildColdpWriter`'s `NameRelation.tsv` (unchanged).

- [ ] **Step 10: Drop basionym Pass-2 resolution in `MergeApplyService`**

`name_relation` entities are already copied by `nameRelationOps` (lines 1059–1075), so basionym survives a merge as a relation. In `merge/MergeApplyService.applyOneUsagePass2` (lines 640–668), delete the `basionymNewId` lines (644–645) and drop the arg from both `updateHierarchy` calls:

```java
    if (tgt.getStatus() == Status.ACCEPTED) {
      Integer parentNewId = src.getParentId() == null ? null
          : usageIdMap.get(String.valueOf(src.getParentId()));
      if (src.getParentId() != null && parentNewId == null) {
        issues.add(new MergeIssue("name_usage", String.valueOf(src.getId()),
            "unanchored: " + tgt.getScientificName()));
      }
      usages.updateHierarchy(targetId, tgt.getId(), parentNewId, userId);
    } else {
      usages.updateHierarchy(targetId, tgt.getId(), null, userId);
```

Update the method's javadoc (lines 637–639) to drop the "basionym_id" mention.

- [ ] **Step 11: Drop `repointBasionym` + `basionymOf` from merge-records**

In `mergerecords/MergeRecordsMapper.java`: delete the `basionymOf` subquery line (20) from `usageCounts`, and delete `repointBasionym` (lines 32–33). Basionym relations are already repointed by `repointRelationUsage`/`repointRelationRelated` (67–70) and counted by `nameRelations` (21).

In `mergerecords/MergeRecordsService.java`: delete the `merge.repointBasionym(projectId, d, survivorId);` call (line 112) and drop "basionym_id" from the comment on line 76.

In `mergerecords/dto/UsageMergeCandidate.java`: if `basionymOf` is a record component/field, remove it and update the doc comment (line 5); if the counts are carried as a `Map`, just update the comment. (Grep `basionymOf` in the `mergerecords` package to confirm no remaining reference.)

- [ ] **Step 12: Fix the `ClbUsageMapper` comment**

In `clb/ClbUsageMapper.java` line 66, remove `getBasionymId()` from the comment (leave `getId()/getProjectId()/getParentId()`).

- [ ] **Step 13: Patch any export/merge ITs that assert `basionymID`/`basionym_id`**

Run these greps and update each hit the same way (assert a `basionym` relation, or drop a `basionymID`-column expectation):

```bash
cd backend && grep -rn "getBasionymId\|setBasionymId\|ColdpTerm.basionymID\|basionym_id" src/test/java | grep -v basionymAuthorship
```
Expected remaining legitimate hits: fixture rows that *provide* `ColdpTerm.basionymID` as import input (keep those). Any assertion on `getBasionymId()` or on an exported `basionymID` column value must change — for a round-trip export test, assert the basionym appears in the parsed `NameRelation` rows and that the `NameUsage` row's `basionymID` cell is empty/absent.

- [ ] **Step 14: Compile + run the affected ITs (GREEN)**

Run:
```bash
cd backend && JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=ImportNameUsageIT,ImportSplitFormIT,ImportExportRoundTripIT,UsageMergeApplyIT,NameUsageExportIT,ExportRoundTripIT -DfailIfNoTests=false verify
```
Expected: PASS (all green). Fix compile errors from any missed `basionymId` reference the grep in Step 13 surfaces.

- [ ] **Step 15: Full clean verify (schema + arity change)**

Run: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw clean verify`
Expected: BUILD SUCCESS. (Pre-existing environment-only failures: `ChangeApiIT` needs network for CSL; the full-suite "too many clients already" Postgres exhaustion — both unrelated to this change.)

- [ ] **Step 16: Commit**

```bash
cd /Users/markus/code/col/coldp-editor
git add -A
git commit -m "$(cat <<'EOF'
refactor(name): drop basionym_id; store basionym as a name_relation

name_relation becomes the single source of truth for basionym. The
name_usage.basionym_id column + its compound self-FK are dropped (V34). Import
converts inbound ColDP basionymID into a `basionym` relation (deduped against an
explicit NameRelation.tsv row); export emits basionym only via NameRelation.tsv.
The two merge subsystems shed their now-redundant basionym special-casing — merge
already copies/repoints name_relation, so basionym rides along.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_0187Tf9weEuVzi834TWXv5pi
EOF
)"
```

---

## Task 2: Homotypy detector (pure) + DTOs

**Files:**
- Create: `backend/src/main/java/org/catalogueoflife/editor/name/homotypy/HomotypicRelations.java`, `HomotypyDetector.java`, and `dto/{ProposedRelation,ProposedGroup,HomotypyProposal}.java`
- Test: `backend/src/test/java/org/catalogueoflife/editor/name/homotypy/HomotypyDetectorTest.java`

**Interfaces:**
- Produces: `HomotypyDetector.detect(NameUsage accepted, List<NameUsage> synonyms, Set<String> existingKeys) -> HomotypyProposal`, where `existingKeys` holds `usageId + ":" + relatedUsageId + ":" + normalizedType` for relations already present. `HomotypicRelations.TYPES` (Set<String> canonical), `HomotypicRelations.normalize(String) -> String`.
- Consumes: `NameUsage` getters (`getId`, `getStatus`, `getGenus`, `getUninomial`, `getSpecificEpithet`, `getInfraspecificEpithet`, `getCombinationAuthorship`, `getCombinationAuthorshipYear`, `getBasionymAuthorship`, `getBasionymAuthorshipYear`); `org.catalogueoflife.editor.name.Status.MISAPPLIED`.

- [ ] **Step 1: Write the failing detector test**

Create `HomotypyDetectorTest.java`. Fixtures use setters (the `SimpleName(String,String,Rank)` constructor is `(id,name,rank)`, so build `NameUsage` via setters):

```java
package org.catalogueoflife.editor.name.homotypy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.name.Status;
import org.catalogueoflife.editor.name.homotypy.dto.HomotypyProposal;
import org.junit.jupiter.api.Test;

class HomotypyDetectorTest {

  private final HomotypyDetector detector = new HomotypyDetector();

  private NameUsage usage(int id, Status status, String genus, String sp, String infra,
      String combAuthor, String combYear, String basAuthor, String basYear) {
    NameUsage u = new NameUsage();
    u.setId(id);
    u.setProjectId(1);
    u.setStatus(status);
    u.setGenus(genus);
    u.setSpecificEpithet(sp);
    u.setInfraspecificEpithet(infra);
    u.setCombinationAuthorship(combAuthor);
    u.setCombinationAuthorshipYear(combYear);
    u.setBasionymAuthorship(basAuthor);
    u.setBasionymAuthorshipYear(basYear);
    return u;
  }

  @Test
  void recombinationGroupsToAcceptedBasionym() {
    // Poa annua L. (basionym) + Ochlopoa annua (L.) H.Scholz (recombination)
    NameUsage accepted = usage(1, Status.ACCEPTED, "Poa", "annua", null, "L.", "1753", null, null);
    NameUsage recomb = usage(2, Status.SYNONYM, "Ochlopoa", "annua", null, "H.Scholz", null, "L.", null);

    HomotypyProposal p = detector.detect(accepted, List.of(recomb), Set.of());

    // one group with basionym=1, one basionym relation 2 -> 1
    assertThat(p.groups()).anySatisfy(g -> {
      assertThat(g.basionymUsageId()).isEqualTo(1);
      assertThat(g.relations()).anySatisfy(r -> {
        assertThat(r.usageId()).isEqualTo(2);
        assertThat(r.relatedUsageId()).isEqualTo(1);
        assertThat(r.type()).isEqualTo("basionym");
        assertThat(r.alreadyExists()).isFalse();
      });
    });
  }

  @Test
  void differentEpithetsStayInSeparateGroups() {
    NameUsage accepted = usage(1, Status.ACCEPTED, "Poa", "annua", null, "L.", "1753", null, null);
    NameUsage aira = usage(3, Status.SYNONYM, "Aira", "pumila", null, "Pursh", "1814", null, null);
    NameUsage catabrosa = usage(4, Status.SYNONYM, "Catabrosa", "pumila", null, "Roem. & Schult.", null, "Pursh", null);

    HomotypyProposal p = detector.detect(accepted, List.of(aira, catabrosa), Set.of());

    // Aira pumila is a basionym with Catabrosa pumila as its recombination; not merged with Poa annua
    assertThat(p.groups()).anySatisfy(g -> {
      assertThat(g.basionymUsageId()).isEqualTo(3);
      assertThat(g.memberUsageIds()).containsExactlyInAnyOrder(3, 4);
    });
    assertThat(p.groups()).noneSatisfy(g -> assertThat(g.memberUsageIds()).contains(1, 3));
  }

  @Test
  void missingYearStillMatches() {
    NameUsage accepted = usage(1, Status.ACCEPTED, "Poa", "annua", null, "L.", "1753", null, null);
    NameUsage recomb = usage(2, Status.SYNONYM, "Ochlopoa", "annua", null, "H.Scholz", null, "L.", null);
    HomotypyProposal p = detector.detect(accepted, List.of(recomb), Set.of());
    assertThat(p.groups()).anySatisfy(g -> assertThat(g.memberUsageIds()).contains(1, 2));
  }

  @Test
  void existingRelationIsFlagged() {
    NameUsage accepted = usage(1, Status.ACCEPTED, "Poa", "annua", null, "L.", "1753", null, null);
    NameUsage recomb = usage(2, Status.SYNONYM, "Ochlopoa", "annua", null, "H.Scholz", null, "L.", null);
    HomotypyProposal p = detector.detect(accepted, List.of(recomb), Set.of("2:1:basionym"));
    assertThat(p.groups())
        .flatExtracting(g -> g.relations())
        .allSatisfy(r -> assertThat(r.alreadyExists()).isTrue());
  }

  @Test
  void misappliedIsExcluded() {
    NameUsage accepted = usage(1, Status.ACCEPTED, "Poa", "annua", null, "L.", "1753", null, null);
    NameUsage misapplied = usage(9, Status.MISAPPLIED, "Poa", "annua", null, "auct.", null, null, null);
    HomotypyProposal p = detector.detect(accepted, List.of(misapplied), Set.of());
    assertThat(p.groups()).noneSatisfy(g -> assertThat(g.memberUsageIds()).contains(9));
  }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw -Dtest=HomotypyDetectorTest test`
Expected: FAIL to compile (`HomotypyDetector` / DTOs not defined).

- [ ] **Step 3: Write the DTOs**

`dto/ProposedRelation.java`:
```java
package org.catalogueoflife.editor.name.homotypy.dto;

// A single proposed homotypic relation (recombination usageId -> basionym relatedUsageId, or a
// homotypic chain link). `alreadyExists` is true when a matching name_relation is already stored.
public record ProposedRelation(int usageId, int relatedUsageId, String type, boolean alreadyExists) {}
```

`dto/ProposedGroup.java`:
```java
package org.catalogueoflife.editor.name.homotypy.dto;

import java.util.List;

// One detected homotypic group. basionymUsageId is null when no basionym was discernible (members
// are then chained as `homotypic`). memberUsageIds includes every usage in the group (basionym +
// recombinations). relations is empty for a singleton group.
public record ProposedGroup(Integer basionymUsageId, List<Integer> memberUsageIds,
    List<ProposedRelation> relations) {}
```

`dto/HomotypyProposal.java`:
```java
package org.catalogueoflife.editor.name.homotypy.dto;

import java.util.List;

public record HomotypyProposal(List<ProposedGroup> groups) {}
```

- [ ] **Step 4: Write `HomotypicRelations`**

`HomotypicRelations.java`:
```java
package org.catalogueoflife.editor.name.homotypy;

import java.util.Set;

// The ColDP NomRelType values that denote homotypy (objective/nomenclatural synonymy), in the
// canonical lowercase UI form. Stored name_relation.type is not canonical (import writes the raw
// ColDP cell), so normalize() lowercases and maps _/- to spaces before any comparison.
public final class HomotypicRelations {
  private HomotypicRelations() {}

  public static final Set<String> TYPES = Set.of(
      "basionym", "homotypic", "spelling correction", "based on", "replacement name", "superfluous");

  public static String normalize(String type) {
    return type == null ? "" : type.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[_-]", " ");
  }

  public static boolean isHomotypic(String type) {
    return TYPES.contains(normalize(type));
  }
}
```

- [ ] **Step 5: Write `HomotypyDetector`**

`HomotypyDetector.java`:
```java
package org.catalogueoflife.editor.name.homotypy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import life.catalogue.common.tax.AuthorshipNormalizer;
import life.catalogue.common.tax.SciNameNormalizer;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.name.Status;
import org.catalogueoflife.editor.name.homotypy.dto.HomotypyProposal;
import org.catalogueoflife.editor.name.homotypy.dto.ProposedGroup;
import org.catalogueoflife.editor.name.homotypy.dto.ProposedRelation;
import org.springframework.stereotype.Component;

// Compact BasionymSorter-lite. Buckets the accepted usage + its (non-misapplied) synonyms by
// normalized terminal epithet, then clusters within a bucket by basionym-or-combination author team
// (+ compatible year). A name WITHOUT parenthetical basionym authorship is the bucket's basionym;
// names WITH it are recombinations. Pure/stateless: no DB, no name parser (reads the parsed fields
// already stored on each usage). See docs/superpowers/specs/2026-07-20-homotypic-grouping-design.md.
@Component
public class HomotypyDetector {

  private static final class Cluster {
    final String epithetKey;
    final String authorKey;
    String year; // first non-null year seen (back-fills the group's reference year)
    final List<NameUsage> members = new ArrayList<>();
    Cluster(String epithetKey, String authorKey, String year) {
      this.epithetKey = epithetKey; this.authorKey = authorKey; this.year = year;
    }
  }

  public HomotypyProposal detect(NameUsage accepted, List<NameUsage> synonyms, Set<String> existingKeys) {
    List<NameUsage> candidates = new ArrayList<>();
    candidates.add(accepted); // accepted first so its group is anchored on it
    for (NameUsage s : synonyms) {
      if (s.getStatus() != Status.MISAPPLIED) candidates.add(s);
    }

    List<Cluster> clusters = new ArrayList<>();
    for (NameUsage u : candidates) {
      String ek = epithetKey(u);
      String ak = authorKey(u);
      String yr = yearOf(u);
      Cluster match = null;
      if (!ak.isBlank()) {
        for (Cluster c : clusters) {
          if (c.epithetKey.equals(ek) && c.authorKey.equals(ak) && yearCompatible(c.year, yr)) {
            match = c; break;
          }
        }
      }
      if (match == null) {
        clusters.add(new Cluster(ek, ak, yr));
        clusters.get(clusters.size() - 1).members.add(u);
      } else {
        match.members.add(u);
        if (match.year == null) match.year = yr;
      }
    }

    List<ProposedGroup> groups = new ArrayList<>();
    for (Cluster c : clusters) {
      NameUsage basionym = null;
      for (NameUsage m : c.members) {
        if (isBlank(m.getBasionymAuthorship())) { basionym = m; break; }
      }
      List<Integer> memberIds = c.members.stream().map(NameUsage::getId).toList();
      List<ProposedRelation> relations = new ArrayList<>();
      if (basionym != null) {
        for (NameUsage m : c.members) {
          if (m == basionym) continue;
          relations.add(rel(m.getId(), basionym.getId(), "basionym", existingKeys));
        }
      } else if (c.members.size() > 1) {
        NameUsage head = c.members.get(0);
        for (int i = 1; i < c.members.size(); i++) {
          relations.add(rel(c.members.get(i).getId(), head.getId(), "homotypic", existingKeys));
        }
      }
      groups.add(new ProposedGroup(basionym == null ? null : basionym.getId(), memberIds, relations));
    }
    return new HomotypyProposal(groups);
  }

  private static ProposedRelation rel(int usageId, int relatedId, String type, Set<String> existing) {
    boolean exists = existing.contains(usageId + ":" + relatedId + ":" + HomotypicRelations.normalize(type));
    return new ProposedRelation(usageId, relatedId, type, exists);
  }

  private static String epithetKey(NameUsage u) {
    String terminal = notBlank(u.getInfraspecificEpithet()) ? u.getInfraspecificEpithet()
        : notBlank(u.getSpecificEpithet()) ? u.getSpecificEpithet()
        : u.getUninomial();
    if (isBlank(terminal)) return "";
    String norm = SciNameNormalizer.normalizeEpithet(terminal);
    return norm == null ? "" : norm.toLowerCase(Locale.ROOT);
  }

  // Basionym-or-combination author: the basionym (in-parentheses) author if the name has one, else
  // the combination author. Normalized + alias-resolved so spelling variants of one author match.
  private static String authorKey(NameUsage u) {
    String team = notBlank(u.getBasionymAuthorship()) ? u.getBasionymAuthorship() : u.getCombinationAuthorship();
    if (isBlank(team)) return "";
    String normalized = AuthorshipNormalizer.normalize(team);
    if (normalized == null || normalized.isBlank()) return "";
    String looked = AuthorshipNormalizer.INSTANCE.lookup(normalized);
    return (looked == null || looked.isBlank() ? normalized : looked).toLowerCase(Locale.ROOT);
  }

  private static String yearOf(NameUsage u) {
    return notBlank(u.getBasionymAuthorship()) ? u.getBasionymAuthorshipYear() : u.getCombinationAuthorshipYear();
  }

  private static boolean yearCompatible(String a, String b) {
    return a == null || a.isBlank() || b == null || b.isBlank() || a.equals(b);
  }

  private static boolean isBlank(String s) { return s == null || s.isBlank(); }
  private static boolean notBlank(String s) { return !isBlank(s); }
}
```

- [ ] **Step 6: Run the detector test (GREEN)**

Run: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw -Dtest=HomotypyDetectorTest test`
Expected: PASS (5 tests). If `AuthorshipNormalizer.normalize`/`lookup` behaves unexpectedly for the fixtures ("L." vs "l."), the assertions still hold because both sides go through the same `authorKey`.

- [ ] **Step 7: Commit**

```bash
cd /Users/markus/code/col/coldp-editor
git add backend/src/main/java/org/catalogueoflife/editor/name/homotypy backend/src/test/java/org/catalogueoflife/editor/name/homotypy
git commit -m "$(cat <<'EOF'
feat(homotypy): pure BasionymSorter-lite detector + proposal DTOs

Buckets an accepted taxon's names by normalized terminal epithet (SciNameNormalizer)
and clusters by basionym-or-combination author team (AuthorshipNormalizer) + year to
propose basionym-anchored homotypic groups. Pure/stateless, works off already-parsed
authorship fields; curator confirms before anything is persisted.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_0187Tf9weEuVzi834TWXv5pi
EOF
)"
```

---

## Task 3: Synonymy closure mapper + service + controller + API IT

**Files:**
- Create: `backend/src/main/java/org/catalogueoflife/editor/name/homotypy/SynonymyMapper.java`, `HomotypyService.java`, `HomotypyController.java`, `dto/{ApplyHomotypicRequest,SynonymEntry,Synonymy}.java`
- Test: `backend/src/test/java/org/catalogueoflife/editor/name/homotypy/HomotypyApiIT.java`

**Interfaces:**
- Produces (REST, under `/api/projects/{pid}/usages/{id}`): `GET /homotypic/detect -> HomotypyProposal`; `POST /homotypic/apply` (body `ApplyHomotypicRequest`) `-> Synonymy`; `GET /synonymy -> Synonymy`.
- `Synonymy(List<SynonymEntry> homotypic, List<List<SynonymEntry>> heterotypicGroups, List<SynonymEntry> misapplied)`; `SynonymEntry(int id, String scientificName, String authorship, String rank, String status, String formattedName)`; `ApplyHomotypicRequest(List<ApplyRelation> relations)` with nested `ApplyRelation(int usageId, int relatedUsageId, String type)`.
- Consumes: `HomotypyDetector.detect(...)`; `projects.requireRole/requireEditor/requireVisible`; `synonymAccepted.findSynonymsOf(pid,id)`; `usages.findByIdInProject(pid,id)`; `nameRelations.findByUsage/insert/exists`; `parser.formatName(NameUsage, NomCode, false)`; `idSeq.allocate(pid, "name_relation")`.

- [ ] **Step 1: Write the failing API IT**

Create `HomotypyApiIT.java`. It seeds an accepted `Poa annua L.` + a recombination synonym `Ochlopoa annua (L.) H.Scholz` + a heterotypic `Aira pumila Pursh`, then exercises detect/apply/synonymy. Use the same seeding helpers other name ITs use (create usages via `POST /api/projects/{pid}/usages`, link synonyms via `PUT .../{id}/synonym-of/{acceptedId}`). Skeleton:

```java
package org.catalogueoflife.editor.name.homotypy;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.catalogueoflife.editor.support.AbstractPostgresIT;
// ... (autowire MockMvc, AppUserService, a project + membership helper as in NameRelationIT)

@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
class HomotypyApiIT extends AbstractPostgresIT {
  // helpers: create project owned by "owner"; add "viewer" as viewer; create usages; link synonym.

  // 1. detect returns a group with a basionym relation (recomb -> accepted), alreadyExists=false.
  // 2. apply persists that relation; a second apply of the same relation is idempotent (no dup).
  // 3. synonymy returns the recomb under `homotypic` and Aira pumila under `heterotypicGroups`.
  // 4. authz: viewer may GET detect + synonymy (200) but POST apply is 403; non-member is 404.
}
```

Fill in concrete requests, e.g. detect:
```java
    mvc.perform(get("/api/projects/" + pid + "/usages/" + acceptedId + "/homotypic/detect").with(user("owner")))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.groups[?(@.basionymUsageId == " + acceptedId + ")]").exists());
```
apply:
```java
    String body = "{\"relations\":[{\"usageId\":" + recombId + ",\"relatedUsageId\":" + acceptedId + ",\"type\":\"basionym\"}]}";
    mvc.perform(post("/api/projects/" + pid + "/usages/" + acceptedId + "/homotypic/apply").with(user("owner")).with(csrf())
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.homotypic[?(@.id == " + recombId + ")]").exists());
    // second apply -> still OK, still exactly one homotypic entry for recombId (idempotent)
```
authz:
```java
    mvc.perform(post("/api/projects/" + pid + "/usages/" + acceptedId + "/homotypic/apply").with(user("viewer")).with(csrf())
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body))
       .andExpect(status().isForbidden());
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=HomotypyApiIT -DfailIfNoTests=false verify`
Expected: FAIL to compile (controller/service/DTOs missing).

- [ ] **Step 3: Write the synonymy DTOs**

`dto/SynonymEntry.java`:
```java
package org.catalogueoflife.editor.name.homotypy.dto;

// One display row in a synonymy. formattedName is the parser-rendered (italic-markup-free) name;
// the UI decides ≡/= from the entry's position (homotypic list vs a heterotypic group's basionym).
public record SynonymEntry(int id, String scientificName, String authorship, String rank,
    String status, String formattedName) {}
```

`dto/Synonymy.java`:
```java
package org.catalogueoflife.editor.name.homotypy.dto;

import java.util.List;

// Nested synonymy of an accepted usage: recombinations homotypic to the accepted name, then each
// heterotypic group (basionym first, its recombinations after), then misapplied names.
public record Synonymy(List<SynonymEntry> homotypic, List<List<SynonymEntry>> heterotypicGroups,
    List<SynonymEntry> misapplied) {}
```

`dto/ApplyHomotypicRequest.java`:
```java
package org.catalogueoflife.editor.name.homotypy.dto;

import java.util.List;

public record ApplyHomotypicRequest(List<ApplyRelation> relations) {
  public record ApplyRelation(int usageId, int relatedUsageId, String type) {}
}
```

- [ ] **Step 4: Write `SynonymyMapper` (recursive-CTE closure)**

`SynonymyMapper.java`:
```java
package org.catalogueoflife.editor.name.homotypy;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SynonymyMapper {

  // Every usage transitively homotypically related to #{usageId} via name_relation edges of a
  // homotypic type (walked in BOTH directions, type normalized case/_/-). Depth-guarded; excludes
  // the seed usage itself. Blixa datasets are small so depth 100 is ample.
  @Select("""
      WITH RECURSIVE closure(uid, depth) AS (
        SELECT #{usageId}, 0
        UNION
        SELECT CASE WHEN nr.usage_id = c.uid THEN nr.related_usage_id ELSE nr.usage_id END, c.depth + 1
        FROM closure c
        JOIN name_relation nr ON nr.project_id = #{projectId}
          AND lower(regexp_replace(nr.type, '[_-]', ' ', 'g'))
              IN ('basionym','homotypic','spelling correction','based on','replacement name','superfluous')
          AND (nr.usage_id = c.uid OR nr.related_usage_id = c.uid)
        WHERE c.depth < 100
      )
      SELECT DISTINCT uid FROM closure WHERE uid IS NOT NULL AND uid <> #{usageId}
      """)
  List<Integer> homotypicClosure(@Param("projectId") int projectId, @Param("usageId") int usageId);
}
```

- [ ] **Step 5: Write `HomotypyService`**

`HomotypyService.java`:
```java
package org.catalogueoflife.editor.name.homotypy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.catalogueoflife.editor.child.NameRelationMapper;
import org.catalogueoflife.editor.child.dto.NameRelationRequest;
import org.catalogueoflife.editor.child.dto.NameRelationResponse;
import org.catalogueoflife.editor.name.IdSeqMapper;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.name.NameUsageMapper;
import org.catalogueoflife.editor.name.Status;
import org.catalogueoflife.editor.name.SynonymAcceptedMapper;
import org.catalogueoflife.editor.name.homotypy.dto.ApplyHomotypicRequest;
import org.catalogueoflife.editor.name.homotypy.dto.HomotypyProposal;
import org.catalogueoflife.editor.name.homotypy.dto.SynonymEntry;
import org.catalogueoflife.editor.name.homotypy.dto.Synonymy;
import org.catalogueoflife.editor.parse.NameParserService;
import org.catalogueoflife.editor.project.Project;
import org.catalogueoflife.editor.project.ProjectService;
import org.catalogueoflife.editor.project.Role;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class HomotypyService {

  private static final String ENTITY = "name_relation";

  private final NameUsageMapper usages;
  private final SynonymAcceptedMapper synonymAccepted;
  private final NameRelationMapper nameRelations;
  private final SynonymyMapper synonymyMapper;
  private final HomotypyDetector detector;
  private final IdSeqMapper idSeq;
  private final ProjectService projects;
  private final NameParserService parser;

  public HomotypyService(NameUsageMapper usages, SynonymAcceptedMapper synonymAccepted,
      NameRelationMapper nameRelations, SynonymyMapper synonymyMapper, HomotypyDetector detector,
      IdSeqMapper idSeq, ProjectService projects, NameParserService parser) {
    this.usages = usages;
    this.synonymAccepted = synonymAccepted;
    this.nameRelations = nameRelations;
    this.synonymyMapper = synonymyMapper;
    this.detector = detector;
    this.idSeq = idSeq;
    this.projects = projects;
    this.parser = parser;
  }

  public HomotypyProposal detect(int userId, int projectId, int acceptedId) {
    projects.requireRole(userId, projectId);
    NameUsage accepted = requireUsage(projectId, acceptedId);
    List<NameUsage> synonyms = loadSynonyms(projectId, acceptedId);
    Set<String> existing = existingRelationKeys(projectId, accepted, synonyms);
    return detector.detect(accepted, synonyms, existing);
  }

  @Transactional
  public Synonymy apply(int userId, int projectId, int acceptedId, ApplyHomotypicRequest req) {
    requireEditor(userId, projectId);
    requireUsage(projectId, acceptedId);
    if (req != null && req.relations() != null) {
      for (ApplyHomotypicRequest.ApplyRelation rel : req.relations()) {
        if (nameRelations.exists(projectId, rel.usageId(), rel.relatedUsageId(), rel.type())) continue;
        int id = idSeq.allocate(projectId, ENTITY);
        nameRelations.insert(projectId, id, rel.usageId(),
            new NameRelationRequest(rel.relatedUsageId(), rel.type(), null, null, null, null), userId);
      }
    }
    return synonymy(userId, projectId, acceptedId);
  }

  public Synonymy synonymy(int userId, int projectId, int acceptedId) {
    Project project = projects.requireVisible(userId, projectId);
    NameUsage accepted = requireUsage(projectId, acceptedId);
    List<NameUsage> synonyms = loadSynonyms(projectId, acceptedId);

    List<SynonymEntry> misapplied = new ArrayList<>();
    List<NameUsage> nonMisapplied = new ArrayList<>();
    for (NameUsage s : synonyms) {
      if (s.getStatus() == Status.MISAPPLIED) misapplied.add(entry(s, project)); else nonMisapplied.add(s);
    }

    Set<Integer> acceptedClosure = new HashSet<>(synonymyMapper.homotypicClosure(projectId, acceptedId));
    List<SynonymEntry> homotypic = new ArrayList<>();
    List<NameUsage> remaining = new ArrayList<>();
    for (NameUsage s : nonMisapplied) {
      if (acceptedClosure.contains(s.getId())) homotypic.add(entry(s, project)); else remaining.add(s);
    }
    homotypic.sort(basionymFirst());

    List<List<SynonymEntry>> heterotypicGroups = new ArrayList<>();
    Set<Integer> placed = new HashSet<>();
    for (NameUsage s : remaining) {
      if (placed.contains(s.getId())) continue;
      Set<Integer> group = new LinkedHashSet<>();
      group.add(s.getId());
      group.addAll(synonymyMapper.homotypicClosure(projectId, s.getId()));
      List<SynonymEntry> members = new ArrayList<>();
      for (NameUsage r : remaining) {
        if (group.contains(r.getId()) && placed.add(r.getId())) members.add(entry(r, project));
      }
      members.sort(basionymFirst());
      heterotypicGroups.add(members);
    }
    return new Synonymy(homotypic, heterotypicGroups, misapplied);
  }

  // A recombination has parenthetical basionym authorship; the basionym does not -> basionym sorts
  // first, then by name.
  private static java.util.Comparator<SynonymEntry> basionymFirst() {
    return java.util.Comparator.comparing((SynonymEntry e) -> e.scientificName() == null ? "" : e.scientificName());
  }

  private List<NameUsage> loadSynonyms(int projectId, int acceptedId) {
    List<NameUsage> out = new ArrayList<>();
    for (Integer sid : synonymAccepted.findSynonymsOf(projectId, acceptedId)) {
      NameUsage u = usages.findByIdInProject(projectId, sid);
      if (u != null) out.add(u);
    }
    return out;
  }

  private Set<String> existingRelationKeys(int projectId, NameUsage accepted, List<NameUsage> synonyms) {
    Set<String> keys = new HashSet<>();
    List<Integer> ids = new ArrayList<>();
    ids.add(accepted.getId());
    synonyms.forEach(s -> ids.add(s.getId()));
    for (Integer id : ids) {
      for (NameRelationResponse r : nameRelations.findByUsage(projectId, id)) {
        if (r.relatedUsageId() != null) {
          keys.add(r.usageId() + ":" + r.relatedUsageId() + ":" + HomotypicRelations.normalize(r.type()));
        }
      }
    }
    return keys;
  }

  private SynonymEntry entry(NameUsage u, Project project) {
    String formatted = parser.formatName(u, project.getNomCode(), false);
    return new SynonymEntry(u.getId(), u.getScientificName(), u.getAuthorship(), u.getRank(),
        u.getStatus() == null ? null : u.getStatus().name(), formatted);
  }

  private NameUsage requireUsage(int projectId, int id) {
    NameUsage u = usages.findByIdInProject(projectId, id);
    if (u == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "name usage not found");
    return u;
  }

  private void requireEditor(int userId, int projectId) {
    String role = projects.requireRole(userId, projectId);
    if (!role.equals(Role.OWNER.dbValue()) && !role.equals(Role.EDITOR.dbValue())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "owner or editor required");
    }
  }
}
```

Note: `Project.getNomCode()` already returns `org.gbif.nameparser.api.NomCode`, which is exactly what `NameParserService.formatName(NameUsage, NomCode, boolean)` takes — so `parser.formatName(u, project.getNomCode(), false)` is correct as written (this mirrors `NameUsageService.toResponse` at ~line 707). No conversion needed.

- [ ] **Step 6: Write `HomotypyController`**

`HomotypyController.java`:
```java
package org.catalogueoflife.editor.name.homotypy;

import org.catalogueoflife.editor.auth.CurrentUser;
import org.catalogueoflife.editor.name.homotypy.dto.ApplyHomotypicRequest;
import org.catalogueoflife.editor.name.homotypy.dto.HomotypyProposal;
import org.catalogueoflife.editor.name.homotypy.dto.Synonymy;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{pid}/usages/{id}")
public class HomotypyController {

  private final HomotypyService service;
  private final CurrentUser currentUser;

  public HomotypyController(HomotypyService service, CurrentUser currentUser) {
    this.service = service;
    this.currentUser = currentUser;
  }

  @GetMapping("/homotypic/detect")
  public HomotypyProposal detect(@PathVariable int pid, @PathVariable int id) {
    return service.detect(currentUser.require().getId(), pid, id);
  }

  @PostMapping("/homotypic/apply")
  public Synonymy apply(@PathVariable int pid, @PathVariable int id, @RequestBody ApplyHomotypicRequest req) {
    return service.apply(currentUser.require().getId(), pid, id, req);
  }

  @GetMapping("/synonymy")
  public Synonymy synonymy(@PathVariable int pid, @PathVariable int id) {
    return service.synonymy(currentUser.require().getId(), pid, id);
  }
}
```

- [ ] **Step 7: Run the API IT (GREEN)**

Run: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=HomotypyApiIT -DfailIfNoTests=false verify`
Expected: PASS. Debug the closure SQL / seeding until green.

- [ ] **Step 8: Commit**

```bash
cd /Users/markus/code/col/coldp-editor
git add backend/src/main/java/org/catalogueoflife/editor/name/homotypy backend/src/test/java/org/catalogueoflife/editor/name/homotypy
git commit -m "$(cat <<'EOF'
feat(homotypy): detect/apply/synonymy API + recursive-CTE closure

GET /usages/{id}/homotypic/detect previews basionym-anchored groups (with
already-existing relations flagged); POST /homotypic/apply persists confirmed
name_relation rows (idempotent, owner/editor); GET /usages/{id}/synonymy returns the
nested read model (accepted's homotypic recombinations, heterotypic groups, misapplied)
via a depth-guarded homotypic closure over name_relation.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_0187Tf9weEuVzi834TWXv5pi
EOF
)"
```

---

## Task 4: Frontend API + nested synonymy view

**Files:**
- Modify: `frontend/src/api/usages.ts`, `frontend/src/tree/TaxonDetail.tsx`
- Create: `frontend/src/tree/Synonymy.tsx`, `frontend/src/tree/Synonymy.test.tsx`

**Interfaces:**
- Produces: `getSynonymy(pid, id) -> Promise<Synonymy>`, `detectHomotypic(pid, id) -> Promise<HomotypyProposal>`, `applyHomotypic(pid, id, relations) -> Promise<Synonymy>`; types `Synonymy`, `SynEntry`, `HomotypyProposal`, `ProposedGroup`, `ProposedRelation`, `ApplyRelation`.
- Consumes: `api<T>(path, opts)` from `./client`.

- [ ] **Step 1: Add API functions + types to `api/usages.ts`**

Append to `frontend/src/api/usages.ts`:

```typescript
// --- Homotypic grouping (see backend name/homotypy) ---
export interface SynEntry {
  id: number;
  scientificName: string | null;
  authorship: string | null;
  rank: string | null;
  status: string | null;
  formattedName: string | null;
}
export interface Synonymy {
  homotypic: SynEntry[];
  heterotypicGroups: SynEntry[][];
  misapplied: SynEntry[];
}
export interface ProposedRelation {
  usageId: number;
  relatedUsageId: number;
  type: string;
  alreadyExists: boolean;
}
export interface ProposedGroup {
  basionymUsageId: number | null;
  memberUsageIds: number[];
  relations: ProposedRelation[];
}
export interface HomotypyProposal {
  groups: ProposedGroup[];
}
export interface ApplyRelation {
  usageId: number;
  relatedUsageId: number;
  type: string;
}

export function getSynonymy(pid: number, id: number): Promise<Synonymy> {
  return api<Synonymy>(`/api/projects/${pid}/usages/${id}/synonymy`);
}
export function detectHomotypic(pid: number, id: number): Promise<HomotypyProposal> {
  return api<HomotypyProposal>(`/api/projects/${pid}/usages/${id}/homotypic/detect`);
}
export function applyHomotypic(pid: number, id: number, relations: ApplyRelation[]): Promise<Synonymy> {
  return api<Synonymy>(`/api/projects/${pid}/usages/${id}/homotypic/apply`, {
    method: 'POST',
    json: { relations },
  });
}
```

- [ ] **Step 2: Write the failing `Synonymy.test.tsx`**

Create `frontend/src/tree/Synonymy.test.tsx`:

```tsx
import { describe, it, expect, beforeEach } from 'vitest';
import { screen } from '@testing-library/react';
import { render } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import Synonymy from './Synonymy';

const PAYLOAD = {
  homotypic: [
    { id: 2, scientificName: 'Ochlopoa annua', authorship: '(L.) H.Scholz', rank: 'species', status: 'SYNONYM', formattedName: 'Ochlopoa annua (L.) H.Scholz' },
  ],
  heterotypicGroups: [
    [
      { id: 3, scientificName: 'Aira pumila', authorship: 'Pursh', rank: 'species', status: 'SYNONYM', formattedName: 'Aira pumila Pursh' },
      { id: 4, scientificName: 'Catabrosa pumila', authorship: '(Pursh) Roem. & Schult.', rank: 'species', status: 'SYNONYM', formattedName: 'Catabrosa pumila (Pursh) Roem. & Schult.' },
    ],
  ],
  misapplied: [
    { id: 9, scientificName: 'Poa annua', authorship: 'auct.', rank: 'species', status: 'MISAPPLIED', formattedName: 'Poa annua auct.' },
  ],
};

describe('Synonymy', () => {
  beforeEach(() => {
    server.use(http.get('/api/projects/1/usages/1/synonymy', () => HttpResponse.json(PAYLOAD)));
  });

  it('renders homotypic recombinations, heterotypic groups nested, and misapplied', async () => {
    render(<Synonymy pid={1} usageId={1} canEdit={false} />);
    expect(await screen.findByText(/Ochlopoa annua/)).toBeInTheDocument();
    expect(screen.getByText(/Aira pumila/)).toBeInTheDocument();
    expect(screen.getByText(/Catabrosa pumila/)).toBeInTheDocument();
    expect(screen.getByText(/Poa annua auct\./)).toBeInTheDocument();
  });

  it('shows the Group synonyms button only when editable', async () => {
    const { rerender } = render(<Synonymy pid={1} usageId={1} canEdit={false} />);
    await screen.findByText(/Ochlopoa annua/);
    expect(screen.queryByRole('button', { name: /group synonyms/i })).not.toBeInTheDocument();
    rerender(<Synonymy pid={1} usageId={1} canEdit />);
    expect(await screen.findByRole('button', { name: /group synonyms/i })).toBeInTheDocument();
  });
});
```

- [ ] **Step 3: Run it to verify it fails**

Run: `cd frontend && npx vitest run src/tree/Synonymy.test.tsx`
Expected: FAIL — `./Synonymy` cannot be resolved.

- [ ] **Step 4: Write `Synonymy.tsx`**

Create `frontend/src/tree/Synonymy.tsx`:

```tsx
import { Box, Button, Group, List, Stack, Text } from '@mantine/core';
import { useDisclosure } from '@mantine/hooks';
import { useQuery } from '@tanstack/react-query';
import { getSynonymy, type SynEntry } from '../api/usages';
import HomotypicGroupModal from './HomotypicGroupModal';

function EntryLine({ e, marker }: { e: SynEntry; marker: '≡' | '=' }) {
  return (
    <Group gap={6} wrap="nowrap" align="baseline">
      <Text span c="dimmed" w={12} ta="center">{marker}</Text>
      <span>
        {e.formattedName ?? e.scientificName}
        {!e.formattedName && e.authorship ? (
          <Text span c="dimmed" size="xs"> {e.authorship}</Text>
        ) : null}
      </span>
    </Group>
  );
}

export interface SynonymyProps {
  pid: number;
  usageId: number;
  canEdit?: boolean;
}

// Nested synonymy of an ACCEPTED usage (see backend name/homotypy). Recombinations homotypic to the
// accepted name render first with ≡; each heterotypic group renders its basionym with = and its
// recombinations indented with ≡; misapplied names come last. `Group synonyms` (editor) opens the
// detect/confirm modal.
export default function Synonymy({ pid, usageId, canEdit = false }: SynonymyProps) {
  const { data, isLoading } = useQuery({
    queryKey: ['synonymy', pid, usageId],
    queryFn: () => getSynonymy(pid, usageId),
  });
  const [opened, { open, close }] = useDisclosure(false);

  if (isLoading) return <Text size="sm" c="dimmed">Loading…</Text>;
  const s = data;
  const empty =
    !s || (s.homotypic.length === 0 && s.heterotypicGroups.length === 0 && s.misapplied.length === 0);

  return (
    <Stack gap="sm">
      {canEdit && (
        <Group justify="flex-end">
          <Button size="xs" variant="light" onClick={open}>Group synonyms</Button>
        </Group>
      )}
      {empty && <Text size="sm" c="dimmed">No synonyms</Text>}
      {s && s.homotypic.length > 0 && (
        <List listStyleType="none" spacing={2}>
          {s.homotypic.map((e) => (
            <List.Item key={e.id}><EntryLine e={e} marker="≡" /></List.Item>
          ))}
        </List>
      )}
      {s &&
        s.heterotypicGroups.map((grp, i) => (
          <List listStyleType="none" spacing={2} key={grp[0]?.id ?? i}>
            {grp.map((e, idx) => (
              <List.Item key={e.id}>
                <Box pl={idx === 0 ? 0 : 'md'}>
                  <EntryLine e={e} marker={idx === 0 ? '=' : '≡'} />
                </Box>
              </List.Item>
            ))}
          </List>
        ))}
      {s && s.misapplied.length > 0 && (
        <List listStyleType="none" spacing={2}>
          {s.misapplied.map((e) => (
            <List.Item key={e.id}><EntryLine e={e} marker="=" /></List.Item>
          ))}
        </List>
      )}
      {opened && (
        <HomotypicGroupModal pid={pid} usageId={usageId} onClose={close} />
      )}
    </Stack>
  );
}
```

Note: this imports `HomotypicGroupModal` (Task 5). To keep Task 4 independently green, create a minimal placeholder now and flesh it out in Task 5:

```tsx
// frontend/src/tree/HomotypicGroupModal.tsx (placeholder — replaced in Task 5)
export default function HomotypicGroupModal(_: { pid: number; usageId: number; onClose: () => void }) {
  return null;
}
```

- [ ] **Step 5: Run the Synonymy test (GREEN)**

Run: `cd frontend && npx vitest run src/tree/Synonymy.test.tsx`
Expected: PASS (2 tests).

- [ ] **Step 6: Wire `Synonymy` into `TaxonDetail` for accepted usages**

In `frontend/src/tree/TaxonDetail.tsx`: add `import Synonymy from './Synonymy';` next to the `SynonymList` import (line 45). Replace the synonyms panel body (line 544) so accepted usages get the nested view and synonym/misapplied usages keep the accepted-target list:

```tsx
          {(usage.status ?? '').toUpperCase() === 'ACCEPTED' ? (
            <Synonymy pid={pid} usageId={usageId} canEdit={canEdit} />
          ) : (
            <SynonymList pid={pid} usageId={usageId} status={usage.status} canEdit={canEdit} />
          )}
```

- [ ] **Step 7: Add a default synonymy handler to the shared msw server**

In `frontend/src/test/server.ts`, add a default so existing `TaxonDetail`/`TreePage` tests that render an accepted usage don't hit an unhandled request:

```typescript
  http.get('/api/projects/:pid/usages/:id/synonymy', () =>
    HttpResponse.json({ homotypic: [], heterotypicGroups: [], misapplied: [] })),
```

- [ ] **Step 8: Typecheck + run the tree suite**

Run: `cd frontend && npx tsc -b && npx vitest run src/tree`
Expected: PASS (Synonymy tests + existing TaxonDetail/TreePage tests green).

- [ ] **Step 9: Commit**

```bash
cd /Users/markus/code/col/coldp-editor
git add frontend/src/api/usages.ts frontend/src/tree/Synonymy.tsx frontend/src/tree/Synonymy.test.tsx frontend/src/tree/HomotypicGroupModal.tsx frontend/src/tree/TaxonDetail.tsx frontend/src/test/server.ts
git commit -m "$(cat <<'EOF'
feat(synonymy): nested homotypic synonymy view on accepted taxa

Synonymy.tsx renders an accepted usage's synonyms nested like the COL portal —
homotypic recombinations (≡) first, each heterotypic group's basionym (=) with its
recombinations indented (≡), misapplied last — off GET /usages/{id}/synonymy.
TaxonDetail uses it for accepted usages; synonym/misapplied usages keep the flat list.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_0187Tf9weEuVzi834TWXv5pi
EOF
)"
```

---

## Task 5: Detect-and-confirm modal

**Files:**
- Modify: `frontend/src/tree/HomotypicGroupModal.tsx` (replace the placeholder)
- Create: `frontend/src/tree/HomotypicGroupModal.test.tsx`

**Interfaces:**
- Consumes: `detectHomotypic`, `applyHomotypic`, `getUsage` (for member labels), types from `api/usages.ts`; `useQueryClient` to invalidate `['synonymy', pid, usageId]`.

- [ ] **Step 1: Write the failing modal test**

Create `frontend/src/tree/HomotypicGroupModal.test.tsx`:

```tsx
import { describe, it, expect, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { render } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import HomotypicGroupModal from './HomotypicGroupModal';

const PROPOSAL = {
  groups: [
    {
      basionymUsageId: 1,
      memberUsageIds: [1, 2],
      relations: [{ usageId: 2, relatedUsageId: 1, type: 'basionym', alreadyExists: false }],
    },
  ],
};

describe('HomotypicGroupModal', () => {
  beforeEach(() => {
    server.use(
      http.get('/api/projects/1/usages/1/homotypic/detect', () => HttpResponse.json(PROPOSAL)),
      http.get('/api/projects/1/usages/:id', ({ params }) =>
        HttpResponse.json({ id: Number(params.id), scientificName: `Name ${params.id}`, authorship: null, version: 0 }),
      ),
    );
  });

  it('shows detected relations and applies the checked ones', async () => {
    let posted: unknown = null;
    server.use(
      http.post('/api/projects/1/usages/1/homotypic/apply', async ({ request }) => {
        posted = await request.json();
        return HttpResponse.json({ homotypic: [], heterotypicGroups: [], misapplied: [] });
      }),
    );
    render(<HomotypicGroupModal pid={1} usageId={1} onClose={() => {}} />);
    expect(await screen.findByText(/basionym/i)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /apply/i }));
    await waitFor(() =>
      expect(posted).toEqual({ relations: [{ usageId: 2, relatedUsageId: 1, type: 'basionym' }] }),
    );
  });
});
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd frontend && npx vitest run src/tree/HomotypicGroupModal.test.tsx`
Expected: FAIL — placeholder renders null, no "basionym" text / Apply button.

- [ ] **Step 3: Implement `HomotypicGroupModal.tsx`**

Replace `frontend/src/tree/HomotypicGroupModal.tsx`:

```tsx
import { Button, Checkbox, Group, Loader, Modal, Stack, Text } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { messageFor } from '../api/client';
import {
  applyHomotypic,
  detectHomotypic,
  getUsage,
  type ApplyRelation,
  type ProposedRelation,
} from '../api/usages';

export interface HomotypicGroupModalProps {
  pid: number;
  usageId: number;
  onClose: () => void;
}

function relKey(r: ProposedRelation) {
  return `${r.usageId}:${r.relatedUsageId}:${r.type}`;
}

// Detect-and-confirm: previews proposed homotypic relations for the accepted taxon, lets the curator
// uncheck any, and applies the checked (new) ones as name_relation rows.
export default function HomotypicGroupModal({ pid, usageId, onClose }: HomotypicGroupModalProps) {
  const qc = useQueryClient();
  const { data: proposal, isLoading } = useQuery({
    queryKey: ['homotypicDetect', pid, usageId],
    queryFn: () => detectHomotypic(pid, usageId),
  });

  const relations: ProposedRelation[] = (proposal?.groups ?? []).flatMap((g) => g.relations);
  const memberIds = Array.from(
    new Set((proposal?.groups ?? []).flatMap((g) => g.memberUsageIds)),
  );
  const nameQueries = useQueries({
    queries: memberIds.map((id) => ({
      queryKey: ['usage', pid, id],
      queryFn: () => getUsage(pid, id),
    })),
  });
  const nameOf = (id: number) => {
    const q = nameQueries[memberIds.indexOf(id)];
    return q?.data?.scientificName ?? `#${id}`;
  };

  // default: every NEW relation checked; already-existing ones shown but unchecked (no-op).
  const [unchecked, setUnchecked] = useState<Set<string>>(new Set());
  const isChecked = (r: ProposedRelation) => !r.alreadyExists && !unchecked.has(relKey(r));
  const toggle = (r: ProposedRelation) =>
    setUnchecked((prev) => {
      const next = new Set(prev);
      const k = relKey(r);
      if (next.has(k)) next.delete(k); else next.add(k);
      return next;
    });

  const apply = useMutation({
    mutationFn: () => {
      const chosen: ApplyRelation[] = relations
        .filter(isChecked)
        .map((r) => ({ usageId: r.usageId, relatedUsageId: r.relatedUsageId, type: r.type }));
      return applyHomotypic(pid, usageId, chosen);
    },
    onSuccess: async () => {
      await qc.invalidateQueries({ queryKey: ['synonymy', pid, usageId] });
      notifications.show({ message: 'Homotypic relations applied' });
      onClose();
    },
    onError: (e) => notifications.show({ color: 'red', message: messageFor(e, 'Could not apply') }),
  });

  return (
    <Modal opened onClose={onClose} title="Group synonyms homotypically" size="lg">
      {isLoading ? (
        <Loader />
      ) : relations.length === 0 ? (
        <Text size="sm" c="dimmed">No homotypic relations detected.</Text>
      ) : (
        <Stack>
          {relations.map((r) => (
            <Checkbox
              key={relKey(r)}
              checked={isChecked(r)}
              disabled={r.alreadyExists}
              onChange={() => toggle(r)}
              label={
                <Text size="sm">
                  {nameOf(r.usageId)} <b>{r.type}</b> {nameOf(r.relatedUsageId)}
                  {r.alreadyExists ? <Text span c="dimmed" size="xs"> (already linked)</Text> : null}
                </Text>
              }
            />
          ))}
          <Group justify="flex-end">
            <Button variant="default" onClick={onClose}>Cancel</Button>
            <Button loading={apply.isPending} onClick={() => apply.mutate()}>Apply</Button>
          </Group>
        </Stack>
      )}
    </Modal>
  );
}
```

- [ ] **Step 4: Run the modal test (GREEN)**

Run: `cd frontend && npx vitest run src/tree/HomotypicGroupModal.test.tsx`
Expected: PASS.

- [ ] **Step 5: Typecheck + full frontend suite + build**

Run: `cd frontend && npx tsc -b && npx vitest run && npm run build`
Expected: PASS (all tests green; build succeeds — the pre-existing chunk-size warning is fine).

- [ ] **Step 6: Commit**

```bash
cd /Users/markus/code/col/coldp-editor
git add frontend/src/tree/HomotypicGroupModal.tsx frontend/src/tree/HomotypicGroupModal.test.tsx
git commit -m "$(cat <<'EOF'
feat(synonymy): detect-and-confirm homotypic grouping modal

Group synonyms opens a modal that previews detected homotypic relations (already-linked
ones shown disabled), lets the curator uncheck any, and applies the chosen new ones as
name_relation rows, refreshing the nested synonymy.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_0187Tf9weEuVzi834TWXv5pi
EOF
)"
```

---

## Task 6: Backlog + spec status

**Files:**
- Modify: `backlog.md`, `docs/superpowers/specs/2026-07-20-homotypic-grouping-design.md`

- [ ] **Step 1: Mark Side 1 shipped in `backlog.md`**

Find the "Homotypic grouping" entry (under Tools & import) and update it:

```markdown
- **Homotypic grouping** — *Side 1 shipped:* in-taxon detection + nested synonymy. Select an accepted
  taxon, auto-detect basionym-anchored homotypic groups among its synonyms (BasionymSorter-lite over
  parsed authorship), confirm, and persist as `name_relation` rows; the synonymy renders nested (≡/=)
  like the COL portal. `name_relation` is now the single source of truth for basionym (the
  `basionym_id` column was dropped; import/export go through the relation). *Side 2 (pending):*
  cross-dataset consolidation of accepted names over the focal taxon's subtree (a family) — pick the
  single survivor per homotypic group, demote the rest (see CLB `HomotypicConsolidator`).
```

- [ ] **Step 2: Note Side 1 complete in the spec**

At the top of `docs/superpowers/specs/2026-07-20-homotypic-grouping-design.md`, under the date line, add: `**Status:** Side 1 implemented 2026-07-20 (plan: docs/superpowers/plans/2026-07-20-homotypic-grouping-side1.md).`

- [ ] **Step 3: Commit**

```bash
cd /Users/markus/code/col/coldp-editor
git add backlog.md docs/superpowers/specs/2026-07-20-homotypic-grouping-design.md
git commit -m "$(cat <<'EOF'
docs: mark homotypic grouping Side 1 shipped

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_0187Tf9weEuVzi834TWXv5pi
EOF
)"
```

---

## Notes for the implementer

- **`NomCode` (Task 3, Step 5):** `Project.getNomCode()` already returns `org.gbif.nameparser.api.NomCode`; pass it straight to `parser.formatName(u, project.getNomCode(), false)` exactly as `NameUsageService.toResponse` does. No conversion.
- **Seeding in `HomotypyApiIT` (Task 3):** copy the project/member/usage-creation helpers from `NameRelationIT.java` or another name IT in the same package style; create the recombination synonym with authorship `(L.) H.Scholz` so the parser fills `basionymAuthorship`. Link it via `PUT /usages/{synId}/synonym-of/{acceptedId}`.
- **Pre-existing environment failures** (do not attribute to this work): `ChangeApiIT` (needs network for CSL), and full-suite "too many clients already" (Postgres connection exhaustion); individual ITs pass in isolation.
- **`messageFor`/`api` options** (frontend): `api(path, { method, json })` and `messageFor(err, fallback)` already exist in `api/client.ts` (used across the app); no new client plumbing needed.
