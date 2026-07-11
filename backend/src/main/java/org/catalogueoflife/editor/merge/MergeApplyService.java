package org.catalogueoflife.editor.merge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.catalogueoflife.editor.merge.dto.Candidate;
import org.catalogueoflife.editor.merge.dto.Category;
import org.catalogueoflife.editor.merge.dto.MergePlan;
import org.catalogueoflife.editor.merge.dto.MergeRunResponse;
import org.catalogueoflife.editor.merge.dto.MergeRunResponse.MergeIssue;
import org.catalogueoflife.editor.merge.dto.Mode;
import org.catalogueoflife.editor.name.IdSeqMapper;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.name.NameUsageMapper;
import org.catalogueoflife.editor.name.NameUsageService;
import org.catalogueoflife.editor.name.Reference;
import org.catalogueoflife.editor.name.ReferenceMapper;
import org.catalogueoflife.editor.name.Status;
import org.catalogueoflife.editor.name.SynAccLink;
import org.catalogueoflife.editor.name.SynonymAcceptedMapper;
import org.catalogueoflife.editor.name.TaxonInfoMapper;
import org.catalogueoflife.editor.parse.NameParserService;
import org.catalogueoflife.editor.project.ProjectMapper;
import org.catalogueoflife.editor.project.ProjectService;
import org.catalogueoflife.editor.project.Role;
import org.gbif.nameparser.api.NomCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

// The supervised project-merge APPLY job (Task 6 of the merge plan): writes a PLANNED merge_run's
// stored plan into the target project -- references first, then name-usages -- and moves the run
// PLANNED -> APPLYING -> DONE|FAILED. Mirrors MergeService.start/computePlan's async shape (see
// its class javadoc) and coldp/imprt/ImportRunService's loadTransactional/loadReferences/
// loadNameUsages almost verbatim: the "source" here is another PROJECT read via mappers instead of
// a ColDP archive, and a MATCHED record's id-map entry is the EXISTING target id from the plan
// rather than a freshly allocated one -- everything else (the refIdMap/usageIdMap source-id ->
// new/target-id maps, the two-phase name-usage insert forced by name_usage's non-deferrable
// self-referencing parent_id/basionym_id FKs, the status inverse for synonym_accepted links) is
// the same shape.
//
// Task 6 deliberately does NOT reconcile a MATCHED record's scalar fields, children, or synonyms --
// only its id (kept stable) and a `src:<sourceId>` provenance CURIE (added to alternative_id
// regardless of mode) are touched. Mode-based scalar/relation reconciliation of matched records,
// child-entity migration, and the post-commit validate pass are Task 7; `mode` is accepted and
// persisted (startApply) here so the API contract is final, but this task's apply() body doesn't
// yet branch on it beyond "always stamp provenance, never touch anything else on a match".
@Service
public class MergeApplyService {

  private static final Logger log = LoggerFactory.getLogger(MergeApplyService.class);

  // id_seq entity strings -- must match ReferenceService.ENTITY/NameUsageService's own ENTITY
  // constant (see ImportRunService's identical REFERENCE_ENTITY/NAME_USAGE_ENTITY javadoc): id_seq's
  // per-(project, entity) counter is shared with every other writer of that entity, so reusing a
  // different string here would silently allocate ids from the wrong counter.
  private static final String REFERENCE_ENTITY = "reference";
  private static final String NAME_USAGE_ENTITY = "name_usage";

  // The identifier-scope prefix every provenance CURIE this job writes uses: "src:<sourceId>" on a
  // matched-or-newly-inserted target record's alternative_id, via NameUsageService.mergeScopedId
  // (which replaces any single existing src: entry, never accumulates more than one per record --
  // fine here, since one target record is only ever the match/copy target of ONE source record
  // within a single merge run).
  private static final String PROVENANCE_SCOPE = "src";

  private final MergeRunMapper runs;
  private final ProjectService projectService;
  private final ProjectMapper projects;
  private final IdSeqMapper idSeq;
  private final ReferenceMapper references;
  private final NameUsageMapper usages;
  private final SynonymAcceptedMapper synonymAccepted;
  private final TaxonInfoMapper taxonInfo;
  private final NameParserService parser;
  private final ObjectMapper json;

  // Self-reference through the Spring proxy so run()'s @Async and applyTransactional's
  // @Transactional actually go through their proxied annotations -- see ImportRunService/
  // MergeService's identical `self` field for the same reason: a plain `this.foo(...)` call
  // bypasses the proxy entirely. @Lazy avoids a circular bean-creation error (this bean depending
  // on itself while still being constructed).
  @Autowired
  @Lazy
  private MergeApplyService self;

