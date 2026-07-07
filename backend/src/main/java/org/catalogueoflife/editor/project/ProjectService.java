package org.catalogueoflife.editor.project;

import java.util.List;
import org.catalogueoflife.editor.project.dto.CreateProjectRequest;
import org.catalogueoflife.editor.project.dto.UpdateProjectMetadataRequest;
import org.catalogueoflife.editor.user.AppUserMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProjectService {

  private final ProjectMapper projects;
  private final ProjectMemberMapper members;
  private final AppUserMapper users;

  public ProjectService(ProjectMapper projects, ProjectMemberMapper members, AppUserMapper users) {
    this.projects = projects;
    this.members = members;
    this.users = users;
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

  public java.util.List<org.catalogueoflife.editor.project.dto.MemberResponse> listMembers(long actorId, long projectId) {
    requireRole(actorId, projectId); // any member may read
    return members.findByProject(projectId).stream()
        .map(m -> {
          var u = users.findById(m.getUserId());
          return new org.catalogueoflife.editor.project.dto.MemberResponse(
              m.getUserId(), u == null ? null : u.getUsername(), m.getRole());
        })
        .toList();
  }

  @Transactional
  public void setMember(long actorId, long projectId, String username, String roleValue) {
    requireOwner(actorId, projectId);
    Role role = Role.fromDb(roleValue); // throws IllegalArgumentException -> 400 via handler below
    var target = users.findByUsername(username);
    if (target == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown user: " + username);
    }
    members.upsert(new ProjectMember(projectId, target.getId(), role.dbValue()));
  }

  @Transactional
  public void removeMember(long actorId, long projectId, long targetUserId) {
    requireOwner(actorId, projectId);
    long owners = members.findByProject(projectId).stream()
        .filter(m -> m.getRole().equals(Role.OWNER.dbValue())).count();
    String targetRole = members.findRole(projectId, targetUserId);
    if (Role.OWNER.dbValue().equals(targetRole) && owners <= 1) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "cannot remove the last owner");
    }
    members.delete(projectId, targetUserId);
  }

  private void requireOwner(long actorId, long projectId) {
    if (!Role.OWNER.dbValue().equals(requireRole(actorId, projectId))) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "owner required");
    }
  }
}
