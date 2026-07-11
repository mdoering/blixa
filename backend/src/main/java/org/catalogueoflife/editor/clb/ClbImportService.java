package org.catalogueoflife.editor.clb;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import life.catalogue.api.model.UsageInfo;
import org.catalogueoflife.editor.child.DistributionMapper;
import org.catalogueoflife.editor.child.EstimateMapper;
import org.catalogueoflife.editor.child.MediaMapper;
import org.catalogueoflife.editor.child.NameRelationMapper;
import org.catalogueoflife.editor.child.PropertyMapper;
import org.catalogueoflife.editor.child.TypeMaterialMapper;
import org.catalogueoflife.editor.child.VernacularMapper;
import org.catalogueoflife.editor.child.dto.DistributionRequest;
import org.catalogueoflife.editor.child.dto.EstimateRequest;
import org.catalogueoflife.editor.child.dto.MediaRequest;
import org.catalogueoflife.editor.child.dto.NameRelationRequest;
import org.catalogueoflife.editor.child.dto.PropertyRequest;
import org.catalogueoflife.editor.child.dto.TypeMaterialRequest;
import org.catalogueoflife.editor.child.dto.VernacularRequest;
import org.catalogueoflife.editor.clb.ClbUsageMapper.MappedDistribution;
import org.catalogueoflife.editor.clb.ClbUsageMapper.MappedEstimate;
import org.catalogueoflife.editor.clb.ClbUsageMapper.MappedImport;
import org.catalogueoflife.editor.clb.ClbUsageMapper.MappedNameRelation;
import org.catalogueoflife.editor.clb.ClbUsageMapper.MappedProperty;
import org.catalogueoflife.editor.clb.ClbUsageMapper.MappedTypeMaterial;
import org.catalogueoflife.editor.clb.ClbUsageMapper.MappedUsage;
import org.catalogueoflife.editor.clb.ClbUsageMapper.MappedVernacular;
import org.catalogueoflife.editor.clb.dto.ClbImportRequest;
import org.catalogueoflife.editor.clb.dto.ClbImportSummary;
import org.catalogueoflife.editor.clb.dto.ClbImportSummary.ClbImportIssue;
import org.catalogueoflife.editor.name.IdSeqMapper;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.name.NameUsageMapper;
import org.catalogueoflife.editor.name.NameUsageService;
import org.catalogueoflife.editor.name.Reference;
import org.catalogueoflife.editor.name.ReferenceMapper;
import org.catalogueoflife.editor.name.Status;
import org.catalogueoflife.editor.name.SynonymAcceptedMapper;
import org.catalogueoflife.editor.name.TaxonInfoMapper;
import org.catalogueoflife.editor.parse.NameParserService;
import org.catalogueoflife.editor.project.Project;
import org.catalogueoflife.editor.project.ProjectMapper;
import org.catalogueoflife.editor.project.ProjectService;
import org.catalogueoflife.editor.project.Role;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

// Direct CLB Taxon Import (Task 2): fetches the chosen CLB taxon/taxa (via ClbImportClient,
// mapped through ClbUsageMapper) and inserts them straight into a project, under an EXISTING
// focal (already-ACCEPTED) usage -- see ImportMode's own javadoc for the three modes. This is
// deliberately the SAME insert shape as the ColDP import's own load (coldp/imprt/
// ImportRunService): app-allocated ids via IdSeqMapper, raw mapper inserts (NameUsageMapper/
// SynonymAcceptedMapper/TaxonInfoMapper/ReferenceMapper/the 7 child mappers) rather than going
// through NameUsageService/AbstractChildEntityService (no audit trail, no per-row
// ValidationEvent -- a CLB import is small and additive, not a bulk archive load that warrants a
// full post-load revalidation pass), and NameParserService.parseInto as the same safety net that
// (re-)derives the atomized name fields ImportRunService.insertPrimaryUsage relies on.
//
// The one structural difference from ColDP import: THIS insert is always top-down under an
// existing parent, so a gathered usage's parent (either the focal usage itself, or an
// already-inserted sibling higher in the gathered set) is always known and already has a new id
// by the time we get to it (see gather()'s BFS, which visits parents strictly before children) --
// unlike ImportRunService's two-phase insert (parent_id left null, patched in a second pass), so
// parent_id is simply set at insert time here, no Pass 2 needed.
//
// No @Transactional anywhere in this class, deliberately: per the design brief, a CLB import is
// small and additive, so each mapper call is left to commit as it goes (MyBatis-Spring's
// SqlSessionTemplate auto-commits per statement when there is no ambient Spring transaction --
// the same "a non-transactional straight call is fine" contract MergeApplyService.
// applyNonTransactional's own javadoc documents, just without that class's extra batching, which
// exists there only because a full-project merge can be orders of magnitude bigger than a CLB
// taxon subtree). A failure partway through leaves whatever was already inserted in place rather
// than rolling back -- exactly the "small, additive" contract calls for.
@Service
public class ClbImportService {