  public MergeApplyService(MergeRunMapper runs, ProjectService projectService, ProjectMapper projects,
      IdSeqMapper idSeq, ReferenceMapper references, NameUsageMapper usages,
      SynonymAcceptedMapper synonymAccepted, TaxonInfoMapper taxonInfo, NameParserService parser,
      ObjectMapper json) {
    this.runs = runs;
    this.projectService = projectService;
    this.projects = projects;
    this.idSeq = idSeq;
    this.references = references;
    this.usages = usages;
    this.synonymAccepted = synonymAccepted;
    this.taxonInfo = taxonInfo;
    this.parser = parser;
    this.json = json;
  }

  // Authorizes the caller (owner/editor on the TARGET -- same write-adjacent tier as
  // MergeService.start/applyOverrides), requires the run to be PLANNED (else 409 -- apply is only
  // legal once against a freshly-computed-or-overridden plan; RUNNING/APPLYING/DONE/FAILED are all
  // the wrong phase), then moves it PLANNED -> APPLYING (runs.startApply, recording the curator's
  // chosen mode/transactional flag) on the REQUEST thread -- so the 202 response always reflects a
  // real, already-persisted APPLYING row -- before firing the actual write work through `self.run`
  // (@Async off MergeAsyncConfig.EXECUTOR_BEAN) so the request returns immediately. Same
  // TaskRejectedException handling as ImportRunService.start/MergeService.start: if the single-
  // thread, bounded-queue executor is full, self.run(...) throws synchronously at this call site
  // (before run()'s own try/catch ever gets a chance to run), so the just-started APPLYING row must
  // be explicitly failed here instead of being left stuck forever.
  public MergeRunResponse apply(int userId, int targetId, long runId, Mode mode, boolean transactional) {
    if (mode == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mode is required");
    }
    requireOwnerOrEditor(projectService.requireRole(userId, targetId));
    MergeRun run = requireRunInTarget(targetId, runId);
    requirePlanned(run);
    runs.startApply(runId, mode.name(), transactional);
    int sourceId = run.getSourceProjectId().intValue();
    try {
      self.run(runId, sourceId, targetId, mode, transactional, userId);
    } catch (TaskRejectedException e) {
      runs.fail(runId, "merge apply service busy — try again later");
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
          "merge apply service busy, try again later");
    }
    return MergeRunResponse.of(runs.findById(runId), json);
  }

  // The async body: runs on MergeAsyncConfig's single-thread pool, off the request thread that
  // called apply(). Any exception escaping the worker (transactional or not) marks the whole row
  // FAILED with the exception's message rather than leaving it stuck APPLYING forever -- there is
  // no caller left to propagate the exception to once we're here, same contract as
  // ImportRunService.run/MergeService.computePlan.
  @Async(MergeAsyncConfig.EXECUTOR_BEAN)
  public void run(long runId, int sourceId, int targetId, Mode mode, boolean transactional, int userId) {
    try {
      List<MergeIssue> issues = transactional
          ? self.applyTransactional(runId, sourceId, targetId, mode, userId)
          : applyWorker(runId, sourceId, targetId, mode, userId);
      runs.finish(runId, issues.isEmpty() ? null : json.writeValueAsString(issues));
    } catch (Exception e) {
      log.warn("merge apply run {} failed (source {}, target {}): {}",
          runId, sourceId, targetId, e.getMessage(), e);
      runs.fail(runId, e.getMessage());
    }
  }

  // The transactional path: wraps the whole references+name-usages write in one transaction, so a
  // failure partway through rolls back everything already written this run -- the "safest" default
  // (ApplyMergeRequest.transactional defaults to true). Called through the @Lazy self proxy from
  // run() so @Transactional actually applies (a plain `this.applyWorker(...)` call from run() would
  // bypass the proxy). The non-transactional path (Task 6 scope: "a non-transactional straight
  // call is fine" -- real per-entity-type batching via TransactionTemplate is Task 7) calls
  // applyWorker directly from run() instead, with no wrapping transaction at all.
  @Transactional(rollbackFor = Exception.class)
  public List<MergeIssue> applyTransactional(long runId, int sourceId, int targetId, Mode mode, int userId) {
    return applyWorker(runId, sourceId, targetId, mode, userId);
  }

  // The actual apply: references then name-usages, exactly ImportRunService.loadTransactional's
  // order (nothing in either half ever points at a name-usage id created only later). `mode` is
  // threaded through for the Task 7 mode-based reconciliation of MATCHED records to plug into; this
  // task's body doesn't yet branch on it (see class javadoc).
  private List<MergeIssue> applyWorker(long runId, int sourceId, int targetId, Mode mode, int userId) {
    MergeRun run = runs.findById(runId);
    MergePlan plan = json.readValue(run.getPlan(), MergePlan.class);
    Map<String, Candidate> refPlan = indexBySourceId(plan.references());
    Map<String, Candidate> namePlan = indexBySourceId(plan.names());
    NomCode targetNomCode = projects.findById(targetId).getNomCode();

    List<MergeIssue> issues = new ArrayList<>();
    // Source id (as a string, matching Candidate.sourceId's own shape) -> the id this record now
    // has IN THE TARGET project: for a MATCHED record this is the EXISTING target id straight out
    // of the plan (never freshly allocated -- the whole point of a supervised merge is that a
    // matched record's id never moves); for a NEW record it's the id idSeq.allocate just minted for
    // the brand-new target row. Every downstream remap (a name-usage's publishedInReferenceId/
    // referenceId[]/parent_id/basionym_id, a synonym's synonym_accepted target) resolves through
    // these two maps rather than ever re-deriving a target id from scratch.
    Map<String, Integer> refIdMap = new HashMap<>();
    Map<String, Integer> usageIdMap = new HashMap<>();

    applyReferences(sourceId, targetId, userId, refPlan, refIdMap);
    applyNameUsages(sourceId, targetId, userId, targetNomCode, namePlan, refIdMap, usageIdMap, issues);

    return issues;
  }

  // References: no forward dependency on anything else (a reference's own author/editor columns
  // are free-text citation strings, never usage/author ids -- see ImportRunService.loadReferences'
  // identical reasoning), so these are always resolved first, before name-usages need refIdMap to
  // remap publishedInReferenceId/referenceId[].
  //
  // A source reference absent from the plan (shouldn't happen -- NameMatcher/ReferenceMatcher.match
  // return exactly one Candidate per source record) is guarded as NEW rather than silently
  // dropped -- same defensive fallback for any category OTHER than MATCHED (a curator-unreviewed
  // POSSIBLE_HOMONYM/POSSIBLE_FUZZY/POSSIBLE left in the plan at apply time is added as new too,
  // never silently discarded or silently merged into a candidate the curator never confirmed --
  // see Mode's javadoc: "NEW records ... are always added").
  private void applyReferences(int sourceId, int targetId, int userId, Map<String, Candidate> refPlan,
      Map<String, Integer> refIdMap) {
    for (Reference src : references.findAllByProject(sourceId)) {
      String srcId = String.valueOf(src.getId());
      Candidate c = refPlan.get(srcId);
      if (c != null && c.category() == Category.MATCHED) {
        int targetRefId = Integer.parseInt(c.targetId());
        refIdMap.put(srcId, targetRefId);
        stampReferenceProvenance(targetId, targetRefId, srcId, userId);
      } else {
        Reference r = buildNewReference(src, targetId, userId, srcId);
        r.setId(idSeq.allocate(targetId, REFERENCE_ENTITY));
        references.insert(r);
        refIdMap.put(srcId, r.getId());
      }
    }
  }

  // Name-usages: two-phase, exactly like ImportRunService.loadNameUsages (see its javadoc for the
  // non-deferrable self-FK reasoning) -- Pass 1 maps MATCHED usages (no insert) and inserts every
  // NEW usage with parent_id/basionym_id left NULL; only once Pass 1 has finished and usageIdMap
  // covers EVERY source usage (matched -> target id, new -> freshly allocated id) does Pass 2
  // resolve the self-referencing hierarchy columns. This is also exactly "attach at nearest matched
  // ancestor": a NEW accepted usage's source parent_id is looked up in usageIdMap same as any other
  // reference -- if that parent MATCHED, the lookup lands on the target parent's stable id; if the
  // parent is also NEW, it lands on that sibling new id (set in this same Pass 1, before Pass 2
  // runs) -- so a whole chain of brand-new taxa under a matched genus resolves transitively with no
  // special-casing.
  private void applyNameUsages(int sourceId, int targetId, int userId, NomCode targetNomCode,
      Map<String, Candidate> namePlan, Map<String, Integer> refIdMap, Map<String, Integer> usageIdMap,
      List<MergeIssue> issues) {
    List<NameUsage> sourceUsages = usages.findAllByProject(sourceId);
    // Every (synonym_id, accepted_id) link in the SOURCE project, grouped by synonym -- this app's
    // own model already separates a synonym's accepted target(s) from parent_id (see NameUsage's
    // class doc: "synonyms link ... via the separate synonym_accepted table, NOT via parentId"), so
    // unlike ColDP import (which has to invert a single parentID column per archive row) there is
    // nothing to invert here: a source non-accepted usage's own accepted target(s) come straight off
    // this map. LinkedHashMap/ordered value lists preserve findAllLinks' (synonym_id, accepted_id)
    // order, so a pro-parte synonym's target links replay in the same relative order in the target.
    Map<Integer, List<Integer>> sourceSynonymLinks = synonymAccepted.findAllLinks(sourceId).stream()
        .collect(Collectors.groupingBy(SynAccLink::synonymId, LinkedHashMap::new,
            Collectors.mapping(SynAccLink::acceptedId, Collectors.toList())));

    record Pending(NameUsage source, NameUsage target) {}
    List<Pending> pending = new ArrayList<>();

    // Pass 1.
    for (NameUsage src : sourceUsages) {
      String srcId = String.valueOf(src.getId());
      Candidate c = namePlan.get(srcId);
      if (c != null && c.category() == Category.MATCHED) {
        int targetUsageId = Integer.parseInt(c.targetId());
        usageIdMap.put(srcId, targetUsageId);
        stampUsageProvenance(targetId, targetUsageId, srcId, userId);
        // Mode-based scalar/relation reconciliation of a matched usage is Task 7 -- this task never
        // inserts, updates any other column, or touches taxon_info/synonym_accepted for a match.
      } else {
        // NEW (or any un-reviewed POSSIBLE_* -- see applyReferences' identical guard above).
        NameUsage tgt = buildNewUsage(src, targetId, userId, srcId, refIdMap, targetNomCode, issues);
        tgt.setId(idSeq.allocate(targetId, NAME_USAGE_ENTITY));
        usages.insert(tgt);
        if (tgt.getStatus() == Status.ACCEPTED && hasTaxonInfo(tgt)) {
          taxonInfo.upsert(targetId, tgt.getId(), tgt.getExtinct(), tgt.getEnvironment(),
              tgt.getTemporalRangeStart(), tgt.getTemporalRangeEnd());
        }
        usageIdMap.put(srcId, tgt.getId());
        pending.add(new Pending(src, tgt));
      }
    }

    // Pass 2 -- only for the NEW usages inserted above (a MATCHED usage's hierarchy is untouched
    // this task, per its class javadoc).
    for (Pending p : pending) {
      NameUsage src = p.source();
      NameUsage tgt = p.target();
      Integer basionymNewId = src.getBasionymId() == null ? null
          : usageIdMap.get(String.valueOf(src.getBasionymId()));

      if (tgt.getStatus() == Status.ACCEPTED) {
        Integer parentNewId = src.getParentId() == null ? null
            : usageIdMap.get(String.valueOf(src.getParentId()));
        if (src.getParentId() != null && parentNewId == null) {
          // The source parent resolved to nothing (unmatched AND not itself in this source project
          // -- shouldn't happen for a whole-project merge, but guarded rather than left to violate
          // a NOT-there parent_id): leave it a new ROOT rather than fail the whole apply.
          issues.add(new MergeIssue("name_usage", String.valueOf(src.getId()),
              "unanchored: " + tgt.getScientificName()));
        }
        usages.updateHierarchy(targetId, tgt.getId(), parentNewId, basionymNewId, userId);
      } else {
        // Status inverse (like ColDP import's identical Pass 2 branch): a non-accepted usage's
        // classification link is a synonym_accepted row, never parent_id.
        usages.updateHierarchy(targetId, tgt.getId(), null, basionymNewId, userId);
        int ordinal = 0;
        for (Integer acceptedSrcId : sourceSynonymLinks.getOrDefault(src.getId(), List.of())) {
          Integer acceptedNewId = usageIdMap.get(String.valueOf(acceptedSrcId));
          if (acceptedNewId == null) {
            issues.add(new MergeIssue("name_usage", String.valueOf(src.getId()),
                "synonym_accepted target " + acceptedSrcId + " not found"));
            continue;
          }
          // Pro-parte: a source synonym linked to N accepted usages yields N target links here,
          // ordinal 0, 1, ... -- same shape as ImportRunService's pro-parte re-merge.
          synonymAccepted.link(targetId, tgt.getId(), acceptedNewId, ordinal++);
        }
      }
    }
  }

  // Builds a brand-new target Reference from a NEW source reference: every citation-ish scalar
  // field copied verbatim (mode never applies to a NEW record -- there's nothing to reconcile
  // against), alternative_id carrying the source's own ids plus the src:<sourceId> provenance CURIE.
  private Reference buildNewReference(Reference src, int targetId, int userId, String srcId) {
    Reference r = new Reference();
    r.setProjectId(targetId);
    r.setModifiedBy(userId);
    r.setCitation(src.getCitation());
    r.setType(src.getType());
    r.setAuthor(src.getAuthor());
    r.setEditor(src.getEditor());
    r.setTitle(src.getTitle());
    r.setContainerTitle(src.getContainerTitle());
    r.setIssued(src.getIssued());
    r.setVolume(src.getVolume());
    r.setIssue(src.getIssue());
    r.setPage(src.getPage());
    r.setPublisher(src.getPublisher());
    r.setDoi(src.getDoi());
    r.setIsbn(src.getIsbn());
    r.setIssn(src.getIssn());
    r.setLink(src.getLink());
    r.setAccessed(src.getAccessed());
    r.setRemarks(src.getRemarks());
    r.setAlternativeId(withProvenance(src.getAlternativeId(), srcId));
    return r;
  }

  // Adds/refreshes the src:<sourceRefId> provenance CURIE on an already-existing MATCHED target
  // reference, via the narrow updateAlternativeId (touches ONLY alternative_id -- see its javadoc);
  // every other scalar field on the target row is left exactly as it was, matching this task's "no
  // reconciliation of matched scalars" contract.
  private void stampReferenceProvenance(int targetId, int targetRefId, String srcId, int userId) {
    Reference target = references.findByIdInProject(targetId, targetRefId);
    if (target == null) {
      // Defensive guard: a plan produced against this same target should never point a MATCHED
      // candidate at an id that doesn't exist there. Nothing to stamp -- refIdMap already has the
      // (dangling) id from the plan, so downstream remaps still resolve to it as documented.
      return;
    }
    List<String> merged = NameUsageService.mergeScopedId(target.getAlternativeId(), PROVENANCE_SCOPE, srcId);
    references.updateAlternativeId(targetId, targetRefId, merged, userId, target.getVersion());
  }

  // Builds a brand-new target NameUsage from a NEW source usage: name/atomized/status/remarks
  // fields copied, publishedInReferenceId/referenceId[] remapped via refIdMap (an unresolved entry
  // is dropped -- surfaced as an issue -- rather than left pointing at a source-project id that
  // means nothing in the target), extinct/environment/temporalRange only copied for an ACCEPTED
  // usage (same taxon_info gating as ImportRunService.insertPrimaryUsage), alternative_id carrying
  // the source's own ids plus the src:<sourceUsageId> provenance CURIE. parent_id/basionym_id are
  // deliberately left unset (null) here -- Pass 2 in applyNameUsages resolves them once usageIdMap
  // is complete. parseInto is called last (matching NameUsageService.create/ImportRunService's own
  // parse-before-insert order) since it authoritatively re-derives every atomized field from
  // scientificName/authorship/rank regardless of what was just copied -- copying them first is
  // "safe" (the brief's own wording) rather than load-bearing.
  private NameUsage buildNewUsage(NameUsage src, int targetId, int userId, String srcId,
      Map<String, Integer> refIdMap, NomCode targetNomCode, List<MergeIssue> issues) {
    NameUsage u = new NameUsage();
    u.setProjectId(targetId);
    u.setModifiedBy(userId);
    u.setStatus(src.getStatus());
    u.setOrdinal(src.getOrdinal());
    u.setNamePhrase(src.getNamePhrase());
    u.setScientificName(src.getScientificName());
    u.setAuthorship(src.getAuthorship());
    u.setRank(src.getRank());
    u.setUninomial(src.getUninomial());
    u.setGenus(src.getGenus());
    u.setInfragenericEpithet(src.getInfragenericEpithet());
    u.setSpecificEpithet(src.getSpecificEpithet());
    u.setInfraspecificEpithet(src.getInfraspecificEpithet());
    u.setCultivarEpithet(src.getCultivarEpithet());
    u.setNotho(src.getNotho());
    u.setCombinationAuthorship(src.getCombinationAuthorship());
    u.setCombinationExAuthorship(src.getCombinationExAuthorship());
    u.setCombinationAuthorshipYear(src.getCombinationAuthorshipYear());
    u.setBasionymAuthorship(src.getBasionymAuthorship());
    u.setBasionymExAuthorship(src.getBasionymExAuthorship());
    u.setBasionymAuthorshipYear(src.getBasionymAuthorshipYear());
    u.setSanctioningAuthor(src.getSanctioningAuthor());
    u.setNomStatus(src.getNomStatus());
    u.setPublishedInYear(src.getPublishedInYear());
    u.setPublishedInPage(src.getPublishedInPage());
    u.setPublishedInPageLink(src.getPublishedInPageLink());
    u.setGender(src.getGender());
    u.setEtymology(src.getEtymology());
    u.setRemarks(src.getRemarks());

    if (src.getStatus() == Status.ACCEPTED) {
      u.setExtinct(src.getExtinct());
      u.setEnvironment(src.getEnvironment());
      u.setTemporalRangeStart(src.getTemporalRangeStart());
      u.setTemporalRangeEnd(src.getTemporalRangeEnd());
    }

    if (src.getPublishedInReferenceId() != null) {
      Integer refId = refIdMap.get(String.valueOf(src.getPublishedInReferenceId()));
      if (refId == null) {
        issues.add(new MergeIssue("name_usage", srcId,
            "publishedInReferenceId " + src.getPublishedInReferenceId() + " not resolved"));
      }
      u.setPublishedInReferenceId(refId);
    }
    if (src.getReferenceId() != null && !src.getReferenceId().isEmpty()) {
      List<Integer> mapped = new ArrayList<>();
      for (Integer rid : src.getReferenceId()) {
        Integer newRid = refIdMap.get(String.valueOf(rid));
        if (newRid == null) {
          issues.add(new MergeIssue("name_usage", srcId, "referenceID " + rid + " not resolved"));
        } else {
          mapped.add(newRid);
        }
      }
      u.setReferenceId(mapped.isEmpty() ? null : mapped);
    }

    u.setAlternativeId(withProvenance(src.getAlternativeId(), srcId));

    parser.parseInto(u, targetNomCode);
    // Same UNRANKED fallback as ImportRunService.insertPrimaryUsage: parseInto only (re-)sets rank
    // when the parse itself yields one, and name_usage.rank is NOT NULL.
    if (u.getRank() == null || u.getRank().isBlank()) {
      u.setRank("unranked");
    }
    return u;
  }

  // Adds/refreshes the src:<sourceUsageId> provenance CURIE on an already-existing MATCHED target
  // usage, via the narrow updateAlternativeId (touches ONLY alternative_id) -- no other column, no
  // taxon_info/synonym_accepted change; mirrors stampReferenceProvenance above.
  private void stampUsageProvenance(int targetId, int targetUsageId, String srcId, int userId) {
    NameUsage target = usages.findByIdInProject(targetId, targetUsageId);
    if (target == null) {
      return; // Defensive guard -- see stampReferenceProvenance's identical comment.
    }
    List<String> merged = NameUsageService.mergeScopedId(target.getAlternativeId(), PROVENANCE_SCOPE, srcId);
    usages.updateAlternativeId(targetId, targetUsageId, merged, userId, target.getVersion());
  }

  private static List<String> withProvenance(List<String> existing, String srcId) {
    return NameUsageService.mergeScopedId(existing, PROVENANCE_SCOPE, srcId);
  }

  private static boolean hasTaxonInfo(NameUsage u) {
    return u.getExtinct() != null || u.getEnvironment() != null
        || u.getTemporalRangeStart() != null || u.getTemporalRangeEnd() != null;
  }

  private static Map<String, Candidate> indexBySourceId(List<Candidate> list) {
    Map<String, Candidate> map = new HashMap<>();
    for (Candidate c : list) {
      map.put(c.sourceId(), c);
    }
    return map;
  }

  private MergeRun requireRunInTarget(int targetId, long runId) {
    MergeRun run = runs.findById(runId);
    if (run == null || !Long.valueOf(targetId).equals(run.getTargetProjectId())) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "merge run not found");
    }
    return run;
  }

  private static void requirePlanned(MergeRun run) {
    if (!"PLANNED".equals(run.getStatus())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "merge run must be PLANNED to apply (was " + run.getStatus() + ")");
    }
  }

  private static void requireOwnerOrEditor(String role) {
    if (!Role.OWNER.dbValue().equals(role) && !Role.EDITOR.dbValue().equals(role)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "owner or editor required");
    }
  }
}
