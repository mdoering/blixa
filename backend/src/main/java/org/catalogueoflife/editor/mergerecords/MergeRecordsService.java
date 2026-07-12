package org.catalogueoflife.editor.mergerecords;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.catalogueoflife.editor.audit.AuditService;
import org.catalogueoflife.editor.audit.Operation;
import org.catalogueoflife.editor.mergerecords.dto.MergeResult;
import org.catalogueoflife.editor.mergerecords.dto.ReferenceMergeCandidate;
import org.catalogueoflife.editor.mergerecords.dto.UsageMergeCandidate;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.name.NameUsageMapper;
import org.catalogueoflife.editor.name.Reference;
import org.catalogueoflife.editor.name.ReferenceMapper;
import org.catalogueoflife.editor.name.Status;
import org.catalogueoflife.editor.project.ProjectService;
import org.catalogueoflife.editor.project.Role;
import org.catalogueoflife.editor.tree.TreeMapper;
import org.catalogueoflife.editor.validation.IssueMapper;
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
  private final ReferenceMapper references;
  private final ProjectService projects;
  private final TreeMapper tree;
  private final AuditService audit;
  private final ApplicationEventPublisher events;
  private final IssueMapper issues;

  public MergeRecordsService(MergeRecordsMapper merge, NameUsageMapper usages, ReferenceMapper references,
      ProjectService projects, TreeMapper tree, AuditService audit, ApplicationEventPublisher events,
      IssueMapper issues) {
    this.merge = merge;
    this.usages = usages;
    this.references = references;
    this.projects = projects;
    this.tree = tree;
    this.audit = audit;
    this.events = events;
    this.issues = issues;
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

    // survivor must be accepted if it will receive children (its own or a merged usage's), or if
    // a merged usage will hand it synonyms (no synonym chaining: the survivor's OWN synonyms don't
    // matter here -- a synonym can't itself have synonyms, so only what a MERGED usage brings along
    // can turn the survivor into an accepted-of-synonyms).
    boolean survivorReceivesChildren = childCount(projectId, survivorId) > 0;
    boolean survivorReceivesSynonyms = false;
    for (int d : mergedIds) {
      NameUsage du = usages.findByIdInProject(projectId, d);
      if (du == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "usage not in project: " + d);
      if (childCount(projectId, d) > 0) survivorReceivesChildren = true;
      if (synonymCount(projectId, d) > 0) survivorReceivesSynonyms = true;
    }
    if ((survivorReceivesChildren || survivorReceivesSynonyms) && survivor.getStatus() != Status.ACCEPTED) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "survivor must be an accepted name to receive children or synonyms");
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
      // entity_id is polymorphic (no cascade FK to name_usage): clean up d's own issue rows now,
      // or they'd reference a nonexistent entity forever (see validation/IssueMapper.deleteByEntity,
      // mirrors NameUsageService.delete's single-usage cleanup).
      issues.deleteByEntity(projectId, "name_usage", d);
      usages.delete(projectId, d);
    }

    // Symmetric to the accepted-parent guard above: after all repoints, an ACCEPTED survivor must
    // not have been turned into a synonym by absorbing a merged synonym's synonym-of-X link. Self-
    // links (X == survivor) and links to other merged ids already collapsed/dropped in the loop; a
    // leftover row means the survivor now points at a DIFFERENT surviving accepted -> accepted AND
    // synonym at once (inconsistent ColDP). Reject (rolls back the whole @Transactional merge).
    if (survivor.getStatus() == Status.ACCEPTED && merge.acceptedOfCount(projectId, survivorId) > 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "cannot merge a synonym into an accepted survivor; resolve its accepted/synonym status first");
    }

    events.publishEvent(ValidationEvent.forUsage(projectId, survivorId));
    return new MergeResult(survivorId, mergedIds.size());
  }

  public List<ReferenceMergeCandidate> previewReferences(int userId, int projectId, List<Integer> ids) {
    projects.requireRole(userId, projectId); // any member may preview
    List<Integer> distinct = requireAtLeastTwo(ids);
    List<ReferenceMergeCandidate> out = new ArrayList<>();
    for (int id : distinct.stream().sorted().toList()) {  // ascending id (oldest first)
      Reference r = references.findByIdInProject(projectId, id);
      if (r == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reference not in project: " + id);
      Map<String, Object> raw = merge.referenceCounts(projectId, id);
      Map<String, Integer> counts = new LinkedHashMap<>();
      raw.forEach((k, v) -> counts.put(k, ((Number) v).intValue()));
      out.add(new ReferenceMergeCandidate(r.getId(), r.getAlternativeId(), r.getCitation(), r.getDoi(), counts));
    }
    return out;
  }

  // Destructive apply: repoints every column that cites each merged (duplicate) reference onto the
  // survivor, then deletes the duplicates -- one transaction (no tree lock needed, references
  // aren't tree nodes). Every repoint runs BEFORE the delete: name_usage.published_in_reference_id
  // is ON DELETE SET NULL, so deleting first would silently null out citations; the 6 child
  // reference_id columns + the reference_id[] array have no FK at all, so a missed repoint there
  // would leave a dangling pointer to a deleted row.
  @Transactional
  public MergeResult mergeReferences(int userId, int projectId, Integer survivorId, List<Integer> ids) {
    requireEditor(userId, projectId);
    List<Integer> all = requireAtLeastTwo(ids);
    if (survivorId == null || !all.contains(survivorId)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "survivorId must be one of the selected records");
    }
    if (references.findByIdInProject(projectId, survivorId) == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "survivor not in project");
    }
    List<Integer> mergedIds = all.stream().filter(id -> id != survivorId.intValue()).toList();

    // Capture every usage that cites a to-be-merged duplicate BEFORE repointing it: once repointed
    // (or deleted) the duplicate's id no longer identifies these citers, mirrors
    // ReferenceService.delete's citingUsageIds/arrayCitingUsageIds capture-before-mutate discipline.
    Set<Integer> citerIds = new LinkedHashSet<>();
    for (int d : mergedIds) {
      Reference ref = references.findByIdInProject(projectId, d);
      if (ref == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reference not in project: " + d);
      citerIds.addAll(usages.findIdsByPublishedInReference(projectId, d));
      citerIds.addAll(usages.findUsageIdsCitingReference(projectId, d));
      merge.repointPublishedIn(projectId, d, survivorId);
      merge.repointReferenceArray(projectId, d, survivorId);
      merge.dedupReferenceArray(projectId, survivorId);
      merge.repointRefNameRelation(projectId, d, survivorId);
      merge.repointRefTypeMaterial(projectId, d, survivorId);
      merge.repointRefVernacular(projectId, d, survivorId);
      merge.repointRefDistribution(projectId, d, survivorId);
      merge.repointRefEstimate(projectId, d, survivorId);
      merge.repointRefProperty(projectId, d, survivorId);
      audit.record(projectId, userId, "reference", d, Operation.DELETE, ref, null);
      // entity_id is polymorphic (no cascade FK to reference): clean up d's own issue rows now,
      // or they'd reference a nonexistent entity forever (mirrors ReferenceService.delete /
      // mergeUsages's own issues.deleteByEntity call above).
      issues.deleteByEntity(projectId, "reference", d);
      merge.deleteReference(projectId, d);
    }
    // Repointing onto the survivor reference can change what YearVsReferenceRule /
    // MissingPublishedInRule find for every former citer of a deleted duplicate -- one event per
    // citer id, published from inside this same @Transactional method so ValidationTrigger's
    // AFTER_COMMIT listener only fires once this merge actually commits (mirrors
    // ReferenceService.update/delete). Note: these are usage ids, NOT survivorId (a reference id) --
    // publishing forUsage(survivorId) here would be a bug.
    for (int usageId : citerIds) {
      events.publishEvent(ValidationEvent.forUsage(projectId, usageId));
    }
    return new MergeResult(survivorId, mergedIds.size());
  }

  private int childCount(int projectId, int id) {
    Object v = merge.usageCounts(projectId, id).get("children");
    return v == null ? 0 : ((Number) v).intValue();
  }

  private int synonymCount(int projectId, int id) {
    Object v = merge.usageCounts(projectId, id).get("synonyms");
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