  // id_seq entity strings -- MUST match NameUsageService.ENTITY / ReferenceService.ENTITY / each
  // AbstractChildEntityService subclass's own entity() (see ImportRunService's identical
  // constants and javadoc for why: id_seq's per-(project, entity) counter is shared with every
  // other writer of that entity).
  private static final String NAME_USAGE_ENTITY = "name_usage";
  private static final String REFERENCE_ENTITY = "reference";
  private static final String DISTRIBUTION_ENTITY = "distribution";
  private static final String VERNACULAR_ENTITY = "vernacular";
  private static final String MEDIA_ENTITY = "media";
  private static final String ESTIMATE_ENTITY = "estimate";
  private static final String PROPERTY_ENTITY = "property";
  private static final String TYPE_MATERIAL_ENTITY = "type_material";
  private static final String NAME_RELATION_ENTITY = "name_relation";

  // ClbImportRequest.entityTypes' own vocabulary (see its javadoc) -- distinct from the id_seq
  // ENTITY constants above (e.g. "typeMaterial" here vs "type_material" there): these are what a
  // caller actually sends over the wire.
  private static final String T_SYNONYMS = "synonyms";
  private static final String T_VERNACULAR = "vernacular";
  private static final String T_DISTRIBUTION = "distribution";
  private static final String T_TYPE_MATERIAL = "typeMaterial";
  private static final String T_MEDIA = "media";
  private static final String T_ESTIMATE = "estimate";
  private static final String T_PROPERTY = "property";
  private static final String T_NAME_RELATION = "nameRelation";

  private static final List<String> CHILD_TYPES =
      List.of(T_VERNACULAR, T_DISTRIBUTION, T_TYPE_MATERIAL, T_MEDIA, T_ESTIMATE, T_PROPERTY, T_NAME_RELATION);

  private final ClbImportClient client;
  private final ProjectService projects;
  private final ProjectMapper projectMapper;
  private final IdSeqMapper idSeq;
  private final NameUsageMapper usages;
  private final SynonymAcceptedMapper synonymAccepted;
  private final TaxonInfoMapper taxonInfo;
  private final ReferenceMapper references;
  private final NameParserService parser;
  private final DistributionMapper distributions;
  private final VernacularMapper vernaculars;
  private final MediaMapper media;
  private final EstimateMapper estimates;
  private final PropertyMapper properties;
  private final TypeMaterialMapper typeMaterials;
  private final NameRelationMapper nameRelations;
  private final int maxUsages;
  // Same configured key ClbMatchClient.defaultColDataset()/ColMatchService use for "the" COL
  // dataset (coldp.col.match-dataset, normally 3LXR) -- read independently here (rather than
  // injecting ClbMatchClient just for its getter) purely so the provenance-scope decision below
  // stays in lock-step with whatever that shared property is configured to, without adding an
  // unrelated network-client dependency to this service.
  private final String colDataset;

