# Homotypic Grouping — Side 2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a curator scan a focal taxon's accepted subtree for homotypic clusters that resolve to more than one accepted name, and resolve each by picking a survivor while the other accepted names are demoted to homotypic synonyms of it.

**Architecture:** A small refactor exposes the Side-1 `HomotypyDetector`'s clustering as a status-agnostic `group(candidates, existingKeys)`. A new `ConsolidationService` scans a subtree (`findSubtreeIds` → filter → `group` → resolve each member's accepted target(s) via `findAcceptedFor` → keep clusters with >1 distinct accepted target, flagging pro-parte/dual-status), and consolidates a cluster by reusing the existing `NameUsageService.demote()` per loser plus `HomotypyService.apply()` to persist the cluster's homotypic relations. A dedicated paginated React page drives it, launched from a taxon's action menu.

**Tech Stack:** Java 25 / Spring Boot 4.1 / MyBatis / Postgres 17 backend; React + Mantine + React Query + vitest frontend.

## Global Constraints

- Build backend: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw …`.
- One IT: `-Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=X -DfailIfNoTests=false verify`; one unit test: `-Dtest=X test`. Full `./mvnw clean verify` after record-arity changes.
- Frontend: `cd frontend && npx vitest run <path>` / `npx tsc -b` / `npm run build`.
- Commit directly to `main` (no branches). End every commit message with:
  `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>` and
  `Claude-Session: https://claude.ai/code/session_0187Tf9weEuVzi834TWXv5pi`.
- A cluster is a **conflict** iff its members resolve to **>1 distinct accepted name** (accepted member → itself; synonym member → each `findAcceptedFor` id). Clustering includes `ACCEPTED` + `SYNONYM`; excludes `MISAPPLIED`, `UNASSESSED`, supraspecific (blank `specificEpithet`), and autonyms (`infraspecificEpithet` equals `specificEpithet`).
- **pro-parte member** = a `SYNONYM` cluster member with >1 accepted target. **dual-status** = the same `scientificName` present in the cluster both as an `ACCEPTED` and a `SYNONYM` usage. A cluster with any such member has `hasExceptions = true`; these are flagged, never auto-resolved.
- **Suggested survivor** = the accepted candidate with the most descendants; ties broken by oldest combination-authorship year, then formatted name.
- Consolidation reuses `demote()` with `childrenTo = "new-accepted"` and `synonymsTo = "new-accepted"` (children + synonyms → survivor) and persists the cluster's homotypic relations; it is atomic per cluster (a stale loser version → 409, full rollback).
- Pre-existing environment-only test failures (NOT this work): `ChangeApiIT` (needs network/CSL); full-suite "too many clients already" (Postgres connection exhaustion).

---

## File Structure

**Backend — new**
- `name/homotypy/ConsolidationService.java` — scan + consolidate orchestration.
- `name/homotypy/ConsolidationController.java` — the two endpoints.
- `name/homotypy/dto/{ConflictCluster,AcceptedCandidate,ConflictMember,ConsolidateRequest}.java`.

**Backend — modified**
- `name/homotypy/HomotypyDetector.java` — extract `group(candidates, existingKeys)`; `detect` delegates.

**Backend — tests**
- `name/homotypy/HomotypyDetectorTest.java` — add a `group()` delegation/flat-list test.
- `name/homotypy/ConsolidationApiIT.java` (new) — scan + consolidate end to end.

**Frontend — new**
- `homotypy/HomotypicConflictsPage.tsx` (+ `.test.tsx`).

**Frontend — modified**
- `api/usages.ts` (+ types), `App.tsx` (route), `names/NameActionMenu.tsx` (launch item).

---

## Task 1: Extract `HomotypyDetector.group()`

**Files:**
- Modify: `backend/src/main/java/org/catalogueoflife/editor/name/homotypy/HomotypyDetector.java`
- Test: `backend/src/test/java/org/catalogueoflife/editor/name/homotypy/HomotypyDetectorTest.java`

**Interfaces:**
- Produces: `HomotypyDetector.group(List<NameUsage> candidates, Set<String> existingKeys) -> HomotypyProposal` (public, status-agnostic — clusters exactly the usages passed, in list order). `detect(accepted, synonyms, existingKeys)` keeps its current behavior by pre-filtering (accepted first, drop MISAPPLIED synonyms) then delegating to `group`.

- [ ] **Step 1: Write the failing delegation test**

Add to `HomotypyDetectorTest.java`:

```java
  @Test
  void groupClustersFlatListLikeDetect() {
    // group() over [accepted, recomb] gives the same single-group result as detect()
    NameUsage accepted = usage(1, Status.ACCEPTED, "Poa", "annua", null, "L.", "1753", null, null);
    NameUsage recomb = usage(2, Status.SYNONYM, "Ochlopoa", "annua", null, "H.Scholz", null, "L.", null);
    HomotypyProposal viaGroup = detector.group(java.util.List.of(accepted, recomb), java.util.Set.of());
    assertThat(viaGroup.groups()).anySatisfy(g -> {
      assertThat(g.basionymUsageId()).isEqualTo(1);
      assertThat(g.memberUsageIds()).containsExactlyInAnyOrder(1, 2);
      assertThat(g.relations()).anySatisfy(r -> {
        assertThat(r.usageId()).isEqualTo(2);
        assertThat(r.relatedUsageId()).isEqualTo(1);
        assertThat(r.type()).isEqualTo("basionym");
      });
    });
  }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw -Dtest=HomotypyDetectorTest test`
Expected: FAIL to compile — `detector.group(...)` does not exist.

- [ ] **Step 3: Extract `group` and delegate from `detect`**

