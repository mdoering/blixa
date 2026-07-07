package org.catalogueoflife.editor.project;

import java.util.List;
import org.catalogueoflife.editor.project.dto.CreateProjectRequest;
import org.catalogueoflife.editor.project.dto.UpdateProjectMetadataRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProjectService {

  private final ProjectMapper projects;
  private final ProjectMemberMapper members;

  public ProjectService(ProjectMapper projects, ProjectMemberMapper members) {
    this.projects = projects;
    this.members = members;
  }

  @Transactional
  public Project create(long userId, CreateProjectRequest req) {
    if (projects.findBySlug(req.slug()) != null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "slug already used");
    }
    Project p = new Project();
    p.setSlug(req.slug());
    p.setTitle(req.title());
    p.setNomCode(req.nomCode());
    projects.insert(p);
    members.upsert(new ProjectMember(p.getId(), userId, Role.OWNER.dbValue()));
    return p;
  }

  public List<Project> listForUser(long userId) {
    return projects.findByMember(userId);
  }

  public String requireRole(long userId, long projectId) {
    String role = members.findRole(projectId, userId);
    if (role == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "project not found");
    }
    return role;
  }

  public Project requireVisible(long userId, long projectId) {
    requireRole(userId, projectId);
    return projects.findById(projectId);
  }

  @Transactional
  public Project updateMetadata(long userId, long projectId, UpdateProjectMetadataRequest req) {
    String role = requireRole(userId, projectId);
    if (!role.equals(Role.OWNER.dbValue()) && !role.equals(Role.EDITOR.dbValue())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "owner or editor required");
    }
    Project p = projects.findById(projectId);
    p.setTitle(req.title());
    p.setAlias(req.alias());
    p.setDescription(req.description());
    p.setNomCode(req.nomCode());
    p.setLicense(req.license());
    p.setVersion(req.version());
    p.setIssued(req.issued());
    p.setGeographicScope(req.geographicScope());
    p.setTaxonomicScope(req.taxonomicScope());
    p.setDoi(req.doi());
    projects.updateMetadata(p);
    return p;
  }
}
