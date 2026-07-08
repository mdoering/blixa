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

  private static final String ENTITY = "name_usage";

  private final NameUsageMapper usages;
  private final SynonymAcceptedMapper synonymAccepted;
  private final IdSeqMapper idSeq;
  private final ProjectService projects;
  private final ProjectMapper projectMapper;
  private final NameParserService parser;

  public NameUsageService(NameUsageMapper usages, SynonymAcceptedMapper synonymAccepted, IdSeqMapper idSeq,
      ProjectService projects, ProjectMapper projectMapper, NameParserService parser) {
    this.usages = usages;
    this.synonymAccepted = synonymAccepted;
    this.idSeq = idSeq;
    this.projects = projects;
    this.projectMapper = projectMapper;
    this.parser = parser;
  }

  public List<NameUsageResponse> list(int userId, int projectId, int limit, int offset) {
    projects.requireRole(userId, projectId);
    return usages.findByProject(projectId, Pagination.clampLimit(limit), Pagination.clampOffset(offset)).stream()
        .map(this::toListResponse)
        .toList();
  }

  public List<NameUsageResponse> search(int userId, int projectId, String q, int limit, int offset) {
    projects.requireRole(userId, projectId);
    return usages.search(projectId, q, Pagination.clampLimit(limit), Pagination.clampOffset(offset)).stream()
        .map(this::toListResponse)
        .toList();
  }

  public NameUsageResponse get(int userId, int projectId, int id) {
    projects.requireRole(userId, projectId);
    Project project = requireProject(projectId);
    return toResponse(requireInProject(projectId, id), project);
  }

  @Transactional
  public NameUsageResponse create(int userId, int projectId, CreateNameUsageRequest req) {
    requireEditor(userId, projectId);
    Project project = requireProject(projectId);
    requireParentInProject(projectId, req.parentId());
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
    // allocate the next per-project id BEFORE inserting: name_usage has no DB identity column
    // any more (see V3__name_core.sql), so the app owns id generation via id_seq.
    u.setId(idSeq.allocate(projectId, ENTITY));
    usages.insert(u);
    // the version column defaults to 0 in the DB (see V3__name_core.sql); reflect that
    // in the in-memory POJO returned to the caller without a redundant round-trip.
    u.setVersion(0);
    return toResponse(u, project);
  }

  @Transactional
  public NameUsageResponse update(int userId, int projectId, int id, UpdateNameUsageRequest req) {
    requireEditor(userId, projectId);
    Project project = requireProject(projectId);
    requireParentInProject(projectId, req.parentId());
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
  public void delete(int userId, int projectId, int id) {
    requireEditor(userId, projectId);
    if (usages.delete(projectId, id) == 0) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "name usage not found");
    }
  }

  @Transactional
  public void linkSynonym(int userId, int projectId, int synonymId, int acceptedId) {
    requireEditor(userId, projectId);
    requireBothInProject(projectId, synonymId, acceptedId);
    synonymAccepted.link(projectId, synonymId, acceptedId, null);
  }

  @Transactional
  public void unlinkSynonym(int userId, int projectId, int synonymId, int acceptedId) {
    requireEditor(userId, projectId);
    requireBothInProject(projectId, synonymId, acceptedId);
    synonymAccepted.unlink(projectId, synonymId, acceptedId);
  }

  // App-layer cross-project integrity guard, kept as a nice 404/400 in front of the DB-level
  // compound FKs (synonym_accepted's FKs to name_usage(project_id, id), see V3__name_core.sql):
  // verify both usages resolve within THIS project before (un)linking.
  private void requireBothInProject(int projectId, int synonymId, int acceptedId) {
    requireInProject(projectId, synonymId);
    requireInProject(projectId, acceptedId);
  }

  // Full response for single-usage endpoints (get/create/update): a real re-parse for the
  // formatted display name plus the per-row synonym-link lookups.
  private NameUsageResponse toResponse(NameUsage u, Project project) {
    String formattedName = parser.formatName(u, project.getNomCode(), false);
    List<Integer> acceptedParentIds = synonymAccepted.findAcceptedFor(u.getProjectId(), u.getId());
    List<Integer> synonymIds = "accepted".equals(u.getStatus())
        ? synonymAccepted.findSynonymsOf(u.getProjectId(), u.getId())
        : List.of();
    return NameUsageResponse.of(u, formattedName, acceptedParentIds, synonymIds);
  }

  // Cheap response for list/search hot paths: avoids the full name-parser re-parse and the
  // per-row synonym-link queries (an N+1 for both) by building the formatted name from the
  // already-stored scientificName/authorship columns and leaving the link lists empty. Detail
  // view (get/create/update, via toResponse) is where the fully-parsed/linked response belongs.
  private NameUsageResponse toListResponse(NameUsage u) {
    String authorship = u.getAuthorship();
    String formattedName = (authorship == null || authorship.isBlank())
        ? u.getScientificName()
        : u.getScientificName() + " " + authorship;
    return NameUsageResponse.of(u, formattedName, List.of(), List.of());
  }

  private void requireParentInProject(int projectId, Integer parentId) {
    if (parentId != null && usages.findByIdInProject(projectId, parentId) == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "parent not in project");
    }
  }

  private NameUsage requireInProject(int projectId, int id) {
    NameUsage u = usages.findByIdInProject(projectId, id);
    if (u == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "name usage not found");
    }
    return u;
  }

  private Project requireProject(int projectId) {
    Project p = projectMapper.findById(projectId);
    if (p == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "project not found");
    }
    return p;
  }

  private void requireEditor(int userId, int projectId) {
    String role = projects.requireRole(userId, projectId);
    if (!role.equals(Role.OWNER.dbValue()) && !role.equals(Role.EDITOR.dbValue())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "owner or editor required");
    }
  }

  private static boolean changed(String oldValue, String newValue) {
    return !Objects.equals(oldValue, newValue);
  }
}
