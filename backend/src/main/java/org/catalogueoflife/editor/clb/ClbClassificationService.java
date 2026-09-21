package org.catalogueoflife.editor.clb;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import life.catalogue.api.model.SimpleName;
import org.catalogueoflife.editor.clb.dto.ClbClassificationPreview;
import org.catalogueoflife.editor.clb.dto.ClbClassificationRequest;
import org.catalogueoflife.editor.clb.dto.ClbClassificationResult;
import org.catalogueoflife.editor.clb.dto.ClbClassificationStep;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.name.NameUsageMapper;
import org.catalogueoflife.editor.name.NameUsageService;
import org.catalogueoflife.editor.name.dto.CreateNameUsageRequest;
import org.catalogueoflife.editor.name.dto.IdentifiersRequest;
import org.catalogueoflife.editor.project.ProjectService;
import org.catalogueoflife.editor.project.Role;
import org.catalogueoflife.editor.tree.TreeMapper;
import org.catalogueoflife.editor.tree.TreeService;
import org.catalogueoflife.editor.tree.dto.MoveRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Compare-with-CLB "wire into tree": places the focal usage under the higher classification of a CLB
 * taxon. Each CLB ancestor (root → direct parent) is matched to an existing ACCEPTED usage of ours by
 * exact name + rank; missing ones can be created. Existing taxa are only ever used, never moved or
 * edited. See docs/superpowers/specs/2026-09-21-clb-compare-copy-classification-relations-design.md.
 *
 * <p>Writes go through NameUsageService.create (audited, validated) and TreeService.move
 * (cycle-safe, optimistic lock), all inside one transaction.
 */
@Service
public class ClbClassificationService {

  private final ClbImportClient client;
  private final NameUsageMapper usages;
  private final TreeMapper tree;
  private final NameUsageService nameUsages;
  private final TreeService treeService;
  private final ProjectService projects;
  // Same configured COL dataset key ClbImportService uses to scope provenance ids as "col:".
  private final String colDataset;

  public ClbClassificationService(ClbImportClient client, NameUsageMapper usages, TreeMapper tree,
      NameUsageService nameUsages, TreeService treeService, ProjectService projects,
      @Value("${coldp.col.match-dataset:3LXR}") String colDataset) {
    this.client = client;
    this.usages = usages;
    this.tree = tree;
    this.nameUsages = nameUsages;
    this.treeService = treeService;
    this.projects = projects;
    this.colDataset = colDataset;
  }

  public ClbClassificationPreview preview(int userId, int projectId, int focalId, String datasetKey,
      String taxonId) {
    projects.requireRole(userId, projectId);
    NameUsage focal = requireTreeNode(projectId, focalId);
    List<ClbClassificationStep> steps = resolve(projectId, focal, ancestors(datasetKey, taxonId), null);
    NameUsage parent = focal.getParentId() == null ? null : usages.findByIdInProject(projectId, focal.getParentId());
    return new ClbClassificationPreview(steps, focal.getParentId(), parent == null ? null : parent.getScientificName());
  }

