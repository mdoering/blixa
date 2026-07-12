package org.catalogueoflife.editor.name;

import java.util.List;
import java.util.Map;
import org.catalogueoflife.editor.audit.AuditService;
import org.catalogueoflife.editor.audit.Operation;
import org.catalogueoflife.editor.name.dto.ContainerTitleFacet;
import org.catalogueoflife.editor.name.dto.CreateReferenceRequest;
import org.catalogueoflife.editor.name.dto.UpdateReferenceRequest;
import org.catalogueoflife.editor.project.ProjectService;
import org.catalogueoflife.editor.project.Role;
import org.catalogueoflife.editor.validation.IssueMapper;
import org.catalogueoflife.editor.validation.ValidationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
public class ReferenceService {

  private static final String ENTITY = "reference";

  private final ReferenceMapper references;
  private final IdSeqMapper idSeq;
  private final ProjectService projects;
  private final AuditService audit;
  private final ObjectMapper objectMapper;
  private final NameUsageMapper usages;
  private final ApplicationEventPublisher events;
  private final IssueMapper issues;
  private final PdfService pdfService;

  public ReferenceService(ReferenceMapper references, IdSeqMapper idSeq, ProjectService projects,
      AuditService audit, ObjectMapper objectMapper, NameUsageMapper usages, ApplicationEventPublisher events,
      IssueMapper issues, PdfService pdfService) {
    this.references = references;
    this.idSeq = idSeq;
    this.projects = projects;
    this.audit = audit;
    this.objectMapper = objectMapper;
    this.usages = usages;
    this.events = events;
    this.issues = issues;
    this.pdfService = pdfService;
  }

  public List<Reference> list(int userId, int projectId, int limit, int offset) {
    projects.requireRole(userId, projectId);
    return references.findByProject(projectId, Pagination.clampLimit(limit), Pagination.clampOffset(offset));
  }

  public List<Reference> search(int userId, int projectId, String q, int limit, int offset) {
    projects.requireRole(userId, projectId);
    return references.search(projectId, q, Pagination.clampLimit(limit), Pagination.clampOffset(offset));
  }

  public Reference get(int userId, int projectId, int id) {
    projects.requireRole(userId, projectId);
    return requireInProject(projectId, id);
  }

  @Transactional
  public Reference create(int userId, int projectId, CreateReferenceRequest req) {
    requireEditor(userId, projectId);
    Reference r = new Reference();
    r.setProjectId(projectId);
    r.setCitation(req.citation());
    r.setCitationManual(Boolean.TRUE.equals(req.citationManual()));
    r.setType(req.type());
    r.setAuthor(req.author());
    r.setEditor(req.editor());
    r.setTitle(req.title());
    r.setContainerTitle(req.containerTitle());
    r.setContainerTitleShort(req.containerTitleShort());
    r.setIssued(req.issued());
    r.setVolume(req.volume());
    r.setIssue(req.issue());
    r.setPage(req.page());
    r.setPublisher(req.publisher());
    r.setDoi(req.doi());
    r.setIsbn(req.isbn());
    r.setIssn(req.issn());
    r.setLink(req.link());
    r.setAccessed(req.accessed());
    r.setRemarks(req.remarks());
    r.setModifiedBy(userId);
    // allocate the next per-project id BEFORE inserting: reference has no DB identity column
    // any more (see V3__name_core.sql), so the app owns id generation via id_seq.
    r.setId(idSeq.allocate(projectId, ENTITY));
    references.insert(r);
    // the version column defaults to 0 in the DB (see V3__name_core.sql); reflect that
    // in the in-memory POJO returned to the caller without a redundant round-trip.
    r.setVersion(0);
    audit.record(projectId, userId, ENTITY, r.getId(), Operation.CREATE, null, r);
    return r;
  }

