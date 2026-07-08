package org.catalogueoflife.editor.name;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import life.catalogue.api.vocab.Environment;
import life.catalogue.api.vocab.Gender;
import life.catalogue.api.vocab.NomStatus;
import org.catalogueoflife.editor.audit.AuditService;
import org.catalogueoflife.editor.audit.Operation;
import org.catalogueoflife.editor.name.dto.CreateNameUsageRequest;
import org.catalogueoflife.editor.name.dto.NameUsageResponse;
import org.catalogueoflife.editor.name.dto.UpdateNameUsageRequest;
import org.catalogueoflife.editor.name.dto.UsagePage;
import org.catalogueoflife.editor.parse.NameParserService;
import org.gbif.nameparser.api.Rank;
import org.catalogueoflife.editor.project.Project;
import org.catalogueoflife.editor.project.ProjectMapper;
import org.catalogueoflife.editor.project.ProjectService;
import org.catalogueoflife.editor.project.Role;
import org.catalogueoflife.editor.tree.TreeMapper;
import org.catalogueoflife.editor.validation.IssueMapper;
import org.catalogueoflife.editor.validation.ValidationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
public class NameUsageService {

  private static final String ENTITY = "name_usage";
  private static final String SYNONYM_LINK_ENTITY = "synonym_link";

  private final NameUsageMapper usages;
  private final SynonymAcceptedMapper synonymAccepted;
  private final IdSeqMapper idSeq;
  private final ProjectService projects;
  private final ProjectMapper projectMapper;
  private final NameParserService parser;
  private final TreeMapper tree;
  private final AuditService audit;
  private final ObjectMapper objectMapper;
  private final ApplicationEventPublisher events;
  private final IssueMapper issues;

  public NameUsageService(NameUsageMapper usages, SynonymAcceptedMapper synonymAccepted, IdSeqMapper idSeq,
      ProjectService projects, ProjectMapper projectMapper, NameParserService parser, TreeMapper tree,
      AuditService audit, ObjectMapper objectMapper, ApplicationEventPublisher events, IssueMapper issues) {
    this.usages = usages;
    this.synonymAccepted = synonymAccepted;
    this.idSeq = idSeq;
    this.projects = projects;
    this.projectMapper = projectMapper;
    this.parser = parser;
    this.tree = tree;
    this.audit = audit;
    this.objectMapper = objectMapper;
    this.events = events;
    this.issues = issues;
  }

  // Unified list/search backing GET /usages: q/rank/status are each optional and ANDed together
  // (blank/null -> unfiltered). rank/status are validated tolerantly like every other vocab field
  // (VocabParsing; unrecognized -> 400) and then normalized to their STORED string form so the
  // mapper's exact-match filters line up with what's actually in the column -- rank is stored
  // lower-case (see parse/ParsedNameMapping.applyTo), status upper-case (Status.name()), so a
  // caller-supplied "SPECIES" and a stored "species" must be reconciled here, not left to SQL.
  // `total` counts ALL matches for the same filters, ignoring limit/offset.
  public UsagePage searchPage(int userId, int projectId, String q, String rank, String status,
      int limit, int offset) {
    projects.requireRole(userId, projectId);
    String qFilter = (q == null || q.isBlank()) ? null : q;
    String rankFilter = normalizeRankFilter(rank);
    String statusFilter = normalizeStatusFilter(status);
    int clampedLimit = Pagination.clampLimit(limit);
    int clampedOffset = Pagination.clampOffset(offset);
    List<NameUsageResponse> items = usages
        .searchItems(projectId, qFilter, rankFilter, statusFilter, clampedLimit, clampedOffset).stream()
        .map(this::toListResponse)
        .toList();
    long total = usages.countMatches(projectId, qFilter, rankFilter, statusFilter);
    return new UsagePage(items, total);
  }

  // Blank/null -> no filter; otherwise the name-parser Rank matching the (case/space/hyphen
  // tolerant) input, re-rendered lower-case to match how rank is actually stored on name_usage.
  private static String normalizeRankFilter(String raw) {
    Rank r = VocabParsing.parse(Rank.class, raw, "rank");
    return r == null ? null : r.name().toLowerCase(Locale.ROOT);
  }

  // Blank/null -> no filter; otherwise the Status enum name (upper-case), matching the stored form.
  private static String normalizeStatusFilter(String raw) {
    Status s = VocabParsing.parse(Status.class, raw, "status");
    return s == null ? null : s.name();
  }

  public NameUsageResponse get(int userId, int projectId, int id) {
    projects.requireRole(userId, projectId);
    Project project = requireProject(projectId);
    return toResponse(requireInProject(projectId, id), project);
  }

