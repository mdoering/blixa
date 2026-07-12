package org.catalogueoflife.editor.project;

import java.util.List;
import java.util.Locale;
import life.catalogue.api.vocab.License;
import life.catalogue.common.csl.CslFormatter;
import org.catalogueoflife.editor.name.Reference;
import org.catalogueoflife.editor.name.ReferenceCitationService;
import org.catalogueoflife.editor.name.ReferenceMapper;
import org.catalogueoflife.editor.project.dto.CreateProjectRequest;
import org.catalogueoflife.editor.project.dto.UpdateProjectMetadataRequest;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserMapper;
import org.catalogueoflife.editor.user.AppUserService;
import org.gbif.nameparser.api.NomCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProjectService {

  private final ProjectMapper projects;
  private final ProjectMemberMapper members;
  private final AppUserMapper users;
  private final AppUserService userService;
  private final ReferenceMapper references;
  private final ReferenceCitationService citationService;
  // ORCID is "configured" iff the client-id is not the sentinel default (see ConfigController).
  private final boolean orcidConfigured;

  public ProjectService(ProjectMapper projects, ProjectMemberMapper members, AppUserMapper users,
      AppUserService userService, ReferenceMapper references, ReferenceCitationService citationService,
      @Value("${spring.security.oauth2.client.registration.orcid.client-id:unconfigured}") String orcidClientId) {
    this.projects = projects;
    this.members = members;
    this.users = users;
    this.userService = userService;
    this.references = references;
    this.citationService = citationService;
    this.orcidConfigured = !"unconfigured".equals(orcidClientId);
  }

  @Transactional
  public Project create(int userId, CreateProjectRequest req) {
    Project p = new Project();
    p.setTitle(req.title());
    p.setAlias(req.alias());
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

  // Owner-only project deletion. Every project-scoped FK is ON DELETE CASCADE (V2+), so deleting the
  // project row drops all of its data (name-usages, references, runs, members, audit, ...).
  @Transactional
  public void delete(int userId, int projectId) {
    String role = requireRole(userId, projectId);
    if (!role.equals(Role.OWNER.dbValue())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "owner required to delete a project");
    }
    projects.delete(projectId);
  }

  @Transactional
  public void setPublic(int userId, int projectId, boolean isPublic) {
    String role = requireRole(userId, projectId);
    if (!role.equals(Role.OWNER.dbValue())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "owner required");
    }
    if (isPublic) {
      Project p = projects.findById(projectId);
      if (p.getLicense() == null) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "a license is required to make a project public");
      }
    }
    projects.updatePublic(projectId, isPublic);
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
    String oldCslStyle = p.getCslStyle();
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
    // Same null-safe carry-over as gbifOccurrenceLayer above: identifierScopes is only sent by
    // the Project settings save (metadata saves that don't touch it omit the field entirely), so
    // a null here must not wipe out whatever is already stored.
    if (req.identifierScopes() != null) {
      p.setIdentifierScopes(req.identifierScopes());
    }
    // Same null-safe carry-over again: a non-null cslStyle must be one of CslFormatter.STYLE
    // (case-insensitive) -- reject anything else with 400 rather than silently storing a value
    // ReferenceCitationService would then quietly fall back to APA for.
    if (req.cslStyle() != null) {
      p.setCslStyle(validateCslStyle(req.cslStyle()));
    }
    projects.updateMetadata(p);
    // A style change invalidates every previously-GENERATED citation in the project -- regenerate
    // them all in the new style so the UI/ColDP export don't keep showing stale text under a
    // project that now says e.g. "harvard". Manual citations (Reference.citationManual) are left
    // untouched: they were never derived from cslStyle to begin with.
    if (!p.getCslStyle().equals(oldCslStyle)) {
      regenerateCitations(projectId, p.getCslStyle());
    }
    return p;
  }

  // Internal accessor for ReferenceService.applyCitation: the caller has already passed an
  // editor-role check (requireEditor) before creating/updating a reference, so this intentionally
  // skips a second requireRole/ACL round-trip.
  public String getCslStyle(int projectId) {
    return projects.findById(projectId).getCslStyle();
  }

  // Re-renders and persists the citation of every non-manual reference in `projectId` -- called
  // only when updateMetadata just changed cslStyle. Deliberately bypasses ReferenceMapper.update's
  // per-row CAS/audit/ValidationEvent machinery (via updateCitation, a narrow write) the same way
  // ReferenceService.mergeContainerTitle does for its own bulk field rewrite: a citation regenerated
  // because the PROJECT'S style changed is system-driven maintenance, not a per-reference edit a
  // concurrent editor should see as a stale-version conflict.
  // NOTE: could be async for very large projects -- synchronous is fine for now.
  private void regenerateCitations(int projectId, String cslStyle) {
    for (Reference ref : references.findAllByProject(projectId)) {
      if (!ref.isCitationManual()) {
        references.updateCitation(projectId, ref.getId(), citationService.render(ref, cslStyle));
      }
    }
  }

  // Tolerantly upper-cases + resolves `raw` against CslFormatter.STYLE, mirroring parseNomCode's
  // shape below; rejects anything unrecognized with a 400 rather than silently storing garbage.
  // Stored lower-case to match the wire form (see ProjectResponse.of / Project.cslStyle's javadoc).
  private static String validateCslStyle(String raw) {
    try {
      return CslFormatter.STYLE.valueOf(raw.trim().toUpperCase(Locale.ROOT)).name().toLowerCase(Locale.ROOT);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown csl style: " + raw);
    }
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
    AppUser target = users.findByUsername(username);
    if (target == null) {
      if (orcidConfigured) {
        // ORCID/production mode: there's no self-registration for a made-up username, so we
        // can't invent an ORCID account on the owner's behalf.
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown user: " + username);
      }
      // Local-auth mode (no ORCID configured): there's no self-registration at all, so an owner
      // could never add a member who hasn't logged in yet. Auto-provision a local account instead
      // -- password = username, which is fine for local/dev use.
      target = userService.createLocal(username, username, username);
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

  // Public so other project-scoped services (e.g. JoinRequestService) can gate owner-only actions
  // through the same role check without duplicating it.
  public void requireOwner(int actorId, int projectId) {
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