  public ClbImportService(ClbImportClient client, ProjectService projects, ProjectMapper projectMapper,
      IdSeqMapper idSeq, NameUsageMapper usages, SynonymAcceptedMapper synonymAccepted,
      TaxonInfoMapper taxonInfo, ReferenceMapper references, NameParserService parser,
      DistributionMapper distributions, VernacularMapper vernaculars, MediaMapper media,
      EstimateMapper estimates, PropertyMapper properties, TypeMaterialMapper typeMaterials,
      NameRelationMapper nameRelations,
      @Value("${coldp.clb-import.max-usages:500}") int maxUsages,
      @Value("${coldp.col.match-dataset:3LXR}") String colDataset) {
    this.client = client;
    this.projects = projects;
    this.projectMapper = projectMapper;
    this.idSeq = idSeq;
    this.usages = usages;
    this.synonymAccepted = synonymAccepted;
    this.taxonInfo = taxonInfo;
    this.references = references;
    this.parser = parser;
    this.distributions = distributions;
    this.vernaculars = vernaculars;
    this.media = media;
    this.estimates = estimates;
    this.properties = properties;
    this.typeMaterials = typeMaterials;
    this.nameRelations = nameRelations;
    this.maxUsages = maxUsages;
    this.colDataset = colDataset;
  }