  // The usages linked to `id` as synonyms (synonym_accepted.accepted_usage_id = id), ordered by
  // scientificName. Any project member may read; 404 if `id` itself isn't in the project.
  public List<NameUsageResponse> listSynonyms(int userId, int projectId, int id) {
    projects.requireRole(userId, projectId);
    Project project = requireProject(projectId);
    requireInProject(projectId, id);
    return synonymAccepted.findSynonymsOf(projectId, id).stream()
        .map(sid -> toResponse(requireInProject(projectId, sid), project))
        .sorted(Comparator.comparing(NameUsageResponse::scientificName, Comparator.nullsLast(String::compareTo)))
        .toList();
  }

  // The accepted usages that `id` points to (synonym_accepted.synonym_id = id), ordered by
  // scientificName. Any project member may read; 404 if `id` itself isn't in the project.
  public List<NameUsageResponse> listAccepted(int userId, int projectId, int id) {
    projects.requireRole(userId, projectId);
    Project project = requireProject(projectId);
    requireInProject(projectId, id);
    return synonymAccepted.findAcceptedFor(projectId, id).stream()
        .map(aid -> toResponse(requireInProject(projectId, aid), project))
        .sorted(Comparator.comparing(NameUsageResponse::scientificName, Comparator.nullsLast(String::compareTo)))
        .toList();
  }

  @Transactional
  public NameUsageResponse create(int userId, int projectId, CreateNameUsageRequest req) {
    requireEditor(userId, projectId);
    Project project = requireProject(projectId);
    if (req.parentId() != null) {
      // See TreeMapper.lockProject: serializes against concurrent moves/creates/updates that
      // touch this project's tree while we validate+use req.parentId() below.
      tree.lockProject(projectId);
      requireValidParent(projectId, null, req.parentId());
    }
    NameUsage u = new NameUsage();
    u.setProjectId(projectId);
    u.setScientificName(req.scientificName());
    u.setAuthorship(req.authorship());
    u.setRank(req.rank());
    u.setStatus(VocabParsing.requireParse(Status.class, req.status(), "status"));
    u.setParentId(req.parentId());
    u.setNamePhrase(req.namePhrase());
    u.setNomStatus(VocabParsing.parse(NomStatus.class, req.nomStatus(), "nomStatus"));
    u.setPublishedInReferenceId(req.publishedInReferenceId());
    u.setPublishedInYear(req.publishedInYear());
    u.setPublishedInPage(req.publishedInPage());
    u.setPublishedInPageLink(req.publishedInPageLink());
    u.setGender(VocabParsing.parse(Gender.class, req.gender(), "gender"));
    u.setExtinct(req.extinct());
    u.setEnvironment(parseEnvironments(req.environment()));
    u.setTemporalRangeStart(req.temporalRangeStart());
    u.setTemporalRangeEnd(req.temporalRangeEnd());
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
    audit.record(projectId, userId, ENTITY, u.getId(), Operation.CREATE, null, u);
    // Published INSIDE this @Transactional method so ValidationTrigger's AFTER_COMMIT listener
    // only ever fires once this create has actually committed (see validation/ValidationTrigger).
    events.publishEvent(ValidationEvent.forUsage(projectId, u.getId()));
    return toResponse(u, project);
  }

  @Transactional
  public NameUsageResponse update(int userId, int projectId, int id, UpdateNameUsageRequest req) {
    requireEditor(userId, projectId);
    Project project = requireProject(projectId);
    if (req.parentId() != null) {
      // See TreeMapper.lockProject: serializes against concurrent moves/creates/updates that
      // touch this project's tree while we validate+use req.parentId() below.
      tree.lockProject(projectId);
      requireValidParent(projectId, id, req.parentId());
    }
    NameUsage u = requireInProject(projectId, id);
    // Snapshot BEFORE mutating u's fields in place below: MyBatis's session-scoped local cache
    // would hand back this SAME cached instance from a second identical findByIdInProject call
    // (no intervening write to invalidate it yet), so re-fetching would alias rather than give an
    // independent "before" object. Converting to a Map here decouples the audit snapshot from u's
    // subsequent mutation.
    @SuppressWarnings("unchecked")
    Map<String, Object> before = objectMapper.convertValue(u, Map.class);
    boolean reparse = changed(u.getScientificName(), req.scientificName())
        || changed(u.getAuthorship(), req.authorship())
        || changed(u.getRank(), req.rank());
    u.setScientificName(req.scientificName());
    u.setAuthorship(req.authorship());
    u.setRank(req.rank());
    u.setStatus(VocabParsing.requireParse(Status.class, req.status(), "status"));
    u.setParentId(req.parentId());
    u.setNamePhrase(req.namePhrase());
    u.setNomStatus(VocabParsing.parse(NomStatus.class, req.nomStatus(), "nomStatus"));
    u.setPublishedInReferenceId(req.publishedInReferenceId());
    u.setPublishedInYear(req.publishedInYear());
    u.setPublishedInPage(req.publishedInPage());
    u.setPublishedInPageLink(req.publishedInPageLink());
    u.setGender(VocabParsing.parse(Gender.class, req.gender(), "gender"));
    u.setExtinct(req.extinct());
    u.setEnvironment(parseEnvironments(req.environment()));
    u.setTemporalRangeStart(req.temporalRangeStart());
    u.setTemporalRangeEnd(req.temporalRangeEnd());
    u.setEtymology(req.etymology());
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
    NameUsage after = requireInProject(projectId, id);
    audit.record(projectId, userId, ENTITY, id, Operation.UPDATE, before, after);
    events.publishEvent(ValidationEvent.forUsage(projectId, id));
    return toResponse(after, project);
  }

