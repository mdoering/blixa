package org.catalogueoflife.editor.child;

import java.util.List;
import org.catalogueoflife.editor.audit.AuditService;
import org.catalogueoflife.editor.audit.Operation;
import org.catalogueoflife.editor.child.dto.TypeMaterialRequest;
import org.catalogueoflife.editor.child.dto.TypeMaterialResponse;
import org.catalogueoflife.editor.name.IdSeqMapper;
import org.catalogueoflife.editor.name.NameUsageMapper;
import org.catalogueoflife.editor.project.ProjectService;
import org.catalogueoflife.editor.project.Role;
import org.catalogueoflife.editor.validation.ValidationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

// Type material (holotype, syntype, ...) for a name usage. Applies to any usage. Follows the shared
// child-entity pattern (see the child-entities spec).
@Service
public class TypeMaterialService {

  private static final String ENTITY = "type_material";

  private final TypeMaterialMapper mapper;
  private final NameUsageMapper usages;
  private final IdSeqMapper idSeq;
  private final ProjectService projects;
  private final AuditService audit;
  private final ApplicationEventPublisher events;

  public TypeMaterialService(TypeMaterialMapper mapper, NameUsageMapper usages, IdSeqMapper idSeq,
      ProjectService projects, AuditService audit, ApplicationEventPublisher events) {
    this.mapper = mapper;
    this.usages = usages;
    this.idSeq = idSeq;
    this.projects = projects;
    this.audit = audit;
    this.events = events;
  }

  public List<TypeMaterialResponse> list(int userId, int projectId, int usageId) {
    projects.requireRole(userId, projectId);
    requireUsage(projectId, usageId);
    return mapper.findByUsage(projectId, usageId);
  }

  @Transactional
  public TypeMaterialResponse create(int userId, int projectId, int usageId, TypeMaterialRequest req) {
    requireEditor(userId, projectId);
    requireUsage(projectId, usageId);
    int id = idSeq.allocate(projectId, ENTITY);
    mapper.insert(projectId, id, usageId, req, userId);
    TypeMaterialResponse after = mapper.findById(projectId, id);
    audit.record(projectId, userId, ENTITY, id, Operation.CREATE, null, after);
    events.publishEvent(ValidationEvent.forUsage(projectId, usageId));
    return after;
  }

  @Transactional
  public TypeMaterialResponse update(int userId, int projectId, int usageId, int id,
      TypeMaterialRequest req) {
    requireEditor(userId, projectId);
    TypeMaterialResponse before = requireInProject(projectId, id);
    if (mapper.update(projectId, id, req, userId) == 0) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "conflict: stale version");
    }
    TypeMaterialResponse after = mapper.findById(projectId, id);
    audit.record(projectId, userId, ENTITY, id, Operation.UPDATE, before, after);
    events.publishEvent(ValidationEvent.forUsage(projectId, usageId));
    return after;
  }

  @Transactional
  public void delete(int userId, int projectId, int usageId, int id) {
    requireEditor(userId, projectId);
    TypeMaterialResponse before = requireInProject(projectId, id);
    mapper.delete(projectId, id);
    audit.record(projectId, userId, ENTITY, id, Operation.DELETE, before, null);
    events.publishEvent(ValidationEvent.forUsage(projectId, usageId));
  }

  private void requireUsage(int projectId, int usageId) {
    if (usages.findByIdInProject(projectId, usageId) == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "name usage not found");
    }
  }

  private TypeMaterialResponse requireInProject(int projectId, int id) {
    TypeMaterialResponse r = mapper.findById(projectId, id);
    if (r == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "type material not found");
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