In `HomotypyDetector.java`, replace the `detect` method (lines 34–92) with a thin wrapper plus the extracted `group`. Keep every private helper (`rel`, `epithetKey`, `authorKey`, `yearOf`, `yearCompatible`, `hasBasionymAuthorship`, `isBlank`, `notBlank`) and the `Cluster` inner class unchanged:

```java
  public HomotypyProposal detect(NameUsage accepted, List<NameUsage> synonyms, Set<String> existingKeys) {
    List<NameUsage> candidates = new ArrayList<>();
    candidates.add(accepted); // accepted first so its group is anchored on it
    for (NameUsage s : synonyms) {
      if (s.getStatus() != Status.MISAPPLIED) candidates.add(s);
    }
    return group(candidates, existingKeys);
  }

  // Status-agnostic clustering: clusters exactly the usages passed (callers pre-filter). Side 1
  // passes an accepted + its non-misapplied synonyms; Side 2 passes a whole subtree's usages.
  public HomotypyProposal group(List<NameUsage> candidates, Set<String> existingKeys) {
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
        if (!hasBasionymAuthorship(m)) { basionym = m; break; }
      }
      List<Integer> memberIds = c.members.stream().map(NameUsage::getId).toList();
      List<ProposedRelation> relations = new ArrayList<>();
      if (basionym != null) {
        for (NameUsage m : c.members) {
          if (m == basionym) continue;
          String type = hasBasionymAuthorship(m) ? "basionym" : "homotypic";
          relations.add(rel(m.getId(), basionym.getId(), type, existingKeys));
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
```

- [ ] **Step 4: Run the detector tests (GREEN)**

Run: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw -Dtest=HomotypyDetectorTest test`
Expected: PASS — the new `groupClustersFlatListLikeDetect` plus all pre-existing cases (delegation preserves behavior).

- [ ] **Step 5: Commit**

```bash
cd /Users/markus/code/col/coldp-editor
git add backend/src/main/java/org/catalogueoflife/editor/name/homotypy/HomotypyDetector.java backend/src/test/java/org/catalogueoflife/editor/name/homotypy/HomotypyDetectorTest.java
git commit -m "$(cat <<'EOF'
refactor(homotypy): extract HomotypyDetector.group() for flat-list clustering

detect() now pre-filters (accepted first, drop misapplied) and delegates to a public
status-agnostic group(candidates, existingKeys) that clusters exactly the usages given.
Side 2 will feed it a whole subtree's usages. Behavior unchanged for Side 1.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_0187Tf9weEuVzi834TWXv5pi
EOF
)"
```

---

## Task 2: Backend scan — `ConsolidationService.scan` + DTOs + controller GET

**Files:**
- Create: `backend/src/main/java/org/catalogueoflife/editor/name/homotypy/dto/{AcceptedCandidate,ConflictMember,ConflictCluster,ConsolidateRequest}.java`
- Create: `backend/src/main/java/org/catalogueoflife/editor/name/homotypy/ConsolidationService.java` (scan only this task; consolidate added in Task 3)
- Create: `backend/src/main/java/org/catalogueoflife/editor/name/homotypy/ConsolidationController.java` (GET this task)
- Test: `backend/src/test/java/org/catalogueoflife/editor/name/homotypy/ConsolidationApiIT.java`

**Interfaces:**
- Consumes: `HomotypyDetector.group(List<NameUsage>, Set<String>)`; `NameUsageMapper.findByIdInProject(pid,id)`, `NameUsageMapper.findSubtreeIds(pid,id)`; `SynonymAcceptedMapper.findAcceptedFor(pid,synonymId) -> List<Integer>`; `ProjectService.requireRole/requireVisible`; `NameParserService.formatName(NameUsage, NomCode, boolean)`; `Status`; `NameRelationMapper.findByUsage`.
- Produces (REST, `/api/projects/{pid}/usages/{id}`): `GET /homotypic/conflicts -> List<ConflictCluster>`.
- `AcceptedCandidate(int id, String formattedName, int descendantCount, int version)`; `ConflictMember(int id, String formattedName, String status, List<Integer> acceptedTargetIds, boolean proParte, boolean dualStatus)`; `ConflictCluster(List<AcceptedCandidate> accepted, List<ConflictMember> members, Integer suggestedSurvivorId, boolean hasExceptions, List<ProposedRelation> relations)`; `ConsolidateRequest(List<LoserRef> losers, List<ApplyHomotypicRequest.ApplyRelation> relations)` with nested `LoserRef(int acceptedId, int version)` (used in Task 3).

- [ ] **Step 1: Write the failing scan IT**

Create `ConsolidationApiIT.java`. Seed a project owned by `owner`, add `viewer` as viewer (copy the membership/usage-creation helpers from `HomotypyApiIT.java` in the same package). Seed under a family `Poaceae` (accepted): two homotypic **accepted** names in different genera — `Poa annua L.` (accepted, child of Poaceae) and `Ochlopoa annua (L.) H.Scholz` (accepted, child of Poaceae). Assert the scan rooted at the family returns one conflict with both as accepted candidates:

```java
package org.catalogueoflife.editor.name.homotypy;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.catalogueoflife.editor.support.AbstractPostgresIT;
// autowire MockMvc, AppUserService, mappers; reuse the project/member/usage helpers pattern from HomotypyApiIT.

@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
class ConsolidationApiIT extends AbstractPostgresIT {
  // helpers: createProjectOwnedBy("owner"); addViewer(pid,"viewer");
  //   long createUsage(pid, sciName, authorship, rank, status, parentId);  // parented create

