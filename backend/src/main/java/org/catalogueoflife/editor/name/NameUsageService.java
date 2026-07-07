package org.catalogueoflife.editor.name;

import java.util.List;
import java.util.Objects;
import org.catalogueoflife.editor.name.dto.CreateNameUsageRequest;
import org.catalogueoflife.editor.name.dto.NameUsageResponse;
import org.catalogueoflife.editor.name.dto.UpdateNameUsageRequest;
import org.catalogueoflife.editor.parse.NameParserService;
import org.catalogueoflife.editor.project.Project;
import org.catalogueoflife.editor.project.ProjectMapper;
import org.catalogueoflife.editor.project.ProjectService;
import org.catalogueoflife.editor.project.Role;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class NameUsageService {

  // Defense-in-depth pagination clamp (flagged by the Task 3 review for ReferenceService;
  // applied here too since neither controller bounds these query params).
  private static final int MAX_LIMIT = 200;

  private final NameUsageMapper usages;
  private final SynonymAcceptedMapper synonymAccepted;
  private final ProjectService projects;
  private final ProjectMapper projectMapper;
  private final NameParserService parser;

  public NameUsageService(NameUsageMapper usages, SynonymAcceptedMapper synonymAccepted,
      ProjectService projects, ProjectMapper projectMapper, NameParserService parser) {
    this.usages = usages;
    this.synonymAccepted = synonymAccepted;
    this.projects = projects;
    this.projectMapper = projectMapper;
    this.parser = parser;
  }

  public List<NameUsageResponse> list(long userId, long projectId, int limit, int offset) {
    projects.requireRole(userId, projectId);
    Project project = requireProject(projectId);
    return usages.findByProject(projectId, clampLimit(limit), clampOffset(offset)).stream()
        .map(u -> toResponse(u, project))
        .toList();
  }

  public List<NameUsageResponse> search(long userId, long projectId, String q, int limit, int offset) {
    projects.requireRole(userId, projectId);
    Project project = requireProject(projectId);
    return usages.search(projectId, q, clampLimit(limit), clampOffset(offset)).stream()
        .map(u -> toResponse(u, project))
        .toList();
  }

  public NameUsageResponse get(long userId, long projectId, long id) {
    projects.requireRole(userId, projectId);
    Project project = requireProject(projectId);
    return toResponse(requireInProject(projectId, id), project);
  }

  @Transactional
  public NameUsageResponse create(long userId, long projectId, CreateNameUsageRequest req) {
    requireEditor(userId, projectId);
    Project project = requireProject(projectId);
    NameUsage u = new NameUsage();
    u.setProjectId(projectId);
    u.setScientificName(req.scientificName());
    u.setAuthorship(req.authorship());
    u.setRank(req.rank());
    u.setStatus(req.status());
    u.setParentId(req.parentId());
    u.setNamePhrase(req.namePhrase());
    u.setNomStatus(req.nomStatus());
    u.setPublishedInReferenceId(req.publishedInReferenceId());
    u.setPublishedInYear(req.publishedInYear());
    u.setPublishedInPage(req.publishedInPage());
    u.setPublishedInPageLink(req.publishedInPageLink());
    u.setExtinct(req.extinct());
    u.setLink(req.link());
    u.setRemarks(req.remarks());
    u.setModifiedBy(userId);
    // parse BEFORE insert so the atomized fields + nameType/parseState are populated on the row.
    parser.parseInto(u, project.getNomCode());
    usages.insert(u);
    // the version column defaults to 0 in the DB (see V3__name_core.sql); reflect that
    // in the in-memory POJO returned to the caller without a redundant round-trip.
    u.setVersion(0);
    return toResponse(u, project);
  }

  @Transactional
  public NameUsageResponse update(long userId, long projectId, long id, UpdateNameUsageRequest req) {
    requireEditor(userId, projectId);
    Project project = requireProject(projectId);
    NameUsage u = requireInProject(projectId, id);
    boolean reparse = changed(u.getScientificName(), req.scientificName())
        || changed(u.getAuthorship(), req.authorship())
        || changed(u.getRank(), req.rank());
    u.setScientificName(req.scientificName());
    u.setAuthorship(req.authorship());
    u.setRank(req.rank());
    u.setStatus(req.status());
    u.setParentId(req.parentId());
    u.setNamePhrase(req.namePhrase());
    u.setNomStatus(req.nomStatus());
    u.setPublishedInReferenceId(req.publishedInReferenceId());
    u.setPublishedInYear(req.publishedInYear());
    u.setPublishedInPage(req.publishedInPage());
    u.setPublishedInPageLink(req.publishedInPageLink());
    u.setExtinct(req.extinct());
    u.setLink(req.link());
    u.setRemarks(req.remarks());
    u.setModifiedBy(userId);
    u.setVersion(req.version());
    if (reparse) {
      parser.parseInto(u, project.getNomCode());
    }
    int updated = usages.update(u);
    if (updated == 0) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "conflict: stale version");
    }
    return toResponse(requireInProject(projectId, id), project);
  }

  @Transactional
  public void delete(long userId, long projectId, long id) {
    requireEditor(userId, projectId);
    if (usages.delete(id, projectId) == 0) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "name usage not found");
    }
  }

  @Transactional
  public void linkSynonym(long userId, long projectId, long synonymId, long acceptedId) {
    requireEditor(userId, projectId);
    requireBothInProject(projectId, synonymId, acceptedId);
    synonymAccepted.link(synonymId, acceptedId, null);
  }

  @Transactional
  public void unlinkSynonym(long userId, long projectId, long synonymId, long acceptedId) {
    requireEditor(userId, projectId);
    requireBothInProject(projectId, synonymId, acceptedId);
    synonymAccepted.unlink(synonymId, acceptedId);
  }

  // App-layer cross-project integrity guard: synonym_accepted has no project_id column and no
  // DB-level check that both sides belong to the same project (design decision from Task 1), so
  // the service must verify both usages resolve within THIS project before (un)linking.
  private void requireBothInProject(long projectId, long synonymId, long acceptedId) {
    requireInProject(projectId, synonymId);
    requireInProject(projectId, acceptedId);
  }

  private NameUsageResponse toResponse(NameUsage u, Project project) {
    String formattedName = parser.formatName(u, project.getNomCode(), false);
    List<Long> acceptedParentIds = synonymAccepted.findAcceptedFor(u.getId());
    List<Long> synonymIds = "accepted".equals(u.getStatus())
        ? synonymAccepted.findSynonymsOf(u.getId())
        : List.of();
    return NameUsageResponse.of(u, formattedName, acceptedParentIds, synonymIds);
  }

  private NameUsage requireInProject(long projectId, long id) {
    NameUsage u = usages.findByIdInProject(id, projectId);
    if (u == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "name usage not found");
    }
    return u;
  }

  private Project requireProject(long projectId) {
    Project p = projectMapper.findById(projectId);
    if (p == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "project not found");
    }
    return p;
  }

  private void requireEditor(long userId, long projectId) {
    String role = projects.requireRole(userId, projectId);
    if (!role.equals(Role.OWNER.dbValue()) && !role.equals(Role.EDITOR.dbValue())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "owner or editor required");
    }
  }

  private static boolean changed(String oldValue, String newValue) {
    return !Objects.equals(oldValue, newValue);
  }

  private static int clampLimit(int limit) {
    if (limit < 1) {
      return 1;
    }
    return Math.min(limit, MAX_LIMIT);
  }

  private static int clampOffset(int offset) {
    return Math.max(offset, 0);
  }
}