  @Transactional
  public Reference update(int userId, int projectId, int id, UpdateReferenceRequest req) {
    requireEditor(userId, projectId);
    Reference r = requireInProject(projectId, id);
    // Snapshot BEFORE mutating r's fields in place below: MyBatis's session-scoped local cache
    // would hand back this SAME cached instance from a second identical findByIdInProject call
    // (no intervening write to invalidate it yet), so re-fetching would alias rather than give an
    // independent "before" object. Converting to a Map here decouples the audit snapshot from r's
    // subsequent mutation.
    @SuppressWarnings("unchecked")
    Map<String, Object> before = objectMapper.convertValue(r, Map.class);
    r.setCitation(req.citation());
    r.setCitationManual(Boolean.TRUE.equals(req.citationManual()));
    r.setType(req.type());
    r.setAuthor(req.author());
    r.setEditor(req.editor());
    r.setTitle(req.title());
    r.setContainerTitle(req.containerTitle());
    r.setContainerTitleShort(req.containerTitleShort());
    r.setIssued(req.issued());
    r.setVolume(req.volume());
    r.setIssue(req.issue());
    r.setPage(req.page());
    r.setPublisher(req.publisher());
    r.setDoi(req.doi());
    r.setIsbn(req.isbn());
    r.setIssn(req.issn());
    r.setLink(req.link());
    r.setAccessed(req.accessed());
    r.setRemarks(req.remarks());
    r.setModifiedBy(userId);
    r.setVersion(req.version());
    int updated = references.update(r);
    if (updated == 0) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "conflict: stale version");
    }
    Reference after = requireInProject(projectId, id);
    audit.record(projectId, userId, ENTITY, id, Operation.UPDATE, before, after);
    // A reference edit (e.g. its year) can change what YearVsReferenceRule finds for every usage
    // that cites it as published_in -- one event per citing usage id, all published inside this
    // same transaction so ValidationTrigger's AFTER_COMMIT listener only fires if this update
    // actually commits (see validation/ValidationTrigger, NameUsageMapper.findIdsByPublishedInReference).
    for (int usageId : usages.findIdsByPublishedInReference(projectId, id)) {
      events.publishEvent(ValidationEvent.forUsage(projectId, usageId));
    }
    return after;
  }

  @Transactional
  public void delete(int userId, int projectId, int id) {
    requireEditor(userId, projectId);
    Reference before = requireInProject(projectId, id);
    // Capture BEFORE the delete: ON DELETE SET NULL (see V3__name_core.sql) means every citing
    // usage's published_in_reference_id becomes NULL the instant the reference row is gone, so
    // querying by refId afterwards would find nothing -- same call update() uses to find its
    // ValidationEvent targets.
    List<Integer> citingUsageIds = usages.findIdsByPublishedInReference(projectId, id);
    // reference_id[] (taxonomic references) has NO array FK (see V3__name_core.sql's comment), so
    // unlike published_in_reference_id above there is no DB-level SET NULL to clean up dangling
    // ids after the delete below -- capture the array-citing usages BEFORE the delete too, same as
    // citingUsageIds, so both lists are queryable while the reference row still exists.
    List<Integer> arrayCitingUsageIds = usages.findUsageIdsCitingReference(projectId, id);
    if (references.delete(projectId, id) == 0) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "reference not found");
    }
    // Strip the now-deleted id out of every citing usage's reference_id[] -- without this a ColDP
    // export would keep emitting referenceID for a reference that no longer exists (see
    // NameUsageMapper.removeReferenceIdFromAll's javadoc for why this doesn't bump version).
    if (!arrayCitingUsageIds.isEmpty()) {
      usages.removeReferenceIdFromAll(projectId, id, userId);
    }
    // entity_id is polymorphic (no cascade FK to reference): clean up this reference's own issue
    // rows now, or they'd reference a nonexistent entity forever (see validation/IssueMapper.deleteByEntity).
    issues.deleteByEntity(projectId, ENTITY, id);
    audit.record(projectId, userId, ENTITY, id, Operation.DELETE, before, null);
    // The SET NULL can clear year_vs_reference and trip missing_published_in for each citing usage
    // -- mirrors update()'s per-citing-usage ValidationEvent, published from inside this same
    // @Transactional method so ValidationTrigger's AFTER_COMMIT listener only fires once this
    // delete actually commits.
    for (int usageId : citingUsageIds) {
      events.publishEvent(ValidationEvent.forUsage(projectId, usageId));
    }
    // Same AFTER_COMMIT-safe publish for the array-citing usages, deduplicated against
    // citingUsageIds above so a usage that both published_in's AND taxonomically cites the same
    // deleted reference doesn't get two redundant revalidations.
    for (int usageId : arrayCitingUsageIds) {
      if (!citingUsageIds.contains(usageId)) {
        events.publishEvent(ValidationEvent.forUsage(projectId, usageId));
      }
    }
  }

  // Attaches (or replaces) the reference's hosted PDF. `link` is never touched -- pdf is a fully
  // separate column (see ReferenceMapper.updatePdf's javadoc) -- so this never risks clobbering a
  // user-set citable link. A replace's old file is deleted only AFTER this transaction actually
  // COMMITS (see deleteAfterCommit): deleting it right after the CAS write, while still inside the
  // transaction, would leave a DB row pointing at a now-missing file if something later in this same
  // method (audit.record, the ValidationEvent publishes) throws and the transaction rolls back --
  // the CAS write itself would be undone, reverting `pdf` back to oldPdf, but the file would already
  // be gone. The freshly-stored file, by contrast, is deleted immediately (not deferred) when the
  // write loses the CAS race: nothing in the DB ever pointed at it, so there is no rollback to race
  // against.
  @Transactional
  public Reference attachPdf(int userId, int projectId, int id, MultipartFile file) {
    requireEditor(userId, projectId);
    Reference before = requireInProject(projectId, id);
    String oldPdf = before.getPdf();
    String filename = pdfService.store(projectId, id, file);
    int updated = references.updatePdf(projectId, id, filename, userId, before.getVersion());
    if (updated == 0) {
      pdfService.delete(filename);
      throw new ResponseStatusException(HttpStatus.CONFLICT, "conflict: stale version");
    }
    if (oldPdf != null) {
      deleteAfterCommit(oldPdf);
    }
    Reference after = requireInProject(projectId, id);
    audit.record(projectId, userId, ENTITY, id, Operation.UPDATE, before, after);
    // Mirrors update()'s per-citing-usage revalidation publish: no validation rule reads pdf today,
    // but keeps every reference write path notifying the same way rather than special-casing this
    // one as silent.
    for (int usageId : usages.findIdsByPublishedInReference(projectId, id)) {
      events.publishEvent(ValidationEvent.forUsage(projectId, usageId));
    }
    return after;
  }

  // Clears the reference's hosted PDF, if any. A no-op (no version bump, no audit row) when the
  // reference has no pdf to begin with -- DELETE is idempotent, and there is no change to record.
  @Transactional
  public Reference removePdf(int userId, int projectId, int id) {
    requireEditor(userId, projectId);
    Reference before = requireInProject(projectId, id);
    String oldPdf = before.getPdf();
    if (oldPdf == null) {
      return before;
    }
    int updated = references.updatePdf(projectId, id, null, userId, before.getVersion());
    if (updated == 0) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "conflict: stale version");
    }
    deleteAfterCommit(oldPdf);
    Reference after = requireInProject(projectId, id);
    audit.record(projectId, userId, ENTITY, id, Operation.UPDATE, before, after);
    for (int usageId : usages.findIdsByPublishedInReference(projectId, id)) {
      events.publishEvent(ValidationEvent.forUsage(projectId, usageId));
    }
    return after;
  }

  // Deletes `filename` from disk only once this method's enclosing transaction commits, never on a
  // rollback -- see attachPdf's javadoc for why deleting eagerly (while a DB write it depends on
  // could still be undone) risks a committed `pdf` column pointing at a file that is already gone.
  // Falls back to an immediate delete if there is, for whatever reason, no active transaction to
  // hook -- both attachPdf and removePdf are @Transactional so that fallback is not expected to be
  // exercised in practice, but a plain delete is still strictly safer than silently dropping it.
  private void deleteAfterCommit(String filename) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      pdfService.delete(filename);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override
      public void afterCommit() {
        pdfService.delete(filename);
      }
    });
  }

  // Facet of distinct container_title (journal name) values for the reconcile UI -- any project
  // member may read it, same gating as list/search/get.
  public List<ContainerTitleFacet> containerTitleFacet(int userId, int projectId) {
    projects.requireRole(userId, projectId);
    return references.containerTitleFacet(projectId);
  }

  // Normalizes every reference whose container_title is one of `variants` to `canonical` -- a bulk
  // field reconciliation (OpenRefine-style), not a record merge, so this deliberately does NOT go
  // through update()'s per-row CAS/audit/ValidationEvent machinery (see ReferenceMapper.
  // mergeContainerTitle's javadoc). Owner/editor gated, same as create/update/delete.
  @Transactional
  public int mergeContainerTitle(int userId, int projectId, String canonical, List<String> variants) {
    requireEditor(userId, projectId);
    if (canonical == null || canonical.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "canonical must not be blank");
    }
    if (variants == null || variants.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "variants must not be empty");
    }
    return references.mergeContainerTitle(projectId, canonical, variants);
  }

  private Reference requireInProject(int projectId, int id) {
    Reference r = references.findByIdInProject(projectId, id);
    if (r == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "reference not found");
    }
    return r;
  }

  private void requireEditor(int userId, int projectId) {
    String role = projects.requireRole(userId, projectId);
    if (!role.equals(Role.OWNER.dbValue()) && !role.equals(Role.EDITOR.dbValue())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "owner or editor required");
    }
  }
}