  // 1. scan the family: one conflict cluster; accepted candidates include both Poa annua & Ochlopoa annua;
  //    suggestedSurvivorId is the one with more descendants; hasExceptions=false.
  // 2. authz: viewer may GET conflicts (200); non-member 404.
}
```

Concrete scan assertion (owner):

```java
    mvc.perform(get("/api/projects/" + pid + "/usages/" + familyId + "/homotypic/conflicts").with(user("owner")))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.length()").value(1))
       .andExpect(jsonPath("$[0].accepted[?(@.id == " + poaId + ")]").exists())
       .andExpect(jsonPath("$[0].accepted[?(@.id == " + ochlopoaId + ")]").exists())
       .andExpect(jsonPath("$[0].hasExceptions").value(false));
    mvc.perform(get("/api/projects/" + pid + "/usages/" + familyId + "/homotypic/conflicts").with(user("viewer")))
       .andExpect(status().isOk());
```

(For "more descendants" give `poaId` one accepted child species so it wins `suggestedSurvivorId`; assert `jsonPath("$[0].suggestedSurvivorId").value((int) poaId)`.)

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=ConsolidationApiIT -DfailIfNoTests=false verify`
Expected: FAIL to compile (service/controller/DTOs missing).

- [ ] **Step 3: Write the DTOs**

`dto/AcceptedCandidate.java`:
```java
package org.catalogueoflife.editor.name.homotypy.dto;

// A survivor choice for a conflict: an accepted name the cluster resolves to, with its accepted
// subtree size (for the suggestion + display) and its optimistic-lock version (echoed back on apply).
public record AcceptedCandidate(int id, String formattedName, int descendantCount, int version) {}
```

`dto/ConflictMember.java`:
```java
package org.catalogueoflife.editor.name.homotypy.dto;

import java.util.List;

// One clustered name in a conflict, for display. acceptedTargetIds is [self] for an accepted
// member, or the synonym's accepted target ids for a synonym. proParte: a synonym with >1 target.
// dualStatus: the same scientificName appears in the cluster both accepted and as a synonym.
public record ConflictMember(int id, String formattedName, String status,
    List<Integer> acceptedTargetIds, boolean proParte, boolean dualStatus) {}
```

`dto/ConflictCluster.java`:
```java
package org.catalogueoflife.editor.name.homotypy.dto;

import java.util.List;

// A homotypic cluster resolving to >1 distinct accepted name. `accepted` are the survivor choices;
// `members` every clustered name; `relations` the detector's proposed homotypic relations to persist
// on consolidation; `hasExceptions` when any member is pro-parte or dual-status.
public record ConflictCluster(List<AcceptedCandidate> accepted, List<ConflictMember> members,
    Integer suggestedSurvivorId, boolean hasExceptions, List<ProposedRelation> relations) {}
```

`dto/ConsolidateRequest.java`:
```java
package org.catalogueoflife.editor.name.homotypy.dto;

import java.util.List;

// Body of POST /usages/{survivorId}/homotypic/consolidate: demote each loser accepted name to a
// SYNONYM of the survivor and persist the cluster's homotypic relations.
public record ConsolidateRequest(List<LoserRef> losers,
    List<ApplyHomotypicRequest.ApplyRelation> relations) {
  public record LoserRef(int acceptedId, int version) {}
}
```

- [ ] **Step 4: Write `ConsolidationService` (scan only)**