  @Transactional
  public void delete(int userId, int projectId, int id) {
    requireEditor(userId, projectId);
    NameUsage before = requireInProject(projectId, id);
    if (usages.delete(projectId, id) == 0) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "name usage not found");
    }
    // entity_id is polymorphic (no cascade FK to name_usage): clean up this usage's own issue rows
    // now, or they'd reference a nonexistent entity forever (see validation/IssueMapper.deleteByEntity).
    issues.deleteByEntity(projectId, ENTITY, id);
    audit.record(projectId, userId, ENTITY, id, Operation.DELETE, before, null);
    // The usage itself is gone by the time this fires (AFTER_COMMIT), so
    // ValidationService.revalidateUsage will find nothing and no-op -- published anyway per the
    // plan's create/update/delete symmetry; a later plan may extend this to re-check usages that
    // referenced the deleted one (e.g. former synonym links), which is out of scope here.
    events.publishEvent(ValidationEvent.forUsage(projectId, id));
  }

  @Transactional
  public void linkSynonym(int userId, int projectId, int synonymId, int acceptedId) {
    requireEditor(userId, projectId);
    requireBothInProject(projectId, synonymId, acceptedId);
    // ON CONFLICT DO NOTHING (see SynonymAcceptedMapper.link): only audit/revalidate an actual new
    // link, not a no-op re-link of an already-existing pair.
    if (synonymAccepted.link(projectId, synonymId, acceptedId, null) > 0) {
      audit.record(projectId, userId, SYNONYM_LINK_ENTITY, synonymId, Operation.CREATE, null,
          Map.of("acceptedId", acceptedId));
      events.publishEvent(ValidationEvent.forUsage(projectId, synonymId));
    }
  }

  @Transactional
  public void unlinkSynonym(int userId, int projectId, int synonymId, int acceptedId) {
    requireEditor(userId, projectId);
    requireBothInProject(projectId, synonymId, acceptedId);
    if (synonymAccepted.unlink(projectId, synonymId, acceptedId) > 0) {
      audit.record(projectId, userId, SYNONYM_LINK_ENTITY, synonymId, Operation.DELETE,
          Map.of("acceptedId", acceptedId), null);
      events.publishEvent(ValidationEvent.forUsage(projectId, synonymId));
    }
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
    List<Integer> synonymIds = Status.ACCEPTED == u.getStatus()
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

  // Centralizes the cycle/accepted-parent guards that the generic usage create/update endpoints
  // must apply -- previously only TreeService.move enforced these, so an editor could create a
  // cycle or parent a usage under a synonym by going through PUT/POST /usages instead of the
  // dedicated tree/move endpoint. Callers only invoke this when parentId != null and must hold
  // TreeMapper.lockProject(projectId) first so the isDescendant check below is atomic with the
  // eventual write. `id` is null on create (the new row has no id yet, so no self-parent/cycle is
  // possible -- only the accepted-parent check applies); non-null on update.
  private void requireValidParent(int projectId, Integer id, int parentId) {
    if (id != null && parentId == id) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "a usage cannot be its own parent");
    }
    NameUsage parent = usages.findByIdInProject(projectId, parentId);
    if (parent == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "parent not in project");
    }
    if (parent.getStatus() != Status.ACCEPTED) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "parent must be an accepted usage");
    }
    if (id != null && tree.isDescendant(projectId, id, parentId)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "would create a cycle");
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

  private static List<Environment> parseEnvironments(List<String> raw) {
    if (raw == null) {
      return null;
    }
    return raw.stream()
        .map(s -> VocabParsing.requireParse(Environment.class, s, "environment"))
        .toList();
  }
}
