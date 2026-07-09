package org.catalogueoflife.editor.child;

import java.util.List;
import org.catalogueoflife.editor.audit.AuditService;
import org.catalogueoflife.editor.audit.Operation;
import org.catalogueoflife.editor.name.IdSeqMapper;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.name.NameUsageMapper;
import org.catalogueoflife.editor.name.Status;
import org.catalogueoflife.editor.project.ProjectService;
import org.catalogueoflife.editor.project.Role;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

// Shared CRUD skeleton for the taxon-level child entities (vernacular, distribution, media,
// estimate, property). Each of these belongs only to an ACCEPTED usage; create is guarded
// accordingly and demote drops them (see NameUsageService.writeTaxonInfo). Concrete subclasses
// supply the mapper calls and the entity name. See the child-entities spec.
public abstract class AbstractChildEntityService<REQ, RES> {

  protected final NameUsageMapper usages;
  protected final IdSeqMapper idSeq;
  protected final ProjectService projects;
  protected final AuditService audit;
  protected final org.springframework.context.ApplicationEventPublisher events;

  protected AbstractChildEntityService(NameUsageMapper usages, IdSeqMapper idSeq,
      ProjectService projects, AuditService audit,
      org.springframework.context.ApplicationEventPublisher events) {
    this.usages = usages;
    this.idSeq = idSeq;
    this.projects = projects;
    this.audit = audit;
    this.events = events;
  }

  // Per-entity hooks.
  protected abstract String entity();

  protected abstract List<RES> findByUsage(int projectId, int usageId);

  protected abstract RES findById(int projectId, int id);

  protected abstract void doInsert(int projectId, int id, int usageId, REQ req, int userId);

  protected abstract int doUpdate(int projectId, int id, REQ req, int userId);

  protected abstract int doDelete(int projectId, int id);

  public List<RES> list(int userId, int projectId, int usageId) {
    projects.requireRole(userId, projectId);
    requireUsage(projectId, usageId);
    return findByUsage(projectId, usageId);
  }

  @org.springframework.transaction.annotation.Transactional
  public RES create(int userId, int projectId, int usageId, REQ req) {
    requireEditor(userId, projectId);
    requireAcceptedUsage(projectId, usageId);
    int id = idSeq.allocate(projectId, entity());
    doInsert(projectId, id, usageId, req, userId);
    RES after = findById(projectId, id);
    audit.record(projectId, userId, entity(), id, Operation.CREATE, null, after);
    events.publishEvent(org.catalogueoflife.editor.validation.ValidationEvent.forUsage(projectId, usageId));
    return after;
  }

  @org.springframework.transaction.annotation.Transactional
  public RES update(int userId, int projectId, int usageId, int id, REQ req) {
    requireEditor(userId, projectId);
    RES before = requireInProject(projectId, id);
    if (doUpdate(projectId, id, req, userId) == 0) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "conflict: stale version");
    }
    RES after = findById(projectId, id);
    audit.record(projectId, userId, entity(), id, Operation.UPDATE, before, after);
    events.publishEvent(org.catalogueoflife.editor.validation.ValidationEvent.forUsage(projectId, usageId));
    return after;
  }

  @org.springframework.transaction.annotation.Transactional
  public void delete(int userId, int projectId, int usageId, int id) {
    requireEditor(userId, projectId);
    RES before = requireInProject(projectId, id);
    doDelete(projectId, id);
    audit.record(projectId, userId, entity(), id, Operation.DELETE, before, null);
    events.publishEvent(org.catalogueoflife.editor.validation.ValidationEvent.forUsage(projectId, usageId));
  }

  protected RES requireInProject(int projectId, int id) {
    RES r = findById(projectId, id);
    if (r == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, entity() + " not found");
    }
    return r;
  }

  protected NameUsage requireUsage(int projectId, int usageId) {
    NameUsage u = usages.findByIdInProject(projectId, usageId);
    if (u == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "name usage not found");
    }
    return u;
  }

  protected void requireAcceptedUsage(int projectId, int usageId) {
    if (requireUsage(projectId, usageId).getStatus() != Status.ACCEPTED) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          entity() + " applies only to accepted taxa");
    }
  }

  protected void requireEditor(int userId, int projectId) {
    String role = projects.requireRole(userId, projectId);
    if (!role.equals(Role.OWNER.dbValue()) && !role.equals(Role.EDITOR.dbValue())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "owner or editor required");
    }
  }
}