`ConsolidationService.java`:
```java
package org.catalogueoflife.editor.name.homotypy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.catalogueoflife.editor.child.NameRelationMapper;
import org.catalogueoflife.editor.child.dto.NameRelationResponse;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.name.NameUsageMapper;
import org.catalogueoflife.editor.name.Status;
import org.catalogueoflife.editor.name.SynonymAcceptedMapper;
import org.catalogueoflife.editor.name.homotypy.dto.AcceptedCandidate;
import org.catalogueoflife.editor.name.homotypy.dto.ConflictCluster;
import org.catalogueoflife.editor.name.homotypy.dto.ConflictMember;
import org.catalogueoflife.editor.name.homotypy.dto.ProposedGroup;
import org.catalogueoflife.editor.parse.NameParserService;
import org.catalogueoflife.editor.project.Project;
import org.catalogueoflife.editor.project.ProjectService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ConsolidationService {

  private final NameUsageMapper usages;
  private final SynonymAcceptedMapper synonymAccepted;
  private final NameRelationMapper nameRelations;
  private final HomotypyDetector detector;
  private final ProjectService projects;
  private final NameParserService parser;

  public ConsolidationService(NameUsageMapper usages, SynonymAcceptedMapper synonymAccepted,
      NameRelationMapper nameRelations, HomotypyDetector detector, ProjectService projects,
      NameParserService parser) {
    this.usages = usages;
    this.synonymAccepted = synonymAccepted;
    this.nameRelations = nameRelations;
    this.detector = detector;
    this.projects = projects;
    this.parser = parser;
  }

  public List<ConflictCluster> scan(int userId, int projectId, int rootId) {
    Project project = projects.requireVisible(userId, projectId);
    requireUsage(projectId, rootId);

    // Load clusterable usages in the subtree: ACCEPTED + SYNONYM, excluding supraspecific + autonyms.
    List<NameUsage> candidates = new ArrayList<>();
    for (Integer id : usages.findSubtreeIds(projectId, rootId)) {
      NameUsage u = usages.findByIdInProject(projectId, id);
      if (u == null) continue;
      if (u.getStatus() != Status.ACCEPTED && u.getStatus() != Status.SYNONYM) continue;
      if (isBlank(u.getSpecificEpithet())) continue; // supraspecific
      if (notBlank(u.getInfraspecificEpithet())
          && u.getInfraspecificEpithet().equals(u.getSpecificEpithet())) continue; // autonym
      candidates.add(u);
    }

    // Accepted-target resolution needs the actual usages by id (targets may be outside the subtree).
    Map<Integer, NameUsage> byId = new HashMap<>();
    candidates.forEach(u -> byId.put(u.getId(), u));

    List<ProposedGroup> groups = detector.group(candidates, Set.of()).groups();
    List<ConflictCluster> conflicts = new ArrayList<>();

    for (ProposedGroup g : groups) {
      // resolve each member's accepted target(s); collect the distinct accepted-name set
      Set<Integer> distinctAccepted = new LinkedHashSet<>();
      List<ConflictMember> members = new ArrayList<>();
      // dual-status: same scientificName appearing both accepted and as a synonym in this cluster
      Map<String, Boolean> hasAccepted = new HashMap<>();
      Map<String, Boolean> hasSynonym = new HashMap<>();
      for (Integer mid : g.memberUsageIds()) {
        NameUsage u = byId.get(mid);
        if (u == null) continue;
        String sn = u.getScientificName() == null ? "" : u.getScientificName();
        if (u.getStatus() == Status.ACCEPTED) hasAccepted.put(sn, true); else hasSynonym.put(sn, true);
      }
      for (Integer mid : g.memberUsageIds()) {
        NameUsage u = byId.get(mid);
        if (u == null) continue;
        List<Integer> targets;
        if (u.getStatus() == Status.ACCEPTED) {
          targets = List.of(u.getId());
        } else {
          targets = synonymAccepted.findAcceptedFor(projectId, u.getId());
        }
        distinctAccepted.addAll(targets);
        String sn = u.getScientificName() == null ? "" : u.getScientificName();
        boolean proParte = u.getStatus() == Status.SYNONYM && targets.size() > 1;
        boolean dualStatus = Boolean.TRUE.equals(hasAccepted.get(sn)) && Boolean.TRUE.equals(hasSynonym.get(sn));
        members.add(new ConflictMember(u.getId(), formatted(u, project), u.getStatus().name(),
            targets, proParte, dualStatus));
      }

      if (distinctAccepted.size() <= 1) continue; // not a conflict

      // Build the accepted candidates (survivor choices) -- loaded project-wide (targets may be
      // outside the subtree). Record each candidate's combination year for the tie-break.
      List<AcceptedCandidate> accepted = new ArrayList<>();
      Map<Integer, Integer> yearByAccepted = new HashMap<>();
      for (Integer aid : distinctAccepted) {
        NameUsage a = usages.findByIdInProject(projectId, aid);
        if (a == null) continue;
        int descendants = Math.max(0, usages.findSubtreeIds(projectId, aid).size() - 1);
        accepted.add(new AcceptedCandidate(a.getId(), formatted(a, project), descendants, a.getVersion()));
        yearByAccepted.put(a.getId(), parseYear(a.getCombinationAuthorshipYear()));
      }
      if (accepted.size() <= 1) continue; // all targets resolved to the same/one loadable accepted

      // Suggested survivor: most descendants, then oldest combination year, then name.
      Integer suggested = accepted.stream()
          .sorted(Comparator
              .comparingInt(AcceptedCandidate::descendantCount).reversed()
              .thenComparingInt((AcceptedCandidate c) -> yearByAccepted.get(c.id()))
              .thenComparing(AcceptedCandidate::formattedName))
          .map(AcceptedCandidate::id).findFirst().orElse(null);

      boolean hasExceptions = members.stream().anyMatch(m -> m.proParte() || m.dualStatus());
      conflicts.add(new ConflictCluster(accepted, members, suggested, hasExceptions, g.relations()));
    }
    return conflicts;
  }

  // Combination-authorship year as an int; unparsable/absent sorts last (newest) so it loses the
  // "oldest" tie-break. Years are stored as strings (e.g. "1753").
  private static int parseYear(String y) {
    if (y == null) return Integer.MAX_VALUE;
    try { return Integer.parseInt(y.trim()); } catch (NumberFormatException e) { return Integer.MAX_VALUE; }
  }

  private String formatted(NameUsage u, Project project) {
    return parser.formatName(u, project.getNomCode(), false);
  }

  private void requireUsage(int projectId, int id) {
    if (usages.findByIdInProject(projectId, id) == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "name usage not found");
    }
  }

  private static boolean isBlank(String s) { return s == null || s.isBlank(); }
  private static boolean notBlank(String s) { return !isBlank(s); }
}
```

- [ ] **Step 5: Write `ConsolidationController` (GET)**

`ConsolidationController.java`:
```java
package org.catalogueoflife.editor.name.homotypy;

import java.util.List;
import org.catalogueoflife.editor.auth.CurrentUser;
import org.catalogueoflife.editor.name.homotypy.dto.ConflictCluster;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{pid}/usages/{id}")
public class ConsolidationController {

  private final ConsolidationService service;
  private final CurrentUser currentUser;

  public ConsolidationController(ConsolidationService service, CurrentUser currentUser) {
    this.service = service;
    this.currentUser = currentUser;
  }

  @GetMapping("/homotypic/conflicts")
  public List<ConflictCluster> conflicts(@PathVariable int pid, @PathVariable int id) {
    return service.scan(currentUser.require().getId(), pid, id);
  }
}
```

- [ ] **Step 6: Run the scan IT (GREEN)**

Run: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=ConsolidationApiIT -DfailIfNoTests=false verify`
Expected: PASS (the scan cases; consolidate cases come in Task 3). Debug seeding/filters until green — remember the parented `createUsage` must set `parentId` so `findSubtreeIds` reaches the two names, and give one an accepted child so `suggestedSurvivorId` is determinate.

- [ ] **Step 7: Commit**

```bash
cd /Users/markus/code/col/coldp-editor
git add backend/src/main/java/org/catalogueoflife/editor/name/homotypy backend/src/test/java/org/catalogueoflife/editor/name/homotypy/ConsolidationApiIT.java
git commit -m "$(cat <<'EOF'
feat(homotypy): scan a subtree for homotypic conflicts (>1 accepted name)

