package org.catalogueoflife.editor.project;

import java.util.List;
import java.util.Locale;
import life.catalogue.api.vocab.License;
import org.catalogueoflife.editor.project.dto.CreateProjectRequest;
import org.catalogueoflife.editor.project.dto.UpdateProjectMetadataRequest;
import org.catalogueoflife.editor.user.AppUserMapper;
import org.gbif.nameparser.api.NomCode;
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
  public Project create(int userId, CreateProjectRequest req) {
    Project p = new Project();
    p.setTitle(req.title());
    p.setNomCode(parseNomCode(req.nomCode()));
    projects.insert(p);
    members.upsert(new ProjectMember(p.getId(), userId, Role.OWNER.dbValue()));
    return p;
  }

  public List<Project> listForUser(int userId) {
    return projects.findByMember(userId);
  }

  public String requireRole(int userId, int projectId) {
    String role = members.findRole(projectId, userId);
    if (role == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "project not found");
    }
    return role;
  }

  public Project requireVisible(int userId, int projectId) {
    requireRole(userId, projectId);
    return projects.findById(projectId);
  }

  @Transactional
  public Project updateMetadata(int userId, int projectId, UpdateProjectMetadataRequest req) {
    String role = requireRole(userId, projectId);
    if (!role.equals(Role.OWNER.dbValue()) && !role.equals(Role.EDITOR.dbValue())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "owner or editor required");
    }
    NomCode nomCode = parseNomCode(req.nomCode());
    License license = Licenses.parse(req.license());
    Project p = projects.findById(projectId);
    p.setTitle(req.title());
    p.setAlias(req.alias());
    p.setDescription(req.description());
    p.setNomCode(nomCode);
    p.setLicense(license);
    p.setGeographicScope(req.geographicScope());
    p.setTaxonomicScope(req.taxonomicScope());
    // Field is omitted (null) on most metadata saves -- don't let a full-replace null it out;
    // fall back to whatever is already stored (loaded above via findById).
    if (req.gbifOccurrenceLayer() != null) {
      p.setGbifOccurrenceLayer(req.gbifOccurrenceLayer());
    }
    projects.updateMetadata(p);
    return p;
  }

  public java.util.List<org.catalogueoflife.editor.project.dto.MemberResponse> listMembers(int actorId, int projectId) {
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
  public void setMember(int actorId, int projectId, String username, String roleValue) {
    requireOwner(actorId, projectId);
    Role role = Role.fromDb(roleValue); // throws IllegalArgumentException -> 400 via handler below
    var target = users.findByUsername(username);
    if (target == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown user: " + username);
    }
    String currentRole = members.findRole(projectId, target.getId());
    if (Role.OWNER.dbValue().equals(currentRole) && !Role.OWNER.dbValue().equals(role.dbValue())
        && countOwners(projectId) <= 1) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "cannot demote the last owner");
    }
    members.upsert(new ProjectMember(projectId, target.getId(), role.dbValue()));
  }

  @Transactional
  public void removeMember(int actorId, int projectId, int targetUserId) {
    requireOwner(actorId, projectId);
    String targetRole = members.findRole(projectId, targetUserId);
    if (Role.OWNER.dbValue().equals(targetRole) && countOwners(projectId) <= 1) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "cannot remove the last owner");
    }
    members.delete(projectId, targetUserId);
  }

  private long countOwners(int projectId) {
    return members.findByProject(projectId).stream()
        .filter(m -> m.getRole().equals(Role.OWNER.dbValue())).count();
  }

  private void requireOwner(int actorId, int projectId) {
    if (!Role.OWNER.dbValue().equals(requireRole(actorId, projectId))) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "owner required");
    }
  }

  // Frontend sends lowercase strings (e.g. "zoological"); tolerantly upper-case before matching
  // the enum constant name, rejecting anything unrecognized with a 400 rather than an ISE.
  private static NomCode parseNomCode(String nomCode) {
    if (nomCode == null || nomCode.isBlank()) {
      return null;
    }
    try {
      return NomCode.valueOf(nomCode.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid nomCode: " + nomCode);
    }
  }
}
