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

  public ConsolidationService(NameUsageMapper usages, SynonymAcceptedMapper synonymAccepted,
      HomotypyDetector detector, ProjectService projects, NameParserService parser) {
    this.usages = usages;
    this.synonymAccepted = synonymAccepted;
    this.detector = detector;
    this.projects = projects;
    this.parser = parser;
  }

  public List<ConflictCluster> scan(int userId, int projectId, int rootId) {
    Project project = projects.requireVisible(userId, projectId);
    requireUsage(projectId, rootId);

    // Collect every name taxonomically in the subtree: the accepted classification (a parent_id
    // walk via findSubtreeIds) PLUS each accepted usage's synonyms. Synonyms carry parent_id = null
    // and link via synonym_accepted, so findSubtreeIds alone would miss them. findSynonymsOf on a
    // non-accepted id simply returns empty, so calling it for every subtree id is harmless.
    LinkedHashSet<Integer> candidateIds = new LinkedHashSet<>();
    for (Integer id : usages.findSubtreeIds(projectId, rootId)) {
      candidateIds.add(id);
      candidateIds.addAll(synonymAccepted.findSynonymsOf(projectId, id));
    }
    List<NameUsage> candidates = new ArrayList<>();
    for (Integer id : candidateIds) {
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