ConsolidationService.scan buckets the accepted subtree's species/infraspecific usages
via HomotypyDetector.group, resolves each member's accepted target(s), and returns the
clusters resolving to >1 distinct accepted name -- with survivor candidates (most
descendants suggested), and pro-parte / dual-status members flagged.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_0187Tf9weEuVzi834TWXv5pi
EOF
)"
```

---

## Task 3: Backend consolidate — `ConsolidationService.consolidate` + controller POST

**Files:**
- Modify: `backend/src/main/java/org/catalogueoflife/editor/name/homotypy/ConsolidationService.java`, `ConsolidationController.java`
- Test: `backend/src/test/java/org/catalogueoflife/editor/name/homotypy/ConsolidationApiIT.java`

**Interfaces:**
- Consumes: `NameUsageService.demote(int userId, int projectId, int id, DemoteRequest)` where `DemoteRequest(Integer acceptedId, String status, String childrenTo, String synonymsTo, int version)`; `HomotypyService.apply(int userId, int projectId, int acceptedId, ApplyHomotypicRequest) -> Synonymy` where `ApplyHomotypicRequest(List<ApplyRelation> relations)`.
- Produces (REST): `POST /api/projects/{pid}/usages/{survivorId}/homotypic/consolidate` body `ConsolidateRequest` → `Synonymy`.

- [ ] **Step 1: Write the failing consolidate IT case**

Add to `ConsolidationApiIT.java`, reusing the seeded family with `Poa annua`(survivor, with a child) and `Ochlopoa annua`(loser). First GET the conflict to read the loser's version from `accepted[?(@.id==ochlopoaId)].version`, then consolidate:

```java
    // consolidate: sink Ochlopoa annua into Poa annua
    String body = "{\"losers\":[{\"acceptedId\":" + ochlopoaId + ",\"version\":" + ochlopoaVersion + "}],"
        + "\"relations\":[{\"usageId\":" + ochlopoaId + ",\"relatedUsageId\":" + poaId + ",\"type\":\"basionym\"}]}";
    mvc.perform(post("/api/projects/" + pid + "/usages/" + poaId + "/homotypic/consolidate")
            .with(user("owner")).with(csrf())
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body))
       .andExpect(status().isOk())
       // Ochlopoa annua now appears under Poa annua's homotypic synonyms
       .andExpect(jsonPath("$.homotypic[?(@.id == " + ochlopoaId + ")]").exists());

    // re-scan: the conflict is gone
    mvc.perform(get("/api/projects/" + pid + "/usages/" + familyId + "/homotypic/conflicts").with(user("owner")))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.length()").value(0));

    // authz: viewer cannot consolidate
    mvc.perform(post("/api/projects/" + pid + "/usages/" + poaId + "/homotypic/consolidate")
            .with(user("viewer")).with(csrf())
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body))
       .andExpect(status().isForbidden());
```

(Read `ochlopoaVersion` from the earlier conflicts GET response JSON — a freshly created usage is version 0, so `0` is fine for the first consolidate.)

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=ConsolidationApiIT -DfailIfNoTests=false verify`
Expected: FAIL — no `consolidate` endpoint (404/405).

- [ ] **Step 3: Add `NameUsageService` + `HomotypyService` to `ConsolidationService` and implement `consolidate`**

Add the two collaborators to the constructor and fields of `ConsolidationService`:

```java
  private final org.catalogueoflife.editor.name.NameUsageService nameUsages;
  private final HomotypyService homotypy;
```
Add both params to the constructor (and assign them). Then add the method:

```java
  @org.springframework.transaction.annotation.Transactional
  public org.catalogueoflife.editor.name.homotypy.dto.Synonymy consolidate(int userId, int projectId,
      int survivorId, org.catalogueoflife.editor.name.homotypy.dto.ConsolidateRequest req) {
    requireEditor(userId, projectId);
    requireUsage(projectId, survivorId);
    if (req != null && req.losers() != null) {
      for (var loser : req.losers()) {
        if (loser.acceptedId() == survivorId) continue; // never demote the survivor
        // Reuse the demote path: loser -> SYNONYM of survivor, children + synonyms -> survivor.
        nameUsages.demote(userId, projectId, loser.acceptedId(),
            new org.catalogueoflife.editor.name.dto.DemoteRequest(
                survivorId, "SYNONYM", "new-accepted", "new-accepted", loser.version()));
      }
    }
    // Persist the cluster's homotypic relations (idempotent) and return the survivor's synonymy.
    var relations = req == null || req.relations() == null ? java.util.List.<org.catalogueoflife.editor.name.homotypy.dto.ApplyHomotypicRequest.ApplyRelation>of() : req.relations();
    return homotypy.apply(userId, projectId, survivorId,
        new org.catalogueoflife.editor.name.homotypy.dto.ApplyHomotypicRequest(relations));
  }

  private void requireEditor(int userId, int projectId) {
    String role = projects.requireRole(userId, projectId);
    if (!role.equals(org.catalogueoflife.editor.project.Role.OWNER.dbValue())
        && !role.equals(org.catalogueoflife.editor.project.Role.EDITOR.dbValue())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "owner or editor required");
    }
  }
```

Note: `demote()` needs `childrenTo`/`synonymsTo` only when the loser actually has children/synonyms; passing them unconditionally is safe (they are ignored when there are none — see `NameUsageService.demote`, which only reads them inside the `!childIds.isEmpty()` / `!synIds.isEmpty()` branches). `homotypy.apply` also revalidates editor role and is `@Transactional`; being called within this transaction it joins it (REQUIRED), so the whole consolidation is atomic — a stale loser version throws 409 from `demote` and rolls everything back.