  public ClbImportSummary importFromClb(int userId, int projectId, int focalUsageId, ClbImportRequest req) {
    Project project = requireEditorProject(userId, projectId);
    NameUsage focal = usages.findByIdInProject(projectId, focalUsageId);
    if (focal == null || focal.getStatus() != Status.ACCEPTED) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "focal usage not found or not accepted");
    }
    if (req.mode() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mode is required");
    }
    if (req.datasetKey() == null || req.datasetKey().isBlank()
        || req.sourceTaxonId() == null || req.sourceTaxonId().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "datasetKey and sourceTaxonId are required");
    }

    String scope = scopeFor(req.datasetKey());
    List<ClbImportIssue> issues = new ArrayList<>();
    Map<String, Integer> refIdMap = new HashMap<>();
    Map<String, Integer> usageIdMap = new HashMap<>();
    Map<String, Integer> nameIdMap = new HashMap<>();
    Map<String, Integer> childCounts = new LinkedHashMap<>();
    CHILD_TYPES.forEach(t -> childCounts.put(t, 0));

    if (req.mode() == ImportMode.UPDATE_FOCAL) {
      MappedImport bundle = ClbUsageMapper.toCreateRequest(client.usageInfo(req.datasetKey(), req.sourceTaxonId()));
      insertReferences(projectId, userId, scope, List.of(bundle), refIdMap);
      // The source's own usage/name CLB ids now stand in for the FOCAL usage: any TypeMaterial/
      // NameRelation entry the bundle carries against them (see insertChildEntities) must attach
      // to the focal usage, not a new one -- there is no new "accepted usage" insert in this mode.
      usageIdMap.put(bundle.usage().clbUsageId(), focalUsageId);
      nameIdMap.put(bundle.usage().clbNameId(), focalUsageId);

      int synonymCount = 0;
      if (included(req.entityTypes(), T_SYNONYMS, false)) {
        synonymCount = insertSynonyms(projectId, userId, project, scope, bundle, focalUsageId,
            refIdMap, usageIdMap, nameIdMap);
      }
      // Mode C's target is always the focal usage, guaranteed ACCEPTED by the guard above, so the
      // 5 taxon-scoped child types are always eligible here (no accepted-only skip needed).
      insertChildEntities(projectId, userId, bundle, focalUsageId, true, usageIdMap, nameIdMap, refIdMap,
          req.entityTypes(), false, childCounts, issues);
      return new ClbImportSummary(0, synonymCount, refIdMap.size(), childCounts, issues);
    }

    Gathered gathered = gather(req.datasetKey(), req.sourceTaxonId(), req.mode());

    Map<String, MappedImport> bundleByClbId = new LinkedHashMap<>();
    for (String id : gathered.orderedIds()) {
      bundleByClbId.put(id, ClbUsageMapper.toCreateRequest(client.usageInfo(req.datasetKey(), id)));
    }
    insertReferences(projectId, userId, scope, bundleByClbId.values(), refIdMap);

    int nameUsageCount = 0;
    int synonymCount = 0;
    boolean includeSynonyms = included(req.entityTypes(), T_SYNONYMS, true);
    for (String id : gathered.orderedIds()) {
      MappedImport bundle = bundleByClbId.get(id);
      MappedUsage mu = bundle.usage();
      NameUsage u = mu.usage();
      u.setProjectId(projectId);
      u.setModifiedBy(userId);
      int newId = idSeq.allocate(projectId, NAME_USAGE_ENTITY);
      u.setId(newId);
      String clbParentId = gathered.rootIds().contains(id) ? null : gathered.parentOf().get(id);
      u.setParentId(clbParentId == null ? focalUsageId : usageIdMap.get(clbParentId));
      u.setPublishedInReferenceId(resolveRef(refIdMap, mu.clbPublishedInReferenceId()));
      u.setReferenceId(resolveRefList(refIdMap, mu.clbReferenceIds()));
      u.setAlternativeId(NameUsageService.mergeScopedId(null, scope, id));
      parser.parseInto(u, project.getNomCode());
      if (u.getRank() == null || u.getRank().isBlank()) {
        u.setRank("unranked");
      }
      usages.insert(u);
      if (u.getStatus() == Status.ACCEPTED && hasTaxonInfo(u)) {
        taxonInfo.upsert(projectId, newId, u.getExtinct(), u.getEnvironment(),
            u.getTemporalRangeStart(), u.getTemporalRangeEnd());
      }
      usageIdMap.put(mu.clbUsageId(), newId);
      nameIdMap.put(mu.clbNameId(), newId);
      nameUsageCount++;

      if (includeSynonyms) {
        synonymCount += insertSynonyms(projectId, userId, project, scope, bundle, newId,
            refIdMap, usageIdMap, nameIdMap);
      }
      insertChildEntities(projectId, userId, bundle, newId, u.getStatus() == Status.ACCEPTED,
          usageIdMap, nameIdMap, refIdMap, req.entityTypes(), true, childCounts, issues);
    }

    return new ClbImportSummary(nameUsageCount, synonymCount, refIdMap.size(), childCounts, issues);
  }

  // --- gather (modes A/B) -----------------------------------------------------------------------

  // orderedIds: every gathered CLB usage id, PARENT BEFORE CHILD (see the BFS below). parentOf:
  // clbId -> its CLB parent's id, for every gathered id EXCEPT a root of the gathered set (rootIds)
  // -- a root's new parent_id is the FOCAL usage, not another gathered usage.
  private record Gathered(List<String> orderedIds, Map<String, String> parentOf, Set<String> rootIds) {}

  // BFS over ClbImportClient.childrenIds: a queue-based walk guarantees a node is only ever
  // enqueued (and thus later dequeued+added to orderedIds) AFTER its own parent has already been
  // dequeued+added, so orderedIds is always parent-before-child -- exactly what the insert loop
  // above needs to resolve u.setParentId(usageIdMap.get(clbParentId)) without a two-phase insert.
  // The size cap is enforced incrementally, right as each id is added, so a subtree that blows
  // the cap fails fast without walking (or fetching UsageInfo for) the rest of it.
  private Gathered gather(String datasetKey, String sourceId, ImportMode mode) {
    List<String> ordered = new ArrayList<>();
    Map<String, String> parentOf = new HashMap<>();
    Set<String> roots = new LinkedHashSet<>();
    Deque<String> queue = new ArrayDeque<>();

    if (mode == ImportMode.TAXON_SUBTREE) {
      queue.add(sourceId);
      roots.add(sourceId);
    } else {
      for (String cid : client.childrenIds(datasetKey, sourceId)) {
        queue.add(cid);
        roots.add(cid);
      }
    }

    while (!queue.isEmpty()) {
      String id = queue.poll();
      ordered.add(id);
      if (ordered.size() > maxUsages) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "subtree too large (" + ordered.size() + " usages) — pick a smaller root or use ColDP import");
      }
      for (String cid : client.childrenIds(datasetKey, id)) {
        parentOf.put(cid, id);
        queue.add(cid);
      }
    }
    return new Gathered(ordered, parentOf, roots);
  }

  // --- references ---------------------------------------------------------------------------------

  // Merges every gathered bundle's UsageInfo.getReferences() into one clbRefId -> Reference map
  // (first-seen wins on a shared id -- see ClbImportRequest's own "duplicates against existing
  // target refs are allowed, no matching" contract: this only dedups WITHIN this one import call),
  // then inserts each exactly once, filling refIdMap for every later referenceID/publishedInId/
  // child referenceId remap in this same call.
  private void insertReferences(int projectId, int userId, String scope, Collection<MappedImport> bundles,
      Map<String, Integer> refIdMap) {
    Map<String, Reference> merged = new LinkedHashMap<>();
    for (MappedImport b : bundles) {
      b.references().forEach(merged::putIfAbsent);
    }
    for (var e : merged.entrySet()) {
      Reference r = e.getValue();
      r.setProjectId(projectId);
      r.setModifiedBy(userId);
      r.setAlternativeId(NameUsageService.mergeScopedId(null, scope, e.getKey()));
      r.setId(idSeq.allocate(projectId, REFERENCE_ENTITY));
      references.insert(r);
      refIdMap.put(e.getKey(), r.getId());
    }
  }

  // --- synonyms -------------------------------------------------------------------------------

  // Inserts every synonym in `bundle` (parseInto'd + referenceId-remapped + provenance-stamped,
  // exactly like the accepted usage itself), linking each to `acceptedNewId` (ordinal 0, 1, 2, ...
  // in the bundle's own order -- CLB's flattened synonymy carries no pro-parte ambiguity for this
  // mapper to preserve, see ClbUsageMapper.toSynonyms' javadoc, so a plain increasing ordinal is
  // sufficient). Populates usageIdMap/nameIdMap for each synonym too, so a TypeMaterial/
  // NameRelation entry owned by a SYNONYM's name (not just the accepted taxon's) still resolves in
  // insertChildEntities below. Returns the number of synonyms inserted.
  private int insertSynonyms(int projectId, int userId, Project project, String scope, MappedImport bundle,
      int acceptedNewId, Map<String, Integer> refIdMap, Map<String, Integer> usageIdMap,
      Map<String, Integer> nameIdMap) {
    int ordinal = 0;
    for (MappedUsage syn : bundle.synonyms()) {
      NameUsage u = syn.usage();
      u.setProjectId(projectId);
      u.setModifiedBy(userId);
      int newId = idSeq.allocate(projectId, NAME_USAGE_ENTITY);
      u.setId(newId);
      // parentId stays null: synonyms link via synonym_accepted, never the classification
      // parent_id (see NameUsage's own class doc / ImportRunService's identical Pass-2 split).
      u.setPublishedInReferenceId(resolveRef(refIdMap, syn.clbPublishedInReferenceId()));
      u.setReferenceId(resolveRefList(refIdMap, syn.clbReferenceIds()));
      u.setAlternativeId(NameUsageService.mergeScopedId(null, scope, syn.clbUsageId()));
      parser.parseInto(u, project.getNomCode());
      if (u.getRank() == null || u.getRank().isBlank()) {
        u.setRank("unranked");
      }
      usages.insert(u);
      usageIdMap.put(syn.clbUsageId(), newId);
      nameIdMap.put(syn.clbNameId(), newId);
      synonymAccepted.link(projectId, newId, acceptedNewId, ordinal++);
    }
    return bundle.synonyms().size();
  }

  // --- child entities ---------------------------------------------------------------------------

  // Inserts the selected (per `entityTypes`/`defaultAll`, see included()) child-entity kinds from
  // one gathered usage's whole bundle (covering both the accepted taxon AND its synonyms, since
  // UsageInfo -- and therefore MappedImport -- bundles them together). The 5 taxon-scoped kinds
  // (vernacular/distribution/media/estimate/property) always belong to `taxonOwnerId` itself (the
  // accepted usage this bundle is FOR -- see ClbUsageMapper's own "taxa only" javadoc) and require
  // `ownerAccepted` (mirrors AbstractChildEntityService.requireAcceptedUsage /
  // ImportRunService.loadChildEntities' identical guard: PROVISIONALLY_ACCEPTED collapses to our
  // UNASSESSED, see ClbUsageMapper.toStatus, so even a CLB "taxon" can fail this check). TypeMaterial/
  // NameRelation key off whichever name/usage in the bundle owns them (nameIdMap/usageIdMap,
  // populated for the accepted usage AND every synonym by the caller before this runs) and apply
  // to ANY usage status, so they never consult `ownerAccepted`.
  private void insertChildEntities(int projectId, int userId, MappedImport bundle, int taxonOwnerId,
      boolean ownerAccepted, Map<String, Integer> usageIdMap, Map<String, Integer> nameIdMap,
      Map<String, Integer> refIdMap, Set<String> entityTypes, boolean defaultAll,
      Map<String, Integer> childCounts, List<ClbImportIssue> issues) {

    if (included(entityTypes, T_DISTRIBUTION, defaultAll)) {
      if (ownerAccepted) {
        for (MappedDistribution d : bundle.distributions()) {
          DistributionRequest src = d.request();
          DistributionRequest r = new DistributionRequest(src.area(), src.areaId(), src.gazetteer(),
              src.establishmentMeans(), src.threatStatus(), resolveRef(refIdMap, d.clbReferenceId()),
              src.remarks(), null);
          int id = idSeq.allocate(projectId, DISTRIBUTION_ENTITY);
          distributions.insert(projectId, id, taxonOwnerId, r, userId);
          childCounts.merge(T_DISTRIBUTION, 1, Integer::sum);
        }
      } else if (!bundle.distributions().isEmpty()) {
        issues.add(new ClbImportIssue(DISTRIBUTION_ENTITY, String.valueOf(taxonOwnerId),
            "usage is not accepted — distribution skipped"));
      }
    }

    if (included(entityTypes, T_VERNACULAR, defaultAll)) {
      if (ownerAccepted) {
        for (MappedVernacular vn : bundle.vernaculars()) {
          VernacularRequest src = vn.request();
          VernacularRequest r = new VernacularRequest(src.name(), src.language(), src.country(), src.sex(),
              src.preferred(), resolveRef(refIdMap, vn.clbReferenceId()), src.remarks(), null);
          int id = idSeq.allocate(projectId, VERNACULAR_ENTITY);
          vernaculars.insert(projectId, id, taxonOwnerId, r, userId);
          childCounts.merge(T_VERNACULAR, 1, Integer::sum);
        }
      } else if (!bundle.vernaculars().isEmpty()) {
        issues.add(new ClbImportIssue(VERNACULAR_ENTITY, String.valueOf(taxonOwnerId),
            "usage is not accepted — vernacular skipped"));
      }
    }

    if (included(entityTypes, T_MEDIA, defaultAll)) {
      if (ownerAccepted) {
        for (MediaRequest r : bundle.media()) {
          int id = idSeq.allocate(projectId, MEDIA_ENTITY);
          media.insert(projectId, id, taxonOwnerId, r, userId);
          childCounts.merge(T_MEDIA, 1, Integer::sum);
        }
      } else if (!bundle.media().isEmpty()) {
        issues.add(new ClbImportIssue(MEDIA_ENTITY, String.valueOf(taxonOwnerId),
            "usage is not accepted — media skipped"));
      }
    }

    if (included(entityTypes, T_ESTIMATE, defaultAll)) {
      if (ownerAccepted) {
        for (MappedEstimate est : bundle.estimates()) {
          EstimateRequest src = est.request();
          EstimateRequest r = new EstimateRequest(src.estimate(), src.type(),
              resolveRef(refIdMap, est.clbReferenceId()), src.remarks(), null);
          int id = idSeq.allocate(projectId, ESTIMATE_ENTITY);
          estimates.insert(projectId, id, taxonOwnerId, r, userId);
          childCounts.merge(T_ESTIMATE, 1, Integer::sum);
        }
      } else if (!bundle.estimates().isEmpty()) {
        issues.add(new ClbImportIssue(ESTIMATE_ENTITY, String.valueOf(taxonOwnerId),
            "usage is not accepted — estimate skipped"));
      }
    }

    if (included(entityTypes, T_PROPERTY, defaultAll)) {
      if (ownerAccepted) {
        for (MappedProperty prop : bundle.properties()) {
          PropertyRequest src = prop.request();
          PropertyRequest r = new PropertyRequest(src.property(), src.value(), src.page(),
              resolveRef(refIdMap, prop.clbReferenceId()), src.remarks(), null);
          int id = idSeq.allocate(projectId, PROPERTY_ENTITY);
          properties.insert(projectId, id, taxonOwnerId, r, userId);
          childCounts.merge(T_PROPERTY, 1, Integer::sum);
        }
      } else if (!bundle.properties().isEmpty()) {
        issues.add(new ClbImportIssue(PROPERTY_ENTITY, String.valueOf(taxonOwnerId),
            "usage is not accepted — property skipped"));
      }
    }

    if (included(entityTypes, T_TYPE_MATERIAL, defaultAll)) {
      for (var e : bundle.typeMaterialByNameId().entrySet()) {
        Integer ownerId = nameIdMap.get(e.getKey());
        if (ownerId == null) {
          issues.add(new ClbImportIssue(TYPE_MATERIAL_ENTITY, e.getKey(), "owning name not found"));
          continue;
        }
        for (MappedTypeMaterial tm : e.getValue()) {
          TypeMaterialRequest src = tm.request();
          TypeMaterialRequest r = new TypeMaterialRequest(src.citation(), src.status(), src.institutionCode(),
              src.catalogNumber(), src.occurrenceId(), src.locality(), src.country(), src.collector(),
              src.date(), src.sex(), resolveRef(refIdMap, tm.clbReferenceId()), src.link(), src.remarks(),
              src.latitude(), src.longitude(), null);
          int id = idSeq.allocate(projectId, TYPE_MATERIAL_ENTITY);
          typeMaterials.insert(projectId, id, ownerId, r, userId);
          childCounts.merge(T_TYPE_MATERIAL, 1, Integer::sum);
        }
      }
    }

    if (included(entityTypes, T_NAME_RELATION, defaultAll)) {
      for (MappedNameRelation rel : bundle.nameRelations()) {
        Integer ownerId = usageIdMap.get(rel.clbUsageId());
        Integer relatedId = usageIdMap.get(rel.clbRelatedUsageId());
        if (ownerId == null || relatedId == null) {
          issues.add(new ClbImportIssue(NAME_RELATION_ENTITY, rel.clbUsageId(),
              "owning or related usage not found"));
          continue;
        }
        NameRelationRequest src = rel.request();
        NameRelationRequest r = new NameRelationRequest(relatedId, src.type(),
            resolveRef(refIdMap, rel.clbReferenceId()), src.page(), src.remarks(), null);
        int id = idSeq.allocate(projectId, NAME_RELATION_ENTITY);
        nameRelations.insert(projectId, id, ownerId, r, userId);
        childCounts.merge(T_NAME_RELATION, 1, Integer::sum);
      }
    }
  }

  // --- small helpers ---------------------------------------------------------------------------

  // null/empty entityTypes -> `defaultAll` (true for modes A/B, false for mode C -- see
  // ClbImportRequest's javadoc); otherwise an exact membership check against the caller's choice.
  private static boolean included(Set<String> entityTypes, String type, boolean defaultAll) {
    return (entityTypes == null || entityTypes.isEmpty()) ? defaultAll : entityTypes.contains(type);
  }

  private static Integer resolveRef(Map<String, Integer> refIdMap, String clbRefId) {
    return clbRefId == null ? null : refIdMap.get(clbRefId);
  }

  private static List<Integer> resolveRefList(Map<String, Integer> refIdMap, List<String> clbRefIds) {
    if (clbRefIds == null || clbRefIds.isEmpty()) {
      return null;
    }
    List<Integer> out = new ArrayList<>();
    for (String id : clbRefIds) {
      Integer rid = refIdMap.get(id);
      if (rid != null) {
        out.add(rid);
      }
    }
    return out.isEmpty() ? null : out;
  }

  private static boolean hasTaxonInfo(NameUsage u) {
    return u.getExtinct() != null || u.getEnvironment() != null
        || u.getTemporalRangeStart() != null || u.getTemporalRangeEnd() != null;
  }

  // "col" when the request's datasetKey is the configured COL dataset (coldp.col.match-dataset,
  // normally 3LXR -- the SAME default ClbMatchClient/ColMatchService use for "the" COL checklist),
  // else the raw datasetKey itself -- e.g. importing straight from a non-COL source dataset stamps
  // that dataset's own key as the provenance scope instead.
  private String scopeFor(String datasetKey) {
    return colDataset.equalsIgnoreCase(datasetKey) ? "col" : datasetKey;
  }

  private Project requireEditorProject(int userId, int projectId) {
    String role = projects.requireRole(userId, projectId);
    if (!role.equals(Role.OWNER.dbValue()) && !role.equals(Role.EDITOR.dbValue())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "owner or editor required");
    }
    return projectMapper.findById(projectId);
  }
}
