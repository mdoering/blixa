package org.catalogueoflife.editor.name.homotypy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.name.NameUsageMapper;
import org.catalogueoflife.editor.name.Status;
import org.catalogueoflife.editor.name.SynAccLink;
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
  private final HomotypyDetector detector;
  private final ProjectService projects;
  private final NameParserService parser;
  private final org.catalogueoflife.editor.name.NameUsageService nameUsages;
  private final HomotypyService homotypy;

  public ConsolidationService(NameUsageMapper usages, SynonymAcceptedMapper synonymAccepted,
      HomotypyDetector detector, ProjectService projects, NameParserService parser,
      org.catalogueoflife.editor.name.NameUsageService nameUsages, HomotypyService homotypy) {
    this.usages = usages;
    this.synonymAccepted = synonymAccepted;
    this.detector = detector;
    this.projects = projects;
    this.parser = parser;
    this.nameUsages = nameUsages;
    this.homotypy = homotypy;
  }

  public List<ConflictCluster> scan(int userId, int projectId, int rootId) {
    Project project = projects.requireVisible(userId, projectId);

    // Bulk-load every usage in the project once (same full-row projection as findByIdInProject)
    // and index it by id and by parent_id -> children, replacing the old per-id findByIdInProject/
    // findSubtreeIds/findSynonymsOf loops with in-memory lookups.
    Map<Integer, NameUsage> byId = new HashMap<>();
    Map<Integer, List<Integer>> childrenByParent = new HashMap<>();
    for (NameUsage u : usages.findAllByProject(projectId)) {
      byId.put(u.getId(), u);
      if (u.getParentId() != null) {
        childrenByParent.computeIfAbsent(u.getParentId(), k -> new ArrayList<>()).add(u.getId());
      }
    }
    if (!byId.containsKey(rootId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "name usage not found");
    }

    // Bulk-load every synonym_accepted link once and index both directions, replacing the old
    // per-id findSynonymsOf/findAcceptedFor loops with in-memory lookups.
    Map<Integer, List<Integer>> synonymsByAccepted = new HashMap<>();
    Map<Integer, List<Integer>> acceptedBySynonym = new HashMap<>();
    for (SynAccLink link : synonymAccepted.findAllLinks(projectId)) {
      synonymsByAccepted.computeIfAbsent(link.acceptedId(), k -> new ArrayList<>()).add(link.synonymId());
      acceptedBySynonym.computeIfAbsent(link.synonymId(), k -> new ArrayList<>()).add(link.acceptedId());
    }

    // Collect every name taxonomically in the subtree: the accepted classification (a parent_id
    // walk, equivalent to the old findSubtreeIds) PLUS each accepted usage's synonyms. Synonyms
    // carry parent_id = null and link via synonym_accepted, so the parent_id walk alone would miss
    // them; a subtree id with no synonyms simply contributes nothing extra, same as before.
    LinkedHashSet<Integer> candidateIds = new LinkedHashSet<>();
    for (Integer id : subtreeIds(rootId, childrenByParent)) {
      candidateIds.add(id);
      candidateIds.addAll(synonymsByAccepted.getOrDefault(id, List.of()));
    }
    List<NameUsage> candidates = new ArrayList<>();
    for (Integer id : candidateIds) {
      NameUsage u = byId.get(id);
      if (u == null) continue;
      if (u.getStatus() != Status.ACCEPTED && u.getStatus() != Status.SYNONYM) continue;
      if (isBlank(u.getSpecificEpithet())) continue; // supraspecific
      if (notBlank(u.getInfraspecificEpithet())
          && u.getInfraspecificEpithet().equals(u.getSpecificEpithet())) continue; // autonym
      candidates.add(u);
    }

    // Cluster-member resolution below only ever looks up ids drawn from `candidates`, so index
    // those (not the full project) -- matches the old candidates-only byId exactly.
    Map<Integer, NameUsage> candidateById = new HashMap<>();
    candidates.forEach(u -> candidateById.put(u.getId(), u));

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
        NameUsage u = candidateById.get(mid);
        if (u == null) continue;
        String sn = u.getScientificName() == null ? "" : u.getScientificName();
        if (u.getStatus() == Status.ACCEPTED) hasAccepted.put(sn, true); else hasSynonym.put(sn, true);
      }
      for (Integer mid : g.memberUsageIds()) {
        NameUsage u = candidateById.get(mid);
        if (u == null) continue;
        List<Integer> targets;
        if (u.getStatus() == Status.ACCEPTED) {
          targets = List.of(u.getId());
        } else {
          targets = acceptedBySynonym.getOrDefault(u.getId(), List.of());
        }
        distinctAccepted.addAll(targets);
        String sn = u.getScientificName() == null ? "" : u.getScientificName();
        boolean proParte = u.getStatus() == Status.SYNONYM && targets.size() > 1;
        boolean dualStatus = Boolean.TRUE.equals(hasAccepted.get(sn)) && Boolean.TRUE.equals(hasSynonym.get(sn));
        members.add(new ConflictMember(u.getId(), formatted(u, project), u.getStatus().name(),
            targets, u.getVersion(), proParte, dualStatus));
      }

      if (distinctAccepted.size() <= 1) continue; // not a conflict

      // Build the accepted candidates (survivor choices) -- looked up project-wide (targets may be
      // outside the subtree). Record each candidate's combination year for the tie-break.
      List<AcceptedCandidate> accepted = new ArrayList<>();
      Map<Integer, Integer> yearByAccepted = new HashMap<>();
      for (Integer aid : distinctAccepted) {
        NameUsage a = byId.get(aid);
        if (a == null) continue;
        int descendants = Math.max(0, subtreeIds(aid, childrenByParent).size() - 1);
        accepted.add(new AcceptedCandidate(a.getId(), formatted(a, project), descendants));
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

  // Demote each loser accepted MEMBER to a SYNONYM of the survivor (reusing demote(): children +
  // synonyms -> survivor), re-point each synonym MEMBER to the survivor (unlinking its other
  // accepted targets), then persist the cluster's homotypic relations and return the survivor's
  // synonymy. An accepted name reached only as a synonym's target -- never itself a homotypic
  // cluster member -- is neither a loser nor touched here; only the re-pointed synonym moves.
  // @Transactional so a stale loser version (409 from demote) rolls back the whole consolidation,
  // not just the failed loser.
  @org.springframework.transaction.annotation.Transactional
  public org.catalogueoflife.editor.name.homotypy.dto.Synonymy consolidate(int userId, int projectId,
      int survivorId, org.catalogueoflife.editor.name.homotypy.dto.ConsolidateRequest req) {
    requireEditor(userId, projectId);
    NameUsage survivor = usages.findByIdInProject(projectId, survivorId);
    if (survivor == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "name usage not found");
    }
    if (survivor.getStatus() != Status.ACCEPTED) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "survivor must be an accepted name");
    }
    if (req != null && req.losers() != null) {
      for (var loser : req.losers()) {
        if (loser.acceptedId() == survivorId) continue; // never demote the survivor
        // Reuse the demote path: loser -> SYNONYM of survivor, children + synonyms -> survivor.
        nameUsages.demote(userId, projectId, loser.acceptedId(),
            new org.catalogueoflife.editor.name.dto.DemoteRequest(
                survivorId, "SYNONYM", "new-accepted", "new-accepted", loser.version()));
      }
    }
    if (req != null && req.repoint() != null) {
      for (Integer synId : req.repoint()) {
        // link to survivor first (never orphan the synonym), then unlink its other accepted targets
        nameUsages.linkSynonym(userId, projectId, synId, survivorId);
        for (Integer t : synonymAccepted.findAcceptedFor(projectId, synId)) {
          if (t != survivorId) nameUsages.unlinkSynonym(userId, projectId, synId, t);
        }
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

  // Combination-authorship year as an int; unparsable/absent sorts last (newest) so it loses the
  // "oldest" tie-break. Years are stored as strings (e.g. "1753").
  private static int parseYear(String y) {
    if (y == null) return Integer.MAX_VALUE;
    try { return Integer.parseInt(y.trim()); } catch (NumberFormatException e) { return Integer.MAX_VALUE; }
  }

  private String formatted(NameUsage u, Project project) {
    return parser.formatName(u, project.getNomCode(), false);
  }

  // Every id in the subtree rooted at `rootId` (itself included), following childrenByParent --
  // the in-memory equivalent of NameUsageMapper.findSubtreeIds's recursive parent_id walk, reused
  // by scan() both to enumerate the accepted classification under the scan root and to count each
  // survivor candidate's descendants (size() - 1).
  private static List<Integer> subtreeIds(int rootId, Map<Integer, List<Integer>> childrenByParent) {
    List<Integer> result = new ArrayList<>();
    Set<Integer> seen = new java.util.HashSet<>();
    java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
    queue.add(rootId);
    seen.add(rootId);
    while (!queue.isEmpty()) {
      int id = queue.poll();
      result.add(id);
      for (Integer child : childrenByParent.getOrDefault(id, List.of())) {
        if (seen.add(child)) queue.add(child);
      }
    }
    return result;
  }

  private static boolean isBlank(String s) { return s == null || s.isBlank(); }
  private static boolean notBlank(String s) { return !isBlank(s); }
}