- [ ] **Step 4: Add the POST endpoint**

In `ConsolidationController.java` add:
```java
  @org.springframework.web.bind.annotation.PostMapping("/homotypic/consolidate")
  public org.catalogueoflife.editor.name.homotypy.dto.Synonymy consolidate(@PathVariable int pid,
      @PathVariable int id,
      @org.springframework.web.bind.annotation.RequestBody org.catalogueoflife.editor.name.homotypy.dto.ConsolidateRequest req) {
    return service.consolidate(currentUser.require().getId(), pid, id, req);
  }
```
(The `{id}` path variable is the `survivorId`.)

- [ ] **Step 5: Run the IT (GREEN)**

Run: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=ConsolidationApiIT -DfailIfNoTests=false verify`
Expected: PASS (scan + consolidate + re-scan-empty + authz).

- [ ] **Step 6: Full clean verify (new records/beans)**

Run: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw clean verify`
Expected: BUILD SUCCESS (ignore only the documented pre-existing env-only failures).

- [ ] **Step 7: Commit**

```bash
cd /Users/markus/code/col/coldp-editor
git add backend/src/main/java/org/catalogueoflife/editor/name/homotypy backend/src/test/java/org/catalogueoflife/editor/name/homotypy/ConsolidationApiIT.java
git commit -m "$(cat <<'EOF'
feat(homotypy): consolidate a homotypic conflict (demote losers to the survivor)

POST /usages/{survivorId}/homotypic/consolidate demotes each loser accepted name to a
SYNONYM of the survivor (reusing demote(): children + synonyms -> survivor), persists the
cluster's homotypic relations via HomotypyService.apply, and returns the survivor's
synonymy. Atomic per cluster (a stale loser version -> 409, full rollback); owner/editor.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_0187Tf9weEuVzi834TWXv5pi
EOF
)"
```

---

## Task 4: Frontend — conflicts page + api + route + launch

**Files:**
- Modify: `frontend/src/api/usages.ts`, `frontend/src/App.tsx`, `frontend/src/names/NameActionMenu.tsx`
- Create: `frontend/src/homotypy/HomotypicConflictsPage.tsx`, `frontend/src/homotypy/HomotypicConflictsPage.test.tsx`

**Interfaces:**
- Produces: `getHomotypicConflicts(pid, rootId) -> Promise<ConflictCluster[]>`, `consolidateHomotypic(pid, survivorId, {losers, relations}) -> Promise<Synonymy>`; types `ConflictCluster`, `AcceptedCandidate`, `ConflictMember`, `LoserRef`.
- Consumes: `api<T>(path, opts)`, `Synonymy`/`ProposedRelation`/`ApplyRelation` (from Task-3/Side-1 `api/usages.ts`), `useNavigate`.

- [ ] **Step 1: Add api functions + types to `api/usages.ts`**

Append to `frontend/src/api/usages.ts`:

```typescript
// --- Side 2: homotypic consolidation ---
export interface AcceptedCandidate {
  id: number;
  formattedName: string | null;
  descendantCount: number;
  version: number;
}
export interface ConflictMember {
  id: number;
  formattedName: string | null;
  status: string;
  acceptedTargetIds: number[];
  proParte: boolean;
  dualStatus: boolean;
}
export interface ConflictCluster {
  accepted: AcceptedCandidate[];
  members: ConflictMember[];
  suggestedSurvivorId: number | null;
  hasExceptions: boolean;
  relations: ProposedRelation[];
}
export interface LoserRef {
  acceptedId: number;
  version: number;
}

export function getHomotypicConflicts(pid: number, rootId: number): Promise<ConflictCluster[]> {
  return api<ConflictCluster[]>(`/api/projects/${pid}/usages/${rootId}/homotypic/conflicts`);
}
export function consolidateHomotypic(
  pid: number,
  survivorId: number,
  body: { losers: LoserRef[]; relations: ApplyRelation[] },
): Promise<Synonymy> {
  return api<Synonymy>(`/api/projects/${pid}/usages/${survivorId}/homotypic/consolidate`, {
    method: 'POST',
    json: body,
  });
}
```

- [ ] **Step 2: Write the failing page test**

Create `frontend/src/homotypy/HomotypicConflictsPage.test.tsx`:

```tsx
import { describe, it, expect, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { render } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import { Routes, Route } from 'react-router-dom';
import HomotypicConflictsPage from './HomotypicConflictsPage';

const CONFLICTS = [
  {
    accepted: [
      { id: 1, formattedName: 'Poa annua L.', descendantCount: 3, version: 0 },
      { id: 2, formattedName: 'Ochlopoa annua (L.) H.Scholz', descendantCount: 0, version: 0 },
    ],
    members: [
      { id: 1, formattedName: 'Poa annua L.', status: 'ACCEPTED', acceptedTargetIds: [1], proParte: false, dualStatus: false },
      { id: 2, formattedName: 'Ochlopoa annua (L.) H.Scholz', status: 'ACCEPTED', acceptedTargetIds: [2], proParte: false, dualStatus: false },
    ],
    suggestedSurvivorId: 1,
    hasExceptions: false,
    relations: [{ usageId: 2, relatedUsageId: 1, type: 'basionym', alreadyExists: false }],
  },
];

function renderPage() {
  return render(
    <Routes>
      <Route path="/projects/:projectId/homotypic-conflicts/:rootId" element={<HomotypicConflictsPage />} />
    </Routes>,
    { route: '/projects/7/homotypic-conflicts/100' },
  );
}

describe('HomotypicConflictsPage', () => {
  beforeEach(() => {
    server.use(
      http.get('/api/projects/7/usages/100/homotypic/conflicts', () => HttpResponse.json(CONFLICTS)),
      http.get('/api/projects/7', () => HttpResponse.json({ id: 7, title: 'P', role: 'owner' })),
    );
  });

  it('lists a conflict with its accepted candidates', async () => {
    renderPage();
    expect(await screen.findByText(/Poa annua/)).toBeInTheDocument();
    expect(screen.getByText(/Ochlopoa annua/)).toBeInTheDocument();
  });

  it('consolidates: demotes the non-survivor accepted names', async () => {
    let posted: unknown = null;
    server.use(
      http.post('/api/projects/7/usages/1/homotypic/consolidate', async ({ request }) => {
        posted = await request.json();
        return HttpResponse.json({ homotypic: [], heterotypicGroups: [], misapplied: [] });
      }),
    );
    renderPage();
    await screen.findByText(/Poa annua/);
    // survivor defaults to id 1 (Poa annua); Consolidate sends Ochlopoa (id 2) as the loser
    await userEvent.click(screen.getByRole('button', { name: /consolidate/i }));
    await waitFor(() =>
      expect(posted).toEqual({
        losers: [{ acceptedId: 2, version: 0 }],
        relations: [{ usageId: 2, relatedUsageId: 1, type: 'basionym' }],
      }),
    );
  });
});
```

