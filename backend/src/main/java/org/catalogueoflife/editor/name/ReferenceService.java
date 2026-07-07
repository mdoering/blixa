package org.catalogueoflife.editor.name;

import java.util.List;
import org.catalogueoflife.editor.name.dto.CreateReferenceRequest;
import org.catalogueoflife.editor.name.dto.UpdateReferenceRequest;
import org.catalogueoflife.editor.project.ProjectService;
import org.catalogueoflife.editor.project.Role;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReferenceService {

  private final ReferenceMapper references;
  private final ProjectService projects;

  public ReferenceService(ReferenceMapper references, ProjectService projects) {
    this.references = references;
    this.projects = projects;
  }

  public List<Reference> list(long userId, long projectId, int limit, int offset) {
    projects.requireRole(userId, projectId);
    return references.findByProject(projectId, Pagination.clampLimit(limit), Pagination.clampOffset(offset));
  }

  public List<Reference> search(long userId, long projectId, String q, int limit, int offset) {
    projects.requireRole(userId, projectId);
    return references.search(projectId, q, Pagination.clampLimit(limit), Pagination.clampOffset(offset));
  }

  public Reference get(long userId, long projectId, long id) {
    projects.requireRole(userId, projectId);
    return requireInProject(projectId, id);
  }

  @Transactional
  public Reference create(long userId, long projectId, CreateReferenceRequest req) {
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
    references.insert(r);
    // the version column defaults to 0 in the DB (see V3__name_core.sql); reflect that
    // in the in-memory POJO returned to the caller without a redundant round-trip.
    r.setVersion(0);
    return r;
  }

  @Transactional
  public Reference update(long userId, long projectId, long id, UpdateReferenceRequest req) {
    requireEditor(userId, projectId);
    Reference r = requireInProject(projectId, id);
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
    return requireInProject(projectId, id);
  }

  @Transactional
  public void delete(long userId, long projectId, long id) {
    requireEditor(userId, projectId);
    if (references.delete(id, projectId) == 0) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "reference not found");
    }
  }

  private Reference requireInProject(long projectId, long id) {
    Reference r = references.findByIdInProject(id, projectId);
    if (r == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "reference not found");
    }
    return r;
  }

  private void requireEditor(long userId, long projectId) {
    String role = projects.requireRole(userId, projectId);
    if (!role.equals(Role.OWNER.dbValue()) && !role.equals(Role.EDITOR.dbValue())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "owner or editor required");
    }
  }
}