  @Transactional
  public ClbClassificationResult apply(int userId, int projectId, int focalId, ClbClassificationRequest req) {
    String role = projects.requireRole(userId, projectId);
    if (!role.equals(Role.OWNER.dbValue()) && !role.equals(Role.EDITOR.dbValue())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "owner or editor required");
    }
    NameUsage focal = requireTreeNode(projectId, focalId);
    Set<String> create = req.createClbIds() == null ? Set.of() : new HashSet<>(req.createClbIds());
    String scope = colDataset.equalsIgnoreCase(req.datasetKey()) ? "col" : req.datasetKey();
    int[] created = {0};
    List<SimpleName> ancestors = ancestors(req.datasetKey(), req.taxonId());
    // Only missing ranks BELOW the lowest existing match can be created: existing taxa are never
    // moved, so anything created above one would be left as an empty branch.
    Set<String> creatable = creatable(resolve(projectId, focal, ancestors, null));
    List<ClbClassificationStep> steps = resolve(projectId, focal, ancestors,
        (sn, rank, parentId) -> {
          if (!create.contains(sn.getId()) || !creatable.contains(sn.getId())) return null;
          var u = nameUsages.create(userId, projectId, new CreateNameUsageRequest(sn.getName(),
              blankToNull(sn.getAuthorship()), rank, "ACCEPTED", parentId, null, null, null, null, null,
              null, null, null, null, null, null, null, null));
          if (sn.getId() != null) {
            nameUsages.setIdentifiers(userId, projectId, u.id(), new IdentifiersRequest(
                NameUsageService.mergeScopedId(null, scope, sn.getId()), 0));
          }
          created[0]++;
          return u.id();
        });
    Integer target = null;
    for (ClbClassificationStep s : steps) {
      if (s.matchId() != null) target = s.matchId();
    }
    boolean moved = false;
    if (target != null && !target.equals(focal.getParentId())) {
      treeService.move(userId, projectId, focalId, new MoveRequest(target, focal.getVersion()));
      moved = true;
    }
    return new ClbClassificationResult(target != null ? target : focal.getParentId(), created[0], moved);
  }

  // CLB ids of the missing steps below the lowest existing match (see apply).
  static Set<String> creatable(List<ClbClassificationStep> steps) {
    int lastMatch = -1;
    for (int i = 0; i < steps.size(); i++) {
      if (steps.get(i).matchId() != null) lastMatch = i;
    }
    Set<String> out = new HashSet<>();
    for (int i = lastMatch + 1; i < steps.size(); i++) {
      if (steps.get(i).clbId() != null) out.add(steps.get(i).clbId());
    }
    return out;
  }

  // Creates a missing ancestor under parentId (null = root) and returns its id, or null to skip it.
  @FunctionalInterface
  interface Creator {
    Integer create(SimpleName sn, String rank, Integer parentId);
  }

  // Walks the CLB ancestors root → parent. At each rank: prefer a candidate hanging under the
  // previously resolved ancestor, else the single/first candidate (flagged ambiguous when several).
  // The focal usage and its descendants are never candidates (no cycles). A missing rank is created
  // via `creator` (apply) or left unresolved (preview / not chosen) -- the chain then continues from
  // the last resolved ancestor. A created step is reported with its new id as matchId.
  private List<ClbClassificationStep> resolve(int projectId, NameUsage focal, List<SimpleName> ancestors,
      Creator creator) {
    List<ClbClassificationStep> steps = new ArrayList<>();
    Integer prev = null;
    for (SimpleName sn : ancestors) {
      String rank = sn.getRank().name().toLowerCase(Locale.ROOT);
      List<NameUsage> cands = usages.findAcceptedByNameAndRank(projectId, sn.getName(), rank).stream()
          .filter(c -> c.getId() != focal.getId() && !tree.isDescendant(projectId, focal.getId(), c.getId()))
          .toList();
      final Integer chain = prev;
      NameUsage match = cands.stream().filter(c -> Objects.equals(c.getParentId(), chain)).findFirst()
          .orElse(cands.isEmpty() ? null : cands.get(0));
      boolean inPlace = match != null && Objects.equals(match.getParentId(), chain);
      boolean ambiguous = !inPlace && cands.size() > 1;
      Integer matchId = match == null ? null : match.getId();
      if (matchId == null && creator != null) {
        matchId = creator.create(sn, rank, prev);
        inPlace = matchId != null;
      }
      if (matchId != null) prev = matchId;
      steps.add(new ClbClassificationStep(sn.getId(), rank, sn.getName(), blankToNull(sn.getAuthorship()),
          matchId, inPlace, ambiguous));
    }
    return steps;
  }

  // The CLB taxon's higher classification, root → direct parent: its own entry (and, for a synonym,
  // anything after the accepted name) dropped, as are unranked entries (nothing to match on).
  private List<SimpleName> ancestors(String datasetKey, String taxonId) {
    if (datasetKey == null || datasetKey.isBlank() || taxonId == null || taxonId.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "datasetKey and taxonId are required");
    }
    var info = client.usageInfo(datasetKey, taxonId);
    if (info.getUsage().getStatus() != null && !info.getUsage().getStatus().isTaxon()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "the CLB target is a synonym — compare against its accepted name to copy the classification");
    }
    List<SimpleName> out = new ArrayList<>();
    if (info.getClassification() == null) return out;
    for (SimpleName sn : info.getClassification()) {
      if (taxonId.equals(sn.getId())) break;
      if (sn.getRank() == null || sn.getName() == null || sn.getRank().isUncomparable()) continue;
      out.add(sn);
    }
    return out;
  }

  private NameUsage requireTreeNode(int projectId, int id) {
    NameUsage u = usages.findByIdInProject(projectId, id);
    if (u == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "name usage not found");
    if (!u.getStatus().isTaxon()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "only a taxon (not a synonym) sits in the tree");
    }
    return u;
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s;
  }
}