- [ ] **Step 3: Run it to verify it fails**

Run: `cd frontend && npx vitest run src/homotypy/HomotypicConflictsPage.test.tsx`
Expected: FAIL — `./HomotypicConflictsPage` unresolved.

- [ ] **Step 4: Write `HomotypicConflictsPage.tsx`**

Create `frontend/src/homotypy/HomotypicConflictsPage.tsx`:

```tsx
import { Badge, Button, Card, Group, Loader, Radio, Stack, Text, Title } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { messageFor } from '../api/client';
import {
  consolidateHomotypic,
  getHomotypicConflicts,
  type ConflictCluster,
} from '../api/usages';

function clusterKey(c: ConflictCluster) {
  return c.accepted.map((a) => a.id).sort((x, y) => x - y).join('-');
}

// One conflict card: pick a survivor among the accepted candidates; Consolidate demotes the others.
function ConflictCard({
  pid,
  cluster,
  onDone,
}: {
  pid: number;
  cluster: ConflictCluster;
  onDone: () => void;
}) {
  const qc = useQueryClient();
  const [survivor, setSurvivor] = useState<number>(
    cluster.suggestedSurvivorId ?? cluster.accepted[0]?.id,
  );

  const consolidate = useMutation({
    mutationFn: () => {
      const losers = cluster.accepted
        .filter((a) => a.id !== survivor)
        .map((a) => ({ acceptedId: a.id, version: a.version }));
      const relations = cluster.relations.map((r) => ({
        usageId: r.usageId,
        relatedUsageId: r.relatedUsageId,
        type: r.type,
      }));
      return consolidateHomotypic(pid, survivor, { losers, relations });
    },
    onSuccess: async () => {
      await qc.invalidateQueries({ queryKey: ['tree', pid] });
      notifications.show({ message: 'Consolidated' });
      onDone();
    },
    onError: (e) => notifications.show({ color: 'red', message: messageFor(e, 'Could not consolidate') }),
  });

  return (
    <Card withBorder>
      <Stack gap="xs">
        <Group gap="xs">
          <Text fw={600}>Homotypic conflict</Text>
          {cluster.hasExceptions && (
            <Badge color="yellow" variant="light">pro parte / dual status — review</Badge>
          )}
        </Group>
        <Text size="xs" c="dimmed">Members</Text>
        {cluster.members.map((m) => (
          <Text key={m.id} size="sm">
            <span dangerouslySetInnerHTML={{ __html: m.formattedName ?? '' }} />{' '}
            <Text span c="dimmed" size="xs">
              {m.status}
              {m.proParte ? ' · pro parte' : ''}
              {m.dualStatus ? ' · dual status' : ''}
            </Text>
          </Text>
        ))}
        <Text size="xs" c="dimmed" mt="xs">Keep as the single accepted name</Text>
        <Radio.Group value={String(survivor)} onChange={(v) => setSurvivor(Number(v))}>
          <Stack gap={4}>
            {cluster.accepted.map((a) => (
              <Radio
                key={a.id}
                value={String(a.id)}
                label={
                  <span>
                    <span dangerouslySetInnerHTML={{ __html: a.formattedName ?? '' }} />{' '}
                    <Text span c="dimmed" size="xs">({a.descendantCount} descendants)</Text>
                  </span>
                }
              />
            ))}
          </Stack>
        </Radio.Group>
        <Group justify="flex-end">
          <Button variant="default" onClick={onDone}>Skip</Button>
          <Button loading={consolidate.isPending} onClick={() => consolidate.mutate()}>
            Consolidate
          </Button>
        </Group>
      </Stack>
    </Card>
  );
}

// Full page: scans a focal taxon's accepted subtree for homotypic clusters resolving to >1 accepted
// name, and resolves each. Launched from a taxon's action menu.
export default function HomotypicConflictsPage() {
  const { projectId, rootId } = useParams();
  const pid = Number(projectId);
  const root = Number(rootId);
  const { data, isLoading } = useQuery({
    queryKey: ['homotypicConflicts', pid, root],
    queryFn: () => getHomotypicConflicts(pid, root),
  });
  // locally-resolved/skipped clusters are hidden without a re-fetch
  const [dismissed, setDismissed] = useState<Set<string>>(new Set());

  if (isLoading) return <Loader />;
  const clusters = (data ?? []).filter((c) => !dismissed.has(clusterKey(c)));

  return (
    <Stack>
      <Title order={3}>Homotypic conflicts</Title>
      {clusters.length === 0 ? (
        <Text c="dimmed">No homotypic conflicts found in this subtree.</Text>
      ) : (
        clusters.map((c) => (
          <ConflictCard
            key={clusterKey(c)}
            pid={pid}
            cluster={c}
            onDone={() => setDismissed((prev) => new Set(prev).add(clusterKey(c)))}
          />
        ))
      )}
    </Stack>
  );
}
```

