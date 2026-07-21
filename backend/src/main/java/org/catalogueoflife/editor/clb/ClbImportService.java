package org.catalogueoflife.editor.clb;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.catalogueoflife.editor.validation.ValidationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
// ValidationEvent as each row is inserted -- a CLB import is small and additive, not a bulk
// archive load), and NameParserService.parseInto as the same safety net that (re-)derives the
// atomized name fields ImportRunService.insertPrimaryUsage relies on. Same as ColDP import
// though, a best-effort post-import validation pass DOES run at the end (see importFromClb's
// final revalidateTouched call) so imported usages still show up in the Issues panel without
// needing an unrelated edit to trigger a revalidate.
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

  private static final Logger log = LoggerFactory.getLogger(ClbImportService.class);

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
  private final ValidationService validationService;
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
      NameRelationMapper nameRelations, ValidationService validationService,
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
    this.validationService = validationService;
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
    // Every usage this import touches -- the focal usage itself (a mode-A/B import attaches new
    // children to it, and mode C writes straight onto it) plus every newly-inserted usage
    // (accepted AND synonym) -- collected so the best-effort revalidateTouched pass at the end of
    // each branch below can give every one of them a fresh ValidationEvent (see this class's own
    // header comment and revalidateTouched's javadoc for why: raw mapper inserts carry none).
    Set<Integer> touched = new LinkedHashSet<>();
    touched.add(focalUsageId);
    // Fix 1: name relations collected here (by insertChildEntities) instead of inserted inline --
    // see insertPendingNameRelations' own javadoc for why relatedUsageId specifically needs to wait
    // for the WHOLE import's usageIdMap, not just this one bundle's.
    List<PendingNameRelation> pendingNameRelations = new ArrayList<>();

    if (req.mode() == ImportMode.UPDATE_FOCAL) {
      MappedImport bundle = ClbUsageMapper.toCreateRequest(client.usageInfo(req.datasetKey(), req.sourceTaxonId()));
      RefResolver refResolver = new RefResolver(projectId, userId, scope, bundle.references(), refIdMap);
      MappedUsage mu = bundle.usage();
      if (mu.usage() == null) {
        issues.add(new ClbImportIssue(NAME_USAGE_ENTITY, mu.clbUsageId(), "CLB usage has no name — skipped"));
      } else {
        // The source's own usage/name CLB ids now stand in for the FOCAL usage: any TypeMaterial/
        // NameRelation entry the bundle carries against them (see insertChildEntities) must attach
        // to the focal usage, not a new one -- there is no new "accepted usage" insert in this mode.
        usageIdMap.put(mu.clbUsageId(), focalUsageId);
        nameIdMap.put(mu.clbNameId(), focalUsageId);
      }

      int synonymCount = 0;
      if (included(req.entityTypes(), T_SYNONYMS, false)) {
        synonymCount = insertSynonyms(projectId, userId, project, scope, bundle, focalUsageId,
            refResolver, usageIdMap, nameIdMap, touched, issues);
      }
      // Mode C's target is always the focal usage, guaranteed ACCEPTED by the guard above, so the
      // 5 taxon-scoped child types are always eligible here (no accepted-only skip needed).
      insertChildEntities(projectId, userId, bundle, focalUsageId, true, usageIdMap, nameIdMap, refResolver,
          req.entityTypes(), false, childCounts, issues, pendingNameRelations);
      insertPendingNameRelations(projectId, userId, pendingNameRelations, usageIdMap, childCounts, issues);
      revalidateTouched(projectId, touched);
      return new ClbImportSummary(0, synonymCount, refIdMap.size(), childCounts, issues);
    }

    Gathered gathered = gather(req.datasetKey(), req.sourceTaxonId(), req.mode());

    Map<String, MappedImport> bundleByClbId = new LinkedHashMap<>();
    for (String id : gathered.orderedIds()) {
      bundleByClbId.put(id, ClbUsageMapper.toCreateRequest(client.usageInfo(req.datasetKey(), id)));
    }
    // Fix 2: no upfront insert here -- refsById is just the merged CLB-id -> Reference LOOKUP the
    // on-demand RefResolver below draws from (first-seen wins on a shared id across bundles, same as
    // the old eager insertReferences pass used, just deferred instead of inserted immediately).
    Map<String, Reference> refsById = new LinkedHashMap<>();
    for (MappedImport b : bundleByClbId.values()) {
      b.references().forEach(refsById::putIfAbsent);
    }
    RefResolver refResolver = new RefResolver(projectId, userId, scope, refsById, refIdMap);

    int nameUsageCount = 0;
    int synonymCount = 0;
    boolean includeSynonyms = included(req.entityTypes(), T_SYNONYMS, true);
    for (String id : gathered.orderedIds()) {
      MappedImport bundle = bundleByClbId.get(id);
      MappedUsage mu = bundle.usage();
      if (mu.usage() == null) {
        // Fix 3: malformed CLB record (no Name at all) -- skip this usage entirely (no insert, no
        // usageIdMap/nameIdMap entry, no synonyms/child entities processed) rather than NPE'ing two
        // lines below. Any usage elsewhere in this import that lists `id` as its parent falls back
        // to a null parent_id (nullable column, see V3__name_core.sql) -- orphaned but not fatal.
        issues.add(new ClbImportIssue(NAME_USAGE_ENTITY, id, "CLB usage has no name — skipped"));
        continue;
      }
      NameUsage u = mu.usage();
      u.setProjectId(projectId);
      u.setModifiedBy(userId);
      int newId = idSeq.allocate(projectId, NAME_USAGE_ENTITY);
      u.setId(newId);
      String clbParentId = gathered.rootIds().contains(id) ? null : gathered.parentOf().get(id);
      u.setParentId(clbParentId == null ? focalUsageId : usageIdMap.get(clbParentId));
      u.setPublishedInReferenceId(refResolver.resolve(mu.clbPublishedInReferenceId()));
      u.setReferenceId(refResolver.resolveList(mu.clbReferenceIds()));
      u.setAlternativeId(NameUsageService.mergeScopedId(null, scope, id));
      parser.parseInto(u, project.getNomCode());
      if (u.getRank() == null || u.getRank().isBlank()) {
        u.setRank("unranked");
      }
      usages.insert(u);
      touched.add(newId);
      if (u.getStatus().isTaxon() && hasTaxonInfo(u)) {
        taxonInfo.upsert(projectId, newId, u.getExtinct(), u.getEnvironment(),
            u.getTemporalRangeStart(), u.getTemporalRangeEnd());
      }
      usageIdMap.put(mu.clbUsageId(), newId);
      nameIdMap.put(mu.clbNameId(), newId);
      nameUsageCount++;

      if (includeSynonyms) {
        synonymCount += insertSynonyms(projectId, userId, project, scope, bundle, newId,
            refResolver, usageIdMap, nameIdMap, touched, issues);
      }
      insertChildEntities(projectId, userId, bundle, newId, u.getStatus().isTaxon(),
          usageIdMap, nameIdMap, refResolver, req.entityTypes(), true, childCounts, issues,
          pendingNameRelations);
    }

    // Fix 1's final pass: usageIdMap is now complete for the whole gathered import, so a
    // relatedUsageId that pointed at a usage inserted LATER in the loop above resolves correctly.
    insertPendingNameRelations(projectId, userId, pendingNameRelations, usageIdMap, childCounts, issues);
    revalidateTouched(projectId, touched);
    return new ClbImportSummary(nameUsageCount, synonymCount, refIdMap.size(), childCounts, issues);
  }

  // Best-effort post-import validation, the SAME "log-and-swallow, never fail the import" contract
  // ImportRunService.run applies to its own post-commit validationService.revalidateProject call
  // after a ColDP import (see that class's identical comment): a validation bug or transient
  // failure must never turn an otherwise-successful CLB import into a failed one, so any exception
  // here is caught, logged, and dropped rather than propagated out of importFromClb. Deliberately
  // per-usage (revalidateUsage), not revalidateProject, since only `touched` -- the focal usage
  // plus whatever this one call actually inserted -- needs a fresh ValidationEvent, not the whole
  // project. revalidateUsage is @Transactional and manages its own transaction; importFromClb
  // itself is deliberately NOT @Transactional (see this class's header comment), so calling it here
  // (on the injected proxy, not `self`) does not nest inside anything.
  private void revalidateTouched(int projectId, Set<Integer> touched) {
    for (int usageId : touched) {
      try {
        validationService.revalidateUsage(projectId, usageId);
      } catch (Exception e) {
        log.warn("post-import revalidation failed for usage {} (project {}): {}",
            usageId, projectId, e.getMessage(), e);
      }
    }
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
  //
  // `visited` guards against a malformed CLB response where the same CLB id is reachable more than
  // once (e.g. a diamond -- two different parents both listing the same child -- or an outright
  // cycle): CLB data is untrusted upstream, and without this a repeated id would be enqueued (and
  // later inserted) again each time it's reached, relying purely on the size cap above to eventually
  // catch a cycle rather than being robust to one directly. First-seen wins: a later, redundant
  // sighting of an already-visited id is simply dropped (not re-added to parentOf, not re-enqueued).
  private Gathered gather(String datasetKey, String sourceId, ImportMode mode) {
    List<String> ordered = new ArrayList<>();
    Map<String, String> parentOf = new HashMap<>();
    Set<String> roots = new LinkedHashSet<>();
    Set<String> visited = new HashSet<>();
    Deque<String> queue = new ArrayDeque<>();

    if (mode == ImportMode.TAXON_SUBTREE) {
      queue.add(sourceId);
      roots.add(sourceId);
      visited.add(sourceId);
    } else {
      // Fix 4: mirror TAXON_SUBTREE's own visited.add(sourceId) even though sourceId itself is
      // never enqueued here (CHILDREN_ONLY never inserts the source usage, see ImportMode's own
      // javadoc) -- without this, a malformed/cyclic CLB response where some descendant lists the
      // source as its own child would re-import the source, violating "source skipped".
      visited.add(sourceId);
      for (String cid : client.childrenIds(datasetKey, sourceId)) {
        if (visited.add(cid)) {
          queue.add(cid);
          roots.add(cid);
        }
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
        if (visited.add(cid)) {
          parentOf.put(cid, id);
          queue.add(cid);
        }
      }
    }
    return new Gathered(ordered, parentOf, roots);
  }

  // --- references (Fix 2: on-demand, memoized) ----------------------------------------------------

  // Resolves (and, on first use, inserts) a single CLB reference by clbRefId -- replaces the old
  // eager insertReferences pass, which pre-inserted EVERY reference in the source bundle(s) whether
  // or not anything selected by the caller's entityTypes actually cited it (in mode C especially,
  // with most entity types unchecked, that meant orphan `reference` rows nothing in the project
  // points at, plus an inflated ClbImportSummary.references() count). Now a reference is inserted
  // only the FIRST time something that actually cites it (a name-usage's referenceId/
  // publishedInReferenceId, a synonym's, or a child entity's -- see every resolve()/resolveList()
  // call site below) resolves it; every later lookup of the same clbRefId in the same import just
  // returns the cached id from refIdMap. `refsById` is the CLB-id -> mapped-Reference LOOKUP this
  // draws from -- importFromClb builds one per branch: mode C's own single bundle, or modes A/B's
  // merge across every gathered bundle (first-seen wins on a shared id, same as the old eager pass
  // used to do -- see ClbImportRequest's own "duplicates against existing target refs are allowed,
  // no matching" contract, which this still honours, just deferred instead of upfront). A non-static
  // inner class (not a standalone helper) purely so it can reach this service's own idSeq/references
  // mapper fields directly, without threading them through as extra parameters on top of the
  // per-call projectId/userId/scope/refsById/refIdMap state every call site would otherwise repeat.
  private final class RefResolver {
    private final int projectId;
    private final int userId;
    private final String scope;
    private final Map<String, Reference> refsById;
    private final Map<String, Integer> refIdMap;

    RefResolver(int projectId, int userId, String scope, Map<String, Reference> refsById,
        Map<String, Integer> refIdMap) {
      this.projectId = projectId;
      this.userId = userId;
      this.scope = scope;
      this.refsById = refsById;
      this.refIdMap = refIdMap;
    }

    Integer resolve(String clbRefId) {
      if (clbRefId == null) {
        return null;
      }
      Integer existing = refIdMap.get(clbRefId);
      if (existing != null) {
        return existing;
      }
      Reference r = refsById.get(clbRefId);
      if (r == null) {
        // cited but its definition was never present in any gathered bundle -- silently unresolved,
        // exactly like the old eager pass's refIdMap.get(...) miss would have left it.
        return null;
      }
      r.setProjectId(projectId);
      r.setModifiedBy(userId);
      r.setAlternativeId(NameUsageService.mergeScopedId(null, scope, clbRefId));
      r.setId(idSeq.allocate(projectId, REFERENCE_ENTITY));
      references.insert(r);
      refIdMap.put(clbRefId, r.getId());
      return r.getId();
    }

    List<Integer> resolveList(List<String> clbRefIds) {
      if (clbRefIds == null || clbRefIds.isEmpty()) {
        return null;
      }
      List<Integer> out = new ArrayList<>();
      for (String id : clbRefIds) {
        Integer rid = resolve(id);
        if (rid != null) {
          out.add(rid);
        }
      }
      return out.isEmpty() ? null : out;
    }
  }

  // --- synonyms -------------------------------------------------------------------------------

  // Inserts every synonym in `bundle` (parseInto'd + referenceId-remapped + provenance-stamped,
  // exactly like the accepted usage itself), linking each to `acceptedNewId` (ordinal 0, 1, 2, ...
  // in the bundle's own order -- CLB's flattened synonymy carries no pro-parte ambiguity for this
  // mapper to preserve, see ClbUsageMapper.toSynonyms' javadoc, so a plain increasing ordinal is
  // sufficient). Populates usageIdMap/nameIdMap for each synonym too, so a TypeMaterial/
  // NameRelation entry owned by a SYNONYM's name (not just the accepted taxon's) still resolves in
  // insertChildEntities below. Returns the number of synonyms actually inserted (Fix 3: a synonym
  // whose own Name is null -- see ClbUsageMapper.toMappedUsage's null-name branch -- is skipped with
  // an issue and does not count).
  private int insertSynonyms(int projectId, int userId, Project project, String scope, MappedImport bundle,
      int acceptedNewId, RefResolver refResolver, Map<String, Integer> usageIdMap,
      Map<String, Integer> nameIdMap, Set<Integer> touched, List<ClbImportIssue> issues) {
    int ordinal = 0;
    int inserted = 0;
    for (MappedUsage syn : bundle.synonyms()) {
      if (syn.usage() == null) {
        issues.add(new ClbImportIssue(NAME_USAGE_ENTITY, syn.clbUsageId(), "CLB usage has no name — skipped"));
        continue;
      }
      NameUsage u = syn.usage();
      u.setProjectId(projectId);
      u.setModifiedBy(userId);
      int newId = idSeq.allocate(projectId, NAME_USAGE_ENTITY);
      u.setId(newId);
      // parentId stays null: synonyms link via synonym_accepted, never the classification
      // parent_id (see NameUsage's own class doc / ImportRunService's identical Pass-2 split).
      u.setPublishedInReferenceId(refResolver.resolve(syn.clbPublishedInReferenceId()));
      u.setReferenceId(refResolver.resolveList(syn.clbReferenceIds()));
      u.setAlternativeId(NameUsageService.mergeScopedId(null, scope, syn.clbUsageId()));
      parser.parseInto(u, project.getNomCode());
      if (u.getRank() == null || u.getRank().isBlank()) {
        u.setRank("unranked");
      }
      usages.insert(u);
      touched.add(newId);
      usageIdMap.put(syn.clbUsageId(), newId);
      nameIdMap.put(syn.clbNameId(), newId);
      synonymAccepted.link(projectId, newId, acceptedNewId, ordinal++);
      inserted++;
    }
    return inserted;
  }

  // --- child entities ---------------------------------------------------------------------------

  // Inserts the selected (per `entityTypes`/`defaultAll`, see included()) child-entity kinds from
  // one gathered usage's whole bundle (covering both the accepted taxon AND its synonyms, since
  // UsageInfo -- and therefore MappedImport -- bundles them together). The 5 taxon-scoped kinds
  // (vernacular/distribution/media/estimate/property) always belong to `taxonOwnerId` itself (the
  // taxon this bundle is FOR -- see ClbUsageMapper's own "taxa only" javadoc) and require
  // `ownerIsTaxon` (mirrors AbstractChildEntityService.requireTaxonUsage /
  // ImportRunService.loadChildEntities' identical guard: a taxon is ACCEPTED or UNASSESSED, and a
  // CLB PROVISIONALLY_ACCEPTED collapses to our UNASSESSED (see ClbUsageMapper.toStatus), which is
  // still a taxon). TypeMaterial/NameRelation key off whichever name/usage in the bundle owns them
  // (nameIdMap/usageIdMap, populated for the taxon AND every synonym by the caller before this runs)
  // and apply to ANY usage status, so they never consult `ownerIsTaxon`.
  private void insertChildEntities(int projectId, int userId, MappedImport bundle, int taxonOwnerId,
      boolean ownerIsTaxon, Map<String, Integer> usageIdMap, Map<String, Integer> nameIdMap,
      RefResolver refResolver, Set<String> entityTypes, boolean defaultAll,
      Map<String, Integer> childCounts, List<ClbImportIssue> issues,
      List<PendingNameRelation> pendingNameRelations) {

    if (included(entityTypes, T_DISTRIBUTION, defaultAll)) {
      if (ownerIsTaxon) {
        for (MappedDistribution d : bundle.distributions()) {
          DistributionRequest src = d.request();
          DistributionRequest r = new DistributionRequest(src.area(), src.areaId(), src.gazetteer(),
              src.establishmentMeans(), src.threatStatus(), refResolver.resolve(d.clbReferenceId()),
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
      if (ownerIsTaxon) {
        for (MappedVernacular vn : bundle.vernaculars()) {
          VernacularRequest src = vn.request();
          VernacularRequest r = new VernacularRequest(src.name(), src.language(), src.country(), src.sex(),
              src.preferred(), refResolver.resolve(vn.clbReferenceId()), src.remarks(), null);
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
      if (ownerIsTaxon) {
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
      if (ownerIsTaxon) {
        for (MappedEstimate est : bundle.estimates()) {
          EstimateRequest src = est.request();
          EstimateRequest r = new EstimateRequest(src.estimate(), src.type(),
              refResolver.resolve(est.clbReferenceId()), src.remarks(), null);
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
      if (ownerIsTaxon) {
        for (MappedProperty prop : bundle.properties()) {
          PropertyRequest src = prop.request();
          PropertyRequest r = new PropertyRequest(src.property(), src.value(), src.page(),
              refResolver.resolve(prop.clbReferenceId()), src.remarks(), null);
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
              src.date(), src.sex(), refResolver.resolve(tm.clbReferenceId()), src.link(), src.remarks(),
              src.latitude(), src.longitude(), null);
          int id = idSeq.allocate(projectId, TYPE_MATERIAL_ENTITY);
          typeMaterials.insert(projectId, id, ownerId, r, userId);
          childCounts.merge(T_TYPE_MATERIAL, 1, Integer::sum);
        }
      }
    }

    // Fix 1: name relations are collected here, NOT inserted -- ownerId resolves inline (always
    // safe: the owner is this bundle's own accepted usage or one of its own synonyms, both already
    // in usageIdMap by the time this method runs -- see insertSynonyms' call ordering ahead of this
    // one in both importFromClb branches). relatedUsageId, though, may point at ANOTHER usage
    // elsewhere in this SAME gathered import that simply hasn't been inserted yet (a forward
    // reference, e.g. an early usage's basionym relation pointing at a later sibling) -- resolving
    // it here, before usageIdMap is complete for the whole import, is exactly the bug this defers
    // around. See importFromClb's insertPendingNameRelations call for the final pass that resolves
    // it once usageIdMap is complete.
    if (included(entityTypes, T_NAME_RELATION, defaultAll)) {
      for (MappedNameRelation rel : bundle.nameRelations()) {
        Integer ownerId = usageIdMap.get(rel.clbUsageId());
        if (ownerId == null) {
          issues.add(new ClbImportIssue(NAME_RELATION_ENTITY, rel.clbUsageId(), "owning usage not found"));
          continue;
        }
        NameRelationRequest src = rel.request();
        pendingNameRelations.add(new PendingNameRelation(ownerId, rel.clbRelatedUsageId(), src.type(),
            refResolver.resolve(rel.clbReferenceId()), src.page(), src.remarks(), rel.clbUsageId()));
      }
    }
  }

  // --- name relations: Fix 1's deferred final pass ------------------------------------------------

  // What insertChildEntities collects instead of inserting directly, above: ownerId is already a
  // real new usage id by construction (see that method's own comment), clbRelatedUsageId is the one
  // field that needed the WHOLE import's usageIdMap to be complete before it could be looked up.
  // sourceIdForIssues is the owning CLB usage id, reused as the issue's own sourceId if
  // clbRelatedUsageId still doesn't resolve below (mirrors the old inline check's identical wording,
  // just split across the two now-separate "owner not found" / "related not found" cases).
  private record PendingNameRelation(
      int ownerId, String clbRelatedUsageId, String type, Integer referenceId, String page, String remarks,
      String sourceIdForIssues) {}

  private void insertPendingNameRelations(int projectId, int userId, List<PendingNameRelation> pending,
      Map<String, Integer> usageIdMap, Map<String, Integer> childCounts, List<ClbImportIssue> issues) {
    for (PendingNameRelation p : pending) {
      Integer relatedId = usageIdMap.get(p.clbRelatedUsageId());
      if (relatedId == null) {
        // Genuinely not part of this import (or itself skipped, e.g. Fix 3's null-name guard) --
        // not a forward-reference timing issue, since usageIdMap is complete at this point.
        issues.add(new ClbImportIssue(NAME_RELATION_ENTITY, p.sourceIdForIssues(), "related usage not found"));
        continue;
      }
      NameRelationRequest r = new NameRelationRequest(relatedId, p.type(), p.referenceId(), p.page(),
          p.remarks(), null);
      int id = idSeq.allocate(projectId, NAME_RELATION_ENTITY);
      nameRelations.insert(projectId, id, p.ownerId(), r, userId);
      childCounts.merge(T_NAME_RELATION, 1, Integer::sum);
    }
  }

  // --- small helpers ---------------------------------------------------------------------------

  // null/empty entityTypes -> `defaultAll` (true for modes A/B, false for mode C -- see
  // ClbImportRequest's javadoc); otherwise an exact membership check against the caller's choice.
  private static boolean included(Set<String> entityTypes, String type, boolean defaultAll) {
    return (entityTypes == null || entityTypes.isEmpty()) ? defaultAll : entityTypes.contains(type);
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
