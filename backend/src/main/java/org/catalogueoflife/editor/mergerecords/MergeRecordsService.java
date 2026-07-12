package org.catalogueoflife.editor.mergerecords;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.catalogueoflife.editor.audit.AuditService;
import org.catalogueoflife.editor.audit.Operation;
import org.catalogueoflife.editor.mergerecords.dto.MergeResult;
import org.catalogueoflife.editor.mergerecords.dto.UsageMergeCandidate;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.name.NameUsageMapper;
import org.catalogueoflife.editor.name.Status;
import org.catalogueoflife.editor.project.ProjectService;
import org.catalogueoflife.editor.project.Role;
import org.catalogueoflife.editor.tree.TreeMapper;
import org.catalogueoflife.editor.validation.ValidationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MergeRecordsService {

  private final MergeRecordsMapper merge;
  private final NameUsageMapper usages;
  private final ProjectService projects;
  private final TreeMapper tree;
  private final AuditService audit;
  private final ApplicationEventPublisher events;

  public MergeRecordsService(MergeRecordsMapper merge, NameUsageMapper usages, ProjectService projects,
      TreeMapper tree, AuditService audit, ApplicationEventPublisher events) {
    this.merge = merge;
    this.usages = usages;
    this.projects = projects;
    this.tree = tree;
    this.audit = audit;
    this.events = events;
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

  // Destructive apply: repoints every FK pointing at each merged (duplicate) usage onto the
  // survivor, then deletes the duplicates -- one transaction under the project advisory lock.
  // Every repoint runs BEFORE the delete: name_usage.parent_id/basionym_id are ON DELETE SET
  // NULL, and synonym_accepted/taxon_info/children's usage_id are ON DELETE CASCADE, so deleting
  // first would null out or cascade-destroy the very links this is supposed to preserve.
  @Transactional
  public MergeResult mergeUsages(int userId, int projectId, Integer survivorId, List<Integer> ids) {
    requireEditor(userId, projectId);
    List<Integer> all = requireAtLeastTwo(ids);
    if (survivorId == null || !all.contains(survivorId)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "survivorId must be one of the selected records");
    }
    tree.lockProject(projectId);
    NameUsage survivor = usages.findByIdInProject(projectId, survivorId);
    if (survivor == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "survivor not in project");
    List<Integer> mergedIds = all.stream().filter(id -> id != survivorId.intValue()).toList();

    // survivor must be accepted if it will receive children (its own or a merged usage's)
    boolean survivorReceivesChildren = childCount(projectId, survivorId) > 0;
    for (int d : mergedIds) {
      NameUsage du = usages.findByIdInProject(projectId, d);
      if (du == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "usage not in project: " + d);
      if (childCount(projectId, d) > 0) survivorReceivesChildren = true;
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
      audit.record(projectId, userId, "name_usage", d, Operation.DELETE, du, null);
      usages.delete(projectId, d);
    }
    events.publishEvent(ValidationEvent.forUsage(projectId, survivorId));
    return new MergeResult(survivorId, mergedIds.size());
  }

  private int childCount(int projectId, int id) {
    Object v = merge.usageCounts(projectId, id).get("children");
    return v == null ? 0 : ((Number) v).intValue();
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