- [ ] **Step 5: Run the page test (GREEN)**

Run: `cd frontend && npx vitest run src/homotypy/HomotypicConflictsPage.test.tsx`
Expected: PASS (2 tests).

- [ ] **Step 6: Wire the route in `App.tsx`**

In `frontend/src/App.tsx`, add the import next to the other page imports:
```tsx
import HomotypicConflictsPage from './homotypy/HomotypicConflictsPage';
```
and add the route inside the `projects/:projectId` `ProjectLayout` children (next to `discussions/:id`):
```tsx
            <Route path="homotypic-conflicts/:rootId" element={<HomotypicConflictsPage />} />
```

- [ ] **Step 7: Add a launch item to `NameActionMenu`**

In `frontend/src/names/NameActionMenu.tsx`, import `useNavigate` and an icon, and add a menu item (owner/editor path, where the other `canEdit` items live) that navigates to the page for the focal usage. Add to the imports:
```tsx
import { useNavigate } from 'react-router-dom';
import { IconGitMerge } from '@tabler/icons-react';
```
Inside the component body, get the navigator:
```tsx
  const navigate = useNavigate();
```
And add this `Menu.Item` alongside the existing edit actions (inside the `canEdit` block that renders the menu contents):
```tsx
          <Menu.Item
            leftSection={<IconGitMerge size={14} />}
            onClick={() => navigate(`/projects/${pid}/homotypic-conflicts/${usage.id}`)}
          >
            Find homotypic conflicts
          </Menu.Item>
```

- [ ] **Step 8: Typecheck + tests + build**

Run: `cd frontend && npx tsc -b && npx vitest run && npm run build`
Expected: PASS (page tests + full suite green; tsc clean; build succeeds — the pre-existing chunk-size warning is fine).

- [ ] **Step 9: Commit**

```bash
cd /Users/markus/code/col/coldp-editor
git add frontend/src/api/usages.ts frontend/src/homotypy frontend/src/App.tsx frontend/src/names/NameActionMenu.tsx
git commit -m "$(cat <<'EOF'
feat(homotypy): homotypic-conflicts page + launch from the name action menu

A paginated page scans a focal taxon's accepted subtree for homotypic clusters
resolving to >1 accepted name; each card lets the curator pick the survivor (most
descendants pre-selected, pro-parte/dual-status badged) and Consolidate (demotes the
others) or Skip. Launched via "Find homotypic conflicts" in the name action menu.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_0187Tf9weEuVzi834TWXv5pi
EOF
)"
```

---

## Task 5: Backlog + spec status

**Files:**
- Modify: `backlog.md`, `docs/superpowers/specs/2026-07-20-homotypic-consolidation-side2-design.md`

- [ ] **Step 1: Mark Side 2 shipped in `backlog.md`**

Update the "Homotypic grouping" entry (Tools & import) so its Side-2 clause reads shipped:
```markdown
  *Side 2 shipped:* scan a focal taxon's accepted subtree for homotypic clusters resolving to >1
  accepted name (incl. via synonyms), pick a survivor (most descendants suggested), and demote the
  others to homotypic synonyms; pro-parte / dual-status flagged. Consolidation page + `GET/POST
  …/usages/{id}/homotypic/{conflicts,consolidate}`.
```

- [ ] **Step 2: Note status in the spec**

Under the spec's date line add: `**Status:** Implemented 2026-07-20 (plan: docs/superpowers/plans/2026-07-20-homotypic-consolidation-side2.md).`

- [ ] **Step 3: Commit**

```bash
cd /Users/markus/code/col/coldp-editor
git add backlog.md docs/superpowers/specs/2026-07-20-homotypic-consolidation-side2-design.md
git commit -m "$(cat <<'EOF'
docs: mark homotypic grouping Side 2 shipped

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_0187Tf9weEuVzi834TWXv5pi
EOF
)"
```

---

## Notes for the implementer

- **Seeding `ConsolidationApiIT`:** copy the project/member helpers and the create-usage helper from `HomotypyApiIT.java` (same package). You need a *parented* create so `findSubtreeIds(family)` reaches the names — if the existing helper doesn't set `parentId`, extend it to POST `parentId` in the create body (see `CreateNameUsageRequest`). Give `Poa annua` one accepted child species so its `descendantCount` (1) beats `Ochlopoa annua`'s (0) and `suggestedSurvivorId` is deterministic.
- **`demote()` reuse:** it is a public method on `NameUsageService`; `ConsolidationService` injects that bean. `demote` sets the loser to a synonym of the survivor and (only when present) reparents children / re-points synonyms to the survivor — exactly CLB's `convertToSynonym`. Do not re-implement it.
- **`formattedName` is HTML-ish:** `parser.formatName(u, nomCode, false)` returns a plain string (the `false` = not HTML), so the page's `dangerouslySetInnerHTML` is harmless here but consistent with how Side-1 rendered names; if you prefer, render `formattedName` as plain text — both are acceptable since `html=false`.
- **Atomicity:** `consolidate` is `@Transactional`; the nested `demote`/`apply` calls join the same transaction (Spring `REQUIRED`), so a 409 from any `demote` rolls the whole cluster back. Don't add try/catch that swallows it.
- **Pre-existing env-only failures** (not this work): `ChangeApiIT` (network/CSL); full-suite "too many clients already" (Postgres). Individual ITs pass in isolation.
