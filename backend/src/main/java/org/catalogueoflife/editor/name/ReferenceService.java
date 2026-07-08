package org.catalogueoflife.editor.name;

import java.util.List;
import java.util.Map;
import org.catalogueoflife.editor.audit.AuditService;
import org.catalogueoflife.editor.audit.Operation;
import org.catalogueoflife.editor.name.dto.CreateReferenceRequest;
import org.catalogueoflife.editor.name.dto.UpdateReferenceRequest;
import org.catalogueoflife.editor.project.ProjectService;
import org.catalogueoflife.editor.project.Role;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

  public ReferenceService(ReferenceMapper references, IdSeqMapper idSeq, ProjectService projects,
      AuditService audit, ObjectMapper objectMapper) {
    this.references = references;
    this.idSeq = idSeq;
    this.projects = projects;
    this.audit = audit;
    this.objectMapper = objectMapper;
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
    r.setType(req.type());
    r.setAuthor(req.author());
    r.setEditor(req.editor());
    r.setTitle(req.title());
    r.setContainerTitle(req.containerTitle());
    r.setIssued(req.issued());
    r.setVolume(req.volume());
    r.setIssue(req.issue());
    r.setPage(req.page());
    r.setPublisher(req.publisher());
    r.setDoi(req.doi());
    r.setIsbn(req.isbn());
    r.setIssn(req.issn());
    r.setLink(req.link());
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
    r.setType(req.type());
    r.setAuthor(req.author());
    r.setEditor(req.editor());
    r.setTitle(req.title());
    r.setContainerTitle(req.containerTitle());
    r.setIssued(req.issued());
    r.setVolume(req.volume());
    r.setIssue(req.issue());
    r.setPage(req.page());
    r.setPublisher(req.publisher());
    r.setDoi(req.doi());
    r.setIsbn(req.isbn());
    r.setIssn(req.issn());
    r.setLink(req.link());
    r.setRemarks(req.remarks());
    r.setModifiedBy(userId);
    r.setVersion(req.version());
    int updated = references.update(r);
    if (updated == 0) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "conflict: stale version");
    }
    Reference after = requireInProject(projectId, id);
    audit.record(projectId, userId, ENTITY, id, Operation.UPDATE, before, after);
    return after;
  }

  @Transactional
  public void delete(int userId, int projectId, int id) {
    requireEditor(userId, projectId);
    Reference before = requireInProject(projectId, id);
    if (references.delete(projectId, id) == 0) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "reference not found");
    }
    audit.record(projectId, userId, ENTITY, id, Operation.DELETE, before, null);
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
