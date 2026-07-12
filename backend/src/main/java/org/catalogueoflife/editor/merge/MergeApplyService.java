package org.catalogueoflife.editor.merge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import life.catalogue.api.model.CslName;
import life.catalogue.api.vocab.Environment;
import org.catalogueoflife.editor.child.DistributionMapper;
import org.catalogueoflife.editor.child.EstimateMapper;
import org.catalogueoflife.editor.child.MediaMapper;
import org.catalogueoflife.editor.child.NameRelationMapper;
import org.catalogueoflife.editor.child.PropertyMapper;
import org.catalogueoflife.editor.child.TaxonChildMapper;
import org.catalogueoflife.editor.child.TypeMaterialMapper;
import org.catalogueoflife.editor.child.VernacularMapper;
import org.catalogueoflife.editor.child.dto.DistributionRequest;
import org.catalogueoflife.editor.child.dto.DistributionResponse;
import org.catalogueoflife.editor.child.dto.EstimateRequest;
import org.catalogueoflife.editor.child.dto.EstimateResponse;
import org.catalogueoflife.editor.child.dto.MediaRequest;
import org.catalogueoflife.editor.child.dto.MediaResponse;
import org.catalogueoflife.editor.child.dto.NameRelationRequest;
import org.catalogueoflife.editor.child.dto.NameRelationResponse;
import org.catalogueoflife.editor.child.dto.PropertyRequest;
import org.catalogueoflife.editor.child.dto.PropertyResponse;
import org.catalogueoflife.editor.child.dto.TypeMaterialRequest;
import org.catalogueoflife.editor.child.dto.TypeMaterialResponse;
import org.catalogueoflife.editor.child.dto.VernacularRequest;
import org.catalogueoflife.editor.child.dto.VernacularResponse;
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
import org.catalogueoflife.editor.validation.ValidationService;
import org.gbif.nameparser.api.NomCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

// The supervised project-merge APPLY job (Tasks 6+7 of the merge plan): writes a PLANNED merge_run's
// stored plan into the target project -- references, then name-usages, then the 7 child-entity
// types -- and moves the run PLANNED -> APPLYING -> DONE|FAILED. Mirrors MergeService.start/
// computePlan's async shape and coldp/imprt/ImportRunService's load*/loadChildEntities/best-effort
// post-commit revalidateProject almost verbatim: the "source" here is another PROJECT read via
// mappers instead of a ColDP archive, and a MATCHED record's id-map entry is the EXISTING target id
// from the plan rather than a freshly allocated one.
//
// Task 6 built the id-stable matched/new + two-phase-insert skeleton but deliberately did not
// branch on `Mode` for a matched record beyond an unconditional provenance stamp. Task 7 completes
// the picture:
//  - Mode now gates EVERY mutation of a matched record (scalar reconciliation, relation/child
//    additions, and the provenance stamp itself) -- see reconcileMatchedUsage/reconcileMatchedRef.
//    NEW_ONLY leaves a matched record completely untouched, including its alternative_id.
//  - Synonym links and the 7 child-entity types are migrated for NEW usages (straight add, like
//    import) and reconciled for MATCHED usages (FILL_GAPS: add-if-missing by content; OVERWRITE:
//    replace the target's set of any child TYPE the source has; NEW_ONLY: skip).
//  - The non-transactional path now genuinely batches its writes via TransactionTemplate
//    (coldp.merge.apply-batch, default 500) instead of relying on statement-level auto-commit; a
//    full-import plan (no MATCHED/POSSIBLE_* candidates at all) forces transactional=false, since
//    there is nothing a partial-failure rollback would ever need to protect a matched record from.
//  - A best-effort validationService.revalidateProject(targetId) runs after the apply has committed
//    (and after runs.finish) -- a validation throw must never flip a DONE run to FAILED (the import
//    final-review lesson; see ImportRunService.run's identical inner try/catch).
//
// Status is deliberately never flipped for a matched record by this class, in any mode: an
// ACCEPTED<->non-accepted change is a demote/promote with classification/synonym_accepted
// implications this narrow scalar reconciliation cannot safely orchestrate. A differing source
// status is only ever surfaced as a MergeIssue for the curator to resolve by hand.
@Service
public class MergeApplyService {

  private static final Logger log = LoggerFactory.getLogger(MergeApplyService.class);

  // id_seq entity strings -- must match ReferenceService.ENTITY/NameUsageService's own ENTITY
  // constant, and (for the 7 child types) each AbstractChildEntityService subclass's entity()/
  // TypeMaterialService/NameRelationService's own ENTITY constant -- see ImportRunService's
  // identical javadoc: id_seq's per-(project, entity) counter is shared with every other writer of
  // that entity, so reusing a different string here would silently allocate ids from the wrong
  // counter.
  private static final String REFERENCE_ENTITY = "reference";
  private static final String NAME_USAGE_ENTITY = "name_usage";
  private static final String TYPE_MATERIAL_ENTITY = "type_material";
  private static final String DISTRIBUTION_ENTITY = "distribution";
  private static final String VERNACULAR_ENTITY = "vernacular";
  private static final String MEDIA_ENTITY = "media";
  private static final String ESTIMATE_ENTITY = "estimate";
  private static final String NAME_RELATION_ENTITY = "name_relation";
  private static final String PROPERTY_ENTITY = "property";

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
  private final DistributionMapper distributions;
  private final EstimateMapper estimates;
  private final MediaMapper media;
  private final PropertyMapper properties;
  private final VernacularMapper vernaculars;
  private final TypeMaterialMapper typeMaterials;
  private final NameRelationMapper nameRelations;
  private final TaxonChildMapper taxonChild;
  private final ValidationService validationService;
  private final TransactionTemplate txTemplate;
  private final int applyBatchSize;

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
      ObjectMapper json, DistributionMapper distributions, EstimateMapper estimates, MediaMapper media,
      PropertyMapper properties, VernacularMapper vernaculars, TypeMaterialMapper typeMaterials,
      NameRelationMapper nameRelations, TaxonChildMapper taxonChild, ValidationService validationService,
      PlatformTransactionManager txManager,
      @Value("${coldp.merge.apply-batch:500}") int applyBatchSize) {
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
    this.distributions = distributions;
    this.estimates = estimates;
    this.media = media;
    this.properties = properties;
    this.vernaculars = vernaculars;
    this.typeMaterials = typeMaterials;
    this.nameRelations = nameRelations;
    this.taxonChild = taxonChild;
    this.validationService = validationService;
    this.txTemplate = new TransactionTemplate(txManager);
    this.applyBatchSize = applyBatchSize;
  }

  // Authorizes the caller (owner/editor on the TARGET), requires the run to be PLANNED (else 409),
  // then moves it PLANNED -> APPLYING (runs.startApply, recording the curator's chosen mode and the
  // RESOLVED transactional flag) on the REQUEST thread -- so the 202 response always reflects a
  // real, already-persisted APPLYING row -- before firing the actual write work through `self.run`
  // (@Async off MergeAsyncConfig.EXECUTOR_BEAN) so the request returns immediately.
  //
  // Full-import fast path: the plan is parsed here (once, not re-parsed inside the worker -- see
  // `plan` threaded through run()/applyTransactional/applyNonTransactional below) so a plan with NO
  // MATCHED/POSSIBLE_* candidate at all (every reference and every name-usage is NEW -- e.g. an
  // empty target) can force transactional=false regardless of what the caller asked for
  // (transactionalReq null OR true): a run with nothing matched has nothing a partial-failure
  // rollback would ever need to protect, and the reconcile/relation/child machinery below simply
  // never triggers for it anyway (every branch it would touch is gated on "this record matched").
  public MergeRunResponse apply(int userId, int targetId, long runId, Mode mode, Boolean transactionalReq) {
    if (mode == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mode is required");
    }
    requireOwnerOrEditor(projectService.requireRole(userId, targetId));
    MergeRun run = requireRunInTarget(targetId, runId);
    requirePlanned(run);
    MergePlan plan = json.readValue(run.getPlan(), MergePlan.class);
    boolean transactional = isFullImport(plan) ? false : (transactionalReq == null || transactionalReq);

    // requirePlanned above is only a friendly early 409 -- runs.startApply's SQL carries
    // "AND status = 'PLANNED'" and returns the affected row count, making the PLANNED -> APPLYING
    // transition an atomic compare-and-swap; see MergeRunMapper.startApply's own javadoc for why.
    int cas = runs.startApply(runId, mode.name(), transactional);
    if (cas == 0) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "merge run is not in PLANNED state (already applying or applied)");
    }
    int sourceId = run.getSourceProjectId().intValue();
    try {
      self.run(runId, sourceId, targetId, mode, transactional, userId, plan);
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
  // no caller left to propagate the exception to once we're here. On success, runs.finish commits
  // DONE first, then a best-effort validationService.revalidateProject runs in its OWN inner
  // try/catch -- exactly ImportRunService.run's shape: a post-commit validation throw must never be
  // allowed to fall into the outer catch and re-mark an already-committed, already-DONE run FAILED
  // (MergeRunMapper.fail's own "AND status <> 'DONE'" guard would no-op it in practice, but relying
  // on that here would be misleading -- the real fix is that a best-effort step's failure was never
  // a reason to report the apply as failed in the first place).
  @Async(MergeAsyncConfig.EXECUTOR_BEAN)
  public void run(long runId, int sourceId, int targetId, Mode mode, boolean transactional, int userId,
      MergePlan plan) {
    try {
      List<MergeIssue> issues = transactional
          ? self.applyTransactional(sourceId, targetId, mode, userId, plan)
          : applyNonTransactional(sourceId, targetId, mode, userId, plan);
      runs.finish(runId, issues.isEmpty() ? null : json.writeValueAsString(issues));
      try {
        validationService.revalidateProject(targetId);
      } catch (Exception ve) {
        log.warn("post-merge revalidation failed for run {} (target {}); merge stays DONE: {}",
            runId, targetId, ve.getMessage(), ve);
      }
    } catch (Exception e) {
      log.warn("merge apply run {} failed (source {}, target {}): {}",
          runId, sourceId, targetId, e.getMessage(), e);
      runs.fail(runId, e.getMessage());
    }
  }

  // The transactional path (ApplyMergeRequest.transactional's default): wraps the whole
  // references+name-usages+children write in ONE transaction, so a failure partway through rolls
  // back everything already written this run. Called through the @Lazy self proxy from run() so
  // @Transactional actually applies (a plain `this.applyWorker(...)` call from run() would bypass
  // the proxy).
  @Transactional(rollbackFor = Exception.class)
  public List<MergeIssue> applyTransactional(int sourceId, int targetId, Mode mode, int userId, MergePlan plan) {
    return applyWorker(sourceId, targetId, mode, userId, plan);
  }

  // The non-transactional path: no single enclosing transaction. Instead of relying on bare
  // statement-level auto-commit (Task 6's "a non-transactional straight call is fine"), every phase
  // is committed in chunks of `applyBatchSize` (coldp.merge.apply-batch, default 500) via
  // txTemplate.executeWithoutResult -- fewer, larger commits than one-row-per-transaction, while
  // still capping the blast radius of a mid-apply failure far below "the whole run". The four
  // phases run in the SAME dependency order as the transactional path (references, then name-usage
  // Pass 1, then Pass 2 -- which needs Pass 1's usageIdMap complete for EVERY source usage, not
  // just its own batch, so it cannot start until the Pass-1 loop below has fully finished -- then
  // matched-synonym-link reconciliation, then children); only the COMMIT boundaries are chunked,
  // never the Java-level ordering. A mid-batch failure propagates out of this method (uncaught) to
  // run()'s outer catch, which marks the run FAILED -- every batch committed before the failure is
  // already durable in the target project, so the run is re-runnable (a fresh compute-plan re-
  // matches everything already applied as MATCHED and simply continues from there).
  private List<MergeIssue> applyNonTransactional(int sourceId, int targetId, Mode mode, int userId,
      MergePlan plan) {
    Map<String, Candidate> refPlan = indexBySourceId(plan.references());
    Map<String, Candidate> namePlan = indexBySourceId(plan.names());
    NomCode targetNomCode = projects.findById(targetId).getNomCode();

    List<MergeIssue> issues = new ArrayList<>();
    Map<String, Integer> refIdMap = new HashMap<>();
    Map<String, Integer> usageIdMap = new HashMap<>();
    Set<String> matchedSrcIds = new HashSet<>();
    Set<Integer> acceptedTargetIds = new HashSet<>();

    List<Reference> sourceRefs = references.findAllByProject(sourceId);
    runInBatches(sourceRefs, batch -> {
      for (Reference src : batch) {
        applyOneReference(targetId, userId, mode, refPlan, refIdMap, issues, src);
      }
    });

    List<NameUsage> sourceUsages = usages.findAllByProject(sourceId);
    List<Pending> pending = new ArrayList<>();
    runInBatches(sourceUsages, batch -> {
      for (NameUsage src : batch) {
        Pending p = applyOneUsagePass1(targetId, userId, mode, targetNomCode, namePlan, refIdMap,
            usageIdMap, matchedSrcIds, acceptedTargetIds, issues, src);
        if (p != null) {
          pending.add(p);
        }
      }
    });

    Map<Integer, List<Integer>> sourceSynonymLinks = synonymAcceptedLinks(sourceId);
    runInBatches(pending, batch -> {
      for (Pending p : batch) {
        applyOneUsagePass2(targetId, userId, usageIdMap, sourceSynonymLinks, issues, p);
      }
    });

    if (mode != Mode.NEW_ONLY) {
      runInBatches(sourceUsages, batch -> {
        for (NameUsage src : batch) {
          applyOneMatchedSynonymLinks(targetId, matchedSrcIds, usageIdMap, sourceSynonymLinks, issues, src);
        }
      });
    }

    List<ChildWorkItem> childWork = buildChildWorkItems(usageIdMap, matchedSrcIds);
    runInBatches(childWork, batch -> {
      for (ChildWorkItem w : batch) {
        applyOneChildTypeForUsage(w.ops(), sourceId, targetId, userId, mode, w.srcUsageIdStr(),
            w.targetUsageId(), w.matched(), refIdMap, usageIdMap, acceptedTargetIds, issues);
      }
    });

    return issues;
  }

  // Runs `batchAction` once per chunk of up to applyBatchSize items, each chunk its own committed
  // transaction (txTemplate defaults to PROPAGATION_REQUIRED; there is no ambient transaction here
  // to join, so each execute() call starts and commits a fresh one). An empty `items` list is a
  // no-op (zero chunks).
  private <T> void runInBatches(List<T> items, Consumer<List<T>> batchAction) {
    for (int i = 0; i < items.size(); i += applyBatchSize) {
      List<T> batch = items.subList(i, Math.min(items.size(), i + applyBatchSize));
      txTemplate.executeWithoutResult(status -> batchAction.accept(batch));
    }
  }

  // The actual apply, single-pass (transactional path): references, then name-usages (Pass 1 +
  // Pass 2 + matched-synonym-link reconciliation), then the 7 child-entity types -- exactly
  // applyNonTransactional's phase order, just without per-batch commit boundaries (the whole thing
  // runs inside applyTransactional's one @Transactional).
  private List<MergeIssue> applyWorker(int sourceId, int targetId, Mode mode, int userId, MergePlan plan) {
    Map<String, Candidate> refPlan = indexBySourceId(plan.references());
    Map<String, Candidate> namePlan = indexBySourceId(plan.names());
    NomCode targetNomCode = projects.findById(targetId).getNomCode();

    List<MergeIssue> issues = new ArrayList<>();
    Map<String, Integer> refIdMap = new HashMap<>();
    Map<String, Integer> usageIdMap = new HashMap<>();
    Set<String> matchedSrcIds = new HashSet<>();
    Set<Integer> acceptedTargetIds = new HashSet<>();

    applyReferences(sourceId, targetId, userId, mode, refPlan, refIdMap, issues);
    applyNameUsages(sourceId, targetId, userId, mode, targetNomCode, namePlan, refIdMap, usageIdMap,
        matchedSrcIds, acceptedTargetIds, issues);
    applyChildEntities(sourceId, targetId, userId, mode, usageIdMap, matchedSrcIds, refIdMap,
        acceptedTargetIds, issues);

    return issues;
  }

  // ---------------------------------------------------------------------------------------------
  // References
  // ---------------------------------------------------------------------------------------------

  private void applyReferences(int sourceId, int targetId, int userId, Mode mode,
      Map<String, Candidate> refPlan, Map<String, Integer> refIdMap, List<MergeIssue> issues) {
    for (Reference src : references.findAllByProject(sourceId)) {
      applyOneReference(targetId, userId, mode, refPlan, refIdMap, issues, src);
    }
  }

  // A source reference IN the plan under any category OTHER than MATCHED (a curator-unreviewed
  // POSSIBLE_HOMONYM/POSSIBLE_FUZZY/POSSIBLE left in the plan at apply time, or a curator-confirmed/
  // rejected NEW) is added as NEW -- see Mode's javadoc: "NEW records ... are always added" in every
  // mode. A source reference with NO plan entry at all (Fix 3, final-review) is different: it wasn't
  // part of what the curator reviewed/approved at all -- e.g. added to the source project after
  // compute-plan ran, or after applyOverrides last recomputed the plan -- so it must NOT be silently
  // inserted as NEW (that would apply beyond the reviewed plan). Issue+skip instead. (A source row
  // that existed at plan time and was since DELETED is a different case, already handled: it simply
  // never reaches this loop at all, since applyOneReference is only ever called for LIVE source rows
  // -- see applyReferences/applyNonTransactional's references.findAllByProject(sourceId).)
  private void applyOneReference(int targetId, int userId, Mode mode, Map<String, Candidate> refPlan,
      Map<String, Integer> refIdMap, List<MergeIssue> issues, Reference src) {
    String srcId = String.valueOf(src.getId());
    Candidate c = refPlan.get(srcId);
    if (c == null) {
      issues.add(new MergeIssue("reference", srcId,
          "source changed since planning — not in reviewed plan, skipped"));
      return;
    }
    if (c.category() == Category.MATCHED) {
      Integer targetRefId = parseTargetId(c);
      if (targetRefId == null) {
        // A stored plan is untrusted input by apply time -- see parseTargetId's javadoc. Skip this
        // record entirely (never re-inserted as NEW, which would duplicate it) rather than blow up
        // the whole apply.
        issues.add(new MergeIssue("reference", srcId, "matched candidate has no valid target id — skipped"));
        return;
      }
      refIdMap.put(srcId, targetRefId);
      if (mode != Mode.NEW_ONLY) {
        // Mode gates EVERY mutation of a matched record, including the provenance stamp itself --
        // under NEW_ONLY a matched reference is left byte-for-byte untouched.
        reconcileMatchedRef(targetId, targetRefId, src, mode, userId, issues);
      }
    } else {
      Reference r = buildNewReference(src, targetId, userId, srcId);
      r.setId(idSeq.allocate(targetId, REFERENCE_ENTITY));
      references.insert(r);
      refIdMap.put(srcId, r.getId());
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
    r.setCitationManual(src.isCitationManual());
    r.setType(src.getType());
    r.setAuthor(src.getAuthor());
    r.setEditor(src.getEditor());
    r.setTitle(src.getTitle());
    r.setContainerTitle(src.getContainerTitle());
    r.setContainerTitleShort(src.getContainerTitleShort());
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

  // Mode-aware reconciliation of an already-existing MATCHED target reference: reads the target
  // fresh, merges each scalar field per `mode` (mergeString: OVERWRITE takes the source value
  // whenever the source has one, else keeps the target's; FILL_GAPS takes the source value only
  // when the target's own is blank -- see mergeString's javadoc), stamps the src:<sourceRefId>
  // provenance CURIE, and writes back via the full-row CAS update() ONLY if something actually
  // changed (the "only when something changed" instruction -- avoids a needless version bump/audit
  // noise on a rerun that changes nothing). Only ever called for mode != NEW_ONLY (see
  // applyOneReference).
  private void reconcileMatchedRef(int targetId, int targetRefId, Reference src, Mode mode, int userId,
      List<MergeIssue> issues) {
    Reference tgt = references.findByIdInProject(targetId, targetRefId);
    if (tgt == null) {
      // Defensive guard: a plan produced against this same target should never point a MATCHED
      // candidate at an id that doesn't exist there. Nothing to reconcile -- refIdMap already has
      // the (dangling) id from the plan, so downstream remaps still resolve to it as documented.
      return;
    }
    String srcId = String.valueOf(src.getId());
    boolean changed = false;
    String v;

    v = mergeString(tgt.getCitation(), src.getCitation(), mode);
    if (!Objects.equals(v, tgt.getCitation())) { tgt.setCitation(v); changed = true; }
    v = mergeString(tgt.getType(), src.getType(), mode);
    if (!Objects.equals(v, tgt.getType())) { tgt.setType(v); changed = true; }
    // author/editor are structured CslName lists (see V24__reference_csl.sql), not scalar strings --
    // mergeScalar (null-vs-non-null, same as the other non-String scalar fields below) rather than
    // mergeString (blank-string-vs-non-blank).
    List<CslName> vNames = mergeScalar(tgt.getAuthor(), src.getAuthor(), mode);
    if (!Objects.equals(vNames, tgt.getAuthor())) { tgt.setAuthor(vNames); changed = true; }
    vNames = mergeScalar(tgt.getEditor(), src.getEditor(), mode);
    if (!Objects.equals(vNames, tgt.getEditor())) { tgt.setEditor(vNames); changed = true; }
    v = mergeString(tgt.getTitle(), src.getTitle(), mode);
    if (!Objects.equals(v, tgt.getTitle())) { tgt.setTitle(v); changed = true; }
    v = mergeString(tgt.getContainerTitle(), src.getContainerTitle(), mode);
    if (!Objects.equals(v, tgt.getContainerTitle())) { tgt.setContainerTitle(v); changed = true; }
    v = mergeString(tgt.getContainerTitleShort(), src.getContainerTitleShort(), mode);
    if (!Objects.equals(v, tgt.getContainerTitleShort())) { tgt.setContainerTitleShort(v); changed = true; }
    v = mergeString(tgt.getIssued(), src.getIssued(), mode);
    if (!Objects.equals(v, tgt.getIssued())) { tgt.setIssued(v); changed = true; }
    v = mergeString(tgt.getVolume(), src.getVolume(), mode);
    if (!Objects.equals(v, tgt.getVolume())) { tgt.setVolume(v); changed = true; }
    v = mergeString(tgt.getIssue(), src.getIssue(), mode);
    if (!Objects.equals(v, tgt.getIssue())) { tgt.setIssue(v); changed = true; }
    v = mergeString(tgt.getPage(), src.getPage(), mode);
    if (!Objects.equals(v, tgt.getPage())) { tgt.setPage(v); changed = true; }
    v = mergeString(tgt.getPublisher(), src.getPublisher(), mode);
    if (!Objects.equals(v, tgt.getPublisher())) { tgt.setPublisher(v); changed = true; }
    v = mergeString(tgt.getDoi(), src.getDoi(), mode);
    if (!Objects.equals(v, tgt.getDoi())) { tgt.setDoi(v); changed = true; }
    v = mergeString(tgt.getIsbn(), src.getIsbn(), mode);
    if (!Objects.equals(v, tgt.getIsbn())) { tgt.setIsbn(v); changed = true; }
    v = mergeString(tgt.getIssn(), src.getIssn(), mode);
    if (!Objects.equals(v, tgt.getIssn())) { tgt.setIssn(v); changed = true; }
    v = mergeString(tgt.getLink(), src.getLink(), mode);
    if (!Objects.equals(v, tgt.getLink())) { tgt.setLink(v); changed = true; }
    v = mergeString(tgt.getAccessed(), src.getAccessed(), mode);
    if (!Objects.equals(v, tgt.getAccessed())) { tgt.setAccessed(v); changed = true; }
    v = mergeString(tgt.getRemarks(), src.getRemarks(), mode);
    if (!Objects.equals(v, tgt.getRemarks())) { tgt.setRemarks(v); changed = true; }

    List<String> newAltIds = withProvenance(tgt.getAlternativeId(), srcId);
    if (!Objects.equals(newAltIds, tgt.getAlternativeId())) { tgt.setAlternativeId(newAltIds); changed = true; }

    if (!changed) {
      return;
    }
    tgt.setModifiedBy(userId);
    int rows = references.update(tgt);
    if (rows == 0) {
      issues.add(new MergeIssue("reference", srcId, "matched target changed concurrently — reconciliation skipped"));
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Name-usages
  // ---------------------------------------------------------------------------------------------

  // One pending NEW usage awaiting Pass 2 (parent_id/basionym_id resolution) -- see
  // applyOneUsagePass1/applyOneUsagePass2.
  private record Pending(NameUsage source, NameUsage target) {}

  private Map<Integer, List<Integer>> synonymAcceptedLinks(int sourceId) {
    // Every (synonym_id, accepted_id) link in the SOURCE project, grouped by synonym -- this app's
    // own model already separates a synonym's accepted target(s) from parent_id (see NameUsage's
    // class doc), so there is nothing to invert (unlike ColDP import's single parentID column).
    // LinkedHashMap/ordered value lists preserve findAllLinks' (synonym_id, accepted_id) order, so a
    // pro-parte synonym's target links replay in the same relative order in the target.
    return synonymAccepted.findAllLinks(sourceId).stream()
        .collect(Collectors.groupingBy(SynAccLink::synonymId, LinkedHashMap::new,
            Collectors.mapping(SynAccLink::acceptedId, Collectors.toList())));
  }

  private void applyNameUsages(int sourceId, int targetId, int userId, Mode mode, NomCode targetNomCode,
      Map<String, Candidate> namePlan, Map<String, Integer> refIdMap, Map<String, Integer> usageIdMap,
      Set<String> matchedSrcIds, Set<Integer> acceptedTargetIds, List<MergeIssue> issues) {
    List<NameUsage> sourceUsages = usages.findAllByProject(sourceId);
    Map<Integer, List<Integer>> sourceSynonymLinks = synonymAcceptedLinks(sourceId);

    List<Pending> pending = new ArrayList<>();
    for (NameUsage src : sourceUsages) {
      Pending p = applyOneUsagePass1(targetId, userId, mode, targetNomCode, namePlan, refIdMap,
          usageIdMap, matchedSrcIds, acceptedTargetIds, issues, src);
      if (p != null) {
        pending.add(p);
      }
    }
    for (Pending p : pending) {
      applyOneUsagePass2(targetId, userId, usageIdMap, sourceSynonymLinks, issues, p);
    }
    if (mode != Mode.NEW_ONLY) {
      for (NameUsage src : sourceUsages) {
        applyOneMatchedSynonymLinks(targetId, matchedSrcIds, usageIdMap, sourceSynonymLinks, issues, src);
      }
    }
  }

  // Pass 1 for one source usage: MATCHED -> maps to the EXISTING target id (no insert) and, for
  // mode != NEW_ONLY, reconciles its scalars/provenance via reconcileMatchedUsage; anything else IN
  // THE PLAN (NEW, or an un-reviewed POSSIBLE_*) -> inserted with parent_id/basionym_id left NULL
  // (Pass 2 resolves them once usageIdMap is complete for every source usage), returned as a Pending
  // for Pass 2. Returns null for a MATCHED row (nothing left to do in Pass 2 for it) or a skipped
  // row. A source usage with NO plan entry at all (Fix 3, final-review -- see applyOneReference's
  // identical comment) is issue+skipped rather than silently inserted as an unreviewed NEW: it has
  // no usageIdMap entry either, so anything downstream that references it as a parent/basionym/
  // synonym-accepted target naturally falls back to its own "not resolved"/"unanchored" issue.
  private Pending applyOneUsagePass1(int targetId, int userId, Mode mode, NomCode targetNomCode,
      Map<String, Candidate> namePlan, Map<String, Integer> refIdMap, Map<String, Integer> usageIdMap,
      Set<String> matchedSrcIds, Set<Integer> acceptedTargetIds, List<MergeIssue> issues, NameUsage src) {
    String srcId = String.valueOf(src.getId());
    Candidate c = namePlan.get(srcId);
    if (c == null) {
      issues.add(new MergeIssue("name_usage", srcId,
          "source changed since planning — not in reviewed plan, skipped"));
      return null;
    }
    if (c.category() == Category.MATCHED) {
      Integer targetUsageId = parseTargetId(c);
      if (targetUsageId == null) {
        issues.add(new MergeIssue("name_usage", srcId, "matched candidate has no valid target id — skipped"));
        return null;
      }
      usageIdMap.put(srcId, targetUsageId);
      matchedSrcIds.add(srcId);
      if (mode != Mode.NEW_ONLY) {
        // Mode gates EVERY mutation of a matched record, including the provenance stamp itself --
        // under NEW_ONLY a matched usage is left byte-for-byte untouched (no scalar change, no
        // provenance CURIE, no relation/child addition -- reconcileMatchedUsage is simply never
        // called).
        reconcileMatchedUsage(targetId, targetUsageId, src, mode, userId, targetNomCode, refIdMap,
            acceptedTargetIds, issues);
      }
      return null;
    } else {
      NameUsage tgt = buildNewUsage(src, targetId, userId, srcId, refIdMap, targetNomCode, issues);
      tgt.setId(idSeq.allocate(targetId, NAME_USAGE_ENTITY));
      usages.insert(tgt);
      if (tgt.getStatus() == Status.ACCEPTED) {
        acceptedTargetIds.add(tgt.getId());
        if (hasTaxonInfo(tgt)) {
          taxonInfo.upsert(targetId, tgt.getId(), tgt.getExtinct(), tgt.getEnvironment(),
              tgt.getTemporalRangeStart(), tgt.getTemporalRangeEnd());
        }
      }
      usageIdMap.put(srcId, tgt.getId());
      return new Pending(src, tgt);
    }
  }

  // Pass 2 for one NEW (pending) usage: resolves basionym_id/parent_id (or, for a non-accepted
  // usage, its synonym_accepted link(s)) now that usageIdMap covers every source usage. Unchanged
  // from Task 6 -- MATCHED usages never reach Pass 2 (applyOneUsagePass1 returns null for them).
  private void applyOneUsagePass2(int targetId, int userId, Map<String, Integer> usageIdMap,
      Map<Integer, List<Integer>> sourceSynonymLinks, List<MergeIssue> issues, Pending p) {
    NameUsage src = p.source();
    NameUsage tgt = p.target();
    Integer basionymNewId = src.getBasionymId() == null ? null
        : usageIdMap.get(String.valueOf(src.getBasionymId()));

    if (tgt.getStatus() == Status.ACCEPTED) {
      Integer parentNewId = src.getParentId() == null ? null
          : usageIdMap.get(String.valueOf(src.getParentId()));
      if (src.getParentId() != null && parentNewId == null) {
        issues.add(new MergeIssue("name_usage", String.valueOf(src.getId()),
            "unanchored: " + tgt.getScientificName()));
      }
      usages.updateHierarchy(targetId, tgt.getId(), parentNewId, basionymNewId, userId);
    } else {
      usages.updateHierarchy(targetId, tgt.getId(), null, basionymNewId, userId);
      int ordinal = 0;
      for (Integer acceptedSrcId : sourceSynonymLinks.getOrDefault(src.getId(), List.of())) {
        Integer acceptedNewId = usageIdMap.get(String.valueOf(acceptedSrcId));
        if (acceptedNewId == null) {
          issues.add(new MergeIssue("name_usage", String.valueOf(src.getId()),
              "synonym_accepted target " + acceptedSrcId + " not found"));
          continue;
        }
        synonymAccepted.link(targetId, tgt.getId(), acceptedNewId, ordinal++);
      }
    }
  }

  // Reconciles a MATCHED synonym/misapplied/unassessed usage's synonym_accepted links: for each of
  // the source's own accepted targets that resolves in usageIdMap (matched-or-new), ensures a
  // matching target-side link exists -- link()'s ON CONFLICT DO NOTHING makes this naturally
  // idempotent (dedup for free; already-present links are simply a no-op). Only called for
  // mode != NEW_ONLY (see applyNameUsages) -- a matched synonym gaining a link to a brand-new
  // accepted usage is exactly the "add missing relations" half of FILL_GAPS/OVERWRITE. Runs AFTER
  // Pass 2 (not folded into Pass 1) because a matched synonym's accepted target may itself be a NEW
  // usage not yet inserted when Pass 1 visits this row.
  private void applyOneMatchedSynonymLinks(int targetId, Set<String> matchedSrcIds,
      Map<String, Integer> usageIdMap, Map<Integer, List<Integer>> sourceSynonymLinks,
      List<MergeIssue> issues, NameUsage src) {
    String srcId = String.valueOf(src.getId());
    if (!matchedSrcIds.contains(srcId) || src.getStatus() == Status.ACCEPTED) {
      return;
    }
    List<Integer> accIds = sourceSynonymLinks.getOrDefault(src.getId(), List.of());
    if (accIds.isEmpty()) {
      return;
    }
    Integer matchedTargetId = usageIdMap.get(srcId);
    if (matchedTargetId == null) {
      return; // shouldn't happen -- every matchedSrcIds entry has a usageIdMap entry (Pass 1).
    }
    int ordinal = synonymAccepted.countBySynonym(targetId, matchedTargetId);
    for (Integer acceptedSrcId : accIds) {
      Integer acceptedNewId = usageIdMap.get(String.valueOf(acceptedSrcId));
      if (acceptedNewId == null) {
        issues.add(new MergeIssue("name_usage", srcId, "synonym_accepted target " + acceptedSrcId + " not found"));
        continue;
      }
      if (synonymAccepted.link(targetId, matchedTargetId, acceptedNewId, ordinal) > 0) {
        ordinal++; // only advance on a genuinely new row -- a dedup no-op keeps the ordinal stable.
      }
    }
  }

  // Builds a brand-new target NameUsage from a NEW source usage. Unchanged from Task 6 -- see its
  // original javadoc: parent_id/basionym_id deliberately left unset (Pass 2 resolves them),
  // parseInto is authoritative over every atomized field regardless of what was just copied.
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
    if (u.getRank() == null || u.getRank().isBlank()) {
      u.setRank("unranked");
    }
    return u;
  }

  // Mode-aware reconciliation of an already-existing MATCHED target usage: reads the target fresh,
  // merges the "identity" fields (scientificName/authorship/rank) and every other independent
  // scalar per `mode`, re-derives the parsed/atomized fields via parseInto if the identity changed
  // (NameParserService.parseInto unconditionally clears and re-populates uninomial/genus/.../notho/
  // combination*/basionym*/sanctioningAuthor from scientificName+authorship+rank -- see its
  // javadoc -- so those columns are NEVER reconciled independently here; any earlier per-field merge
  // of them would just be discarded by parseInto), stamps the src:<sourceUsageId> provenance CURIE,
  // and writes back via the full-row CAS update() ONLY if something actually changed. Status is
  // NEVER changed by this method (see class javadoc) -- a differing source status is only ever
  // surfaced as an issue. Only ever called for mode != NEW_ONLY (see applyOneUsagePass1); always
  // records the target's ACCEPTED-ness into `acceptedTargetIds` (needed by the child-entity
  // accepted-only guard) regardless of whether anything else changed.
  private void reconcileMatchedUsage(int targetId, int targetUsageId, NameUsage src, Mode mode, int userId,
      NomCode targetNomCode, Map<String, Integer> refIdMap, Set<Integer> acceptedTargetIds,
      List<MergeIssue> issues) {
    NameUsage tgt = usages.findByIdInProject(targetId, targetUsageId);
    if (tgt == null) {
      // Defensive guard -- see reconcileMatchedRef's identical comment.
      return;
    }
    String srcId = String.valueOf(src.getId());
    boolean changed = false;
    boolean nameChanged = false;

    String newSciName = mergeString(tgt.getScientificName(), src.getScientificName(), mode);
    if (!Objects.equals(newSciName, tgt.getScientificName())) {
      tgt.setScientificName(newSciName);
      changed = true;
      nameChanged = true;
    }
    String newAuthorship = mergeString(tgt.getAuthorship(), src.getAuthorship(), mode);
    if (!Objects.equals(newAuthorship, tgt.getAuthorship())) {
      tgt.setAuthorship(newAuthorship);
      changed = true;
      nameChanged = true;
    }
    String newRank = mergeString(tgt.getRank(), src.getRank(), mode);
    if (!Objects.equals(newRank, tgt.getRank())) {
      tgt.setRank(newRank);
      changed = true;
      nameChanged = true;
    }

    String v;
    v = mergeString(tgt.getNamePhrase(), src.getNamePhrase(), mode);
    if (!Objects.equals(v, tgt.getNamePhrase())) { tgt.setNamePhrase(v); changed = true; }

    var nomStatus = mergeScalar(tgt.getNomStatus(), src.getNomStatus(), mode);
    if (!Objects.equals(nomStatus, tgt.getNomStatus())) { tgt.setNomStatus(nomStatus); changed = true; }

    Integer pubYear = mergeScalar(tgt.getPublishedInYear(), src.getPublishedInYear(), mode);
    if (!Objects.equals(pubYear, tgt.getPublishedInYear())) { tgt.setPublishedInYear(pubYear); changed = true; }

    v = mergeString(tgt.getPublishedInPage(), src.getPublishedInPage(), mode);
    if (!Objects.equals(v, tgt.getPublishedInPage())) { tgt.setPublishedInPage(v); changed = true; }

    v = mergeString(tgt.getPublishedInPageLink(), src.getPublishedInPageLink(), mode);
    if (!Objects.equals(v, tgt.getPublishedInPageLink())) { tgt.setPublishedInPageLink(v); changed = true; }

    var gender = mergeScalar(tgt.getGender(), src.getGender(), mode);
    if (!Objects.equals(gender, tgt.getGender())) { tgt.setGender(gender); changed = true; }

    v = mergeString(tgt.getEtymology(), src.getEtymology(), mode);
    if (!Objects.equals(v, tgt.getEtymology())) { tgt.setEtymology(v); changed = true; }

    v = mergeString(tgt.getRemarks(), src.getRemarks(), mode);
    if (!Objects.equals(v, tgt.getRemarks())) { tgt.setRemarks(v); changed = true; }

    // publishedInReferenceId / referenceId[] -- remapped through refIdMap (a source ref id means
    // nothing in the target project), same resolution as buildNewUsage's identical fields.
    Integer srcPubRefTarget = null;
    if (src.getPublishedInReferenceId() != null) {
      srcPubRefTarget = refIdMap.get(String.valueOf(src.getPublishedInReferenceId()));
      if (srcPubRefTarget == null) {
        issues.add(new MergeIssue("name_usage", srcId,
            "publishedInReferenceId " + src.getPublishedInReferenceId() + " not resolved"));
      }
    }
    Integer newPubRef = mergeScalar(tgt.getPublishedInReferenceId(), srcPubRefTarget, mode);
    if (!Objects.equals(newPubRef, tgt.getPublishedInReferenceId())) {
      tgt.setPublishedInReferenceId(newPubRef);
      changed = true;
    }

    List<Integer> srcRefIds = null;
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
      srcRefIds = mapped.isEmpty() ? null : mapped;
    }
    List<Integer> newRefIds = mergeList(tgt.getReferenceId(), srcRefIds, mode);
    if (!Objects.equals(newRefIds, tgt.getReferenceId())) { tgt.setReferenceId(newRefIds); changed = true; }

    // Status is deliberately NEVER changed by this path -- see class javadoc. A differing source
    // status is only ever surfaced as an issue for the curator to resolve by hand.
    if (src.getStatus() != null && tgt.getStatus() != src.getStatus()) {
      issues.add(new MergeIssue("name_usage", srcId, "status differs (target=" + tgt.getStatus()
          + ", source=" + src.getStatus() + ") — left unchanged; review manually"));
    }

    // taxon_info fields only ever reconciled while the target STAYS accepted (status is never
    // flipped above, so "stays accepted" == "already is accepted") -- mirrors buildNewUsage's
    // identical gate. acceptedTargetIds is recorded here UNCONDITIONALLY (even if nothing else
    // changed) -- the child-entity accepted-only guard needs it regardless.
    boolean taxonInfoChanged = false;
    if (tgt.getStatus() == Status.ACCEPTED) {
      acceptedTargetIds.add(targetUsageId);
      Boolean extinct = mergeScalar(tgt.getExtinct(), src.getExtinct(), mode);
      if (!Objects.equals(extinct, tgt.getExtinct())) { tgt.setExtinct(extinct); changed = true; taxonInfoChanged = true; }
      List<Environment> env = mergeList(tgt.getEnvironment(), src.getEnvironment(), mode);
      if (!Objects.equals(env, tgt.getEnvironment())) { tgt.setEnvironment(env); changed = true; taxonInfoChanged = true; }
      v = mergeString(tgt.getTemporalRangeStart(), src.getTemporalRangeStart(), mode);
      if (!Objects.equals(v, tgt.getTemporalRangeStart())) { tgt.setTemporalRangeStart(v); changed = true; taxonInfoChanged = true; }
      v = mergeString(tgt.getTemporalRangeEnd(), src.getTemporalRangeEnd(), mode);
      if (!Objects.equals(v, tgt.getTemporalRangeEnd())) { tgt.setTemporalRangeEnd(v); changed = true; taxonInfoChanged = true; }
    }

    // Provenance: always stamped whenever this method runs at all -- the caller only invokes it for
    // mode != NEW_ONLY (applyOneUsagePass1).
    List<String> newAltIds = withProvenance(tgt.getAlternativeId(), srcId);
    if (!Objects.equals(newAltIds, tgt.getAlternativeId())) { tgt.setAlternativeId(newAltIds); changed = true; }

    if (!changed) {
      return;
    }

    if (nameChanged) {
      parser.parseInto(tgt, targetNomCode);
      if (tgt.getRank() == null || tgt.getRank().isBlank()) {
        tgt.setRank("unranked");
      }
    }

    tgt.setModifiedBy(userId);
    int rows = usages.update(tgt);
    if (rows == 0) {
      issues.add(new MergeIssue("name_usage", srcId, "matched target changed concurrently — reconciliation skipped"));
      return;
    }
    if (taxonInfoChanged && hasTaxonInfo(tgt)) {
      taxonInfo.upsert(targetId, tgt.getId(), tgt.getExtinct(), tgt.getEnvironment(),
          tgt.getTemporalRangeStart(), tgt.getTemporalRangeEnd());
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Child entities (the 7 taxon/name child-entity types)
  // ---------------------------------------------------------------------------------------------

  @FunctionalInterface
  private interface ChildInsert<REQ> {
    void insert(int projectId, int id, int usageId, REQ r, int modifiedBy);
  }

  @FunctionalInterface
  private interface ChildRemap<REQ, RES> {
    // Builds a target-project Request from a source-project Response, remapping referenceID (via
    // refIdMap) and, for NameRelation only, relatedNameID (via usageIdMap). Returns null to skip
    // the row entirely (NameRelation's relatedUsageId is mandatory -- an unresolved one skips the
    // row with an issue, exactly like ImportRunService.loadNameRelation).
    REQ remap(RES source, Map<String, Integer> refIdMap, Map<String, Integer> usageIdMap, String srcRowId,
        List<MergeIssue> issues);
  }

  @FunctionalInterface
  private interface ChildDelete {
    void deleteAllForUsage(int projectId, int usageId);
  }

  // One child-entity type's full set of operations, composed rather than inherited (the 7 types
  // share no common mapper/DTO interface) so a single generic driver
  // (applyOneChildTypeForUsage/buildChildWorkItems) can walk all 7 uniformly. `acceptedOnly` mirrors
  // ImportRunService.loadChildEntities' split: the 5 taxon-scoped types (Distribution/Estimate/
  // Media/Property/Vernacular) apply only to an ACCEPTED target usage; TypeMaterial/NameRelation key
  // off the NAME and apply to any status. `contentKey`/`contentKeyFromResponse` extract the
  // meaningful (non-id/usageId/version) fields for FILL_GAPS' dedup-by-content comparison.
  private record ChildTypeOps<REQ, RES>(
      String idSeqEntity,
      boolean acceptedOnly,
      BiFunction<Integer, Integer, List<RES>> findByUsage,
      ChildInsert<REQ> insert,
      ChildRemap<REQ, RES> remap,
      Function<REQ, Object> contentKey,
      Function<RES, Object> contentKeyFromResponse,
      ChildDelete deleteAllForUsage) {}

  // One (child type, source usage) unit of work, flattened so both the transactional (unbatched)
  // and non-transactional (batched via runInBatches) child-entity phases can share the exact same
  // driver (applyOneChildTypeForUsage) and work-item list.
  private record ChildWorkItem(ChildTypeOps<?, ?> ops, String srcUsageIdStr, int targetUsageId, boolean matched) {}

  private static Object key(Object... vals) {
    return Arrays.asList(vals);
  }

  private static Integer resolveRefId(Map<String, Integer> refIdMap, String entity, String srcRowId,
      Integer srcRefId, List<MergeIssue> issues) {
    if (srcRefId == null) {
      return null;
    }
    Integer refId = refIdMap.get(String.valueOf(srcRefId));
    if (refId == null) {
      issues.add(new MergeIssue(entity, srcRowId, "referenceID " + srcRefId + " not resolved"));
    }
    return refId;
  }

  private List<ChildTypeOps<?, ?>> childTypeOps() {
    ChildTypeOps<DistributionRequest, DistributionResponse> distributionOps = new ChildTypeOps<>(
        DISTRIBUTION_ENTITY, true, distributions::findByUsage, distributions::insert,
        (row, refIdMap, usageIdMap, srcRowId, issues) -> new DistributionRequest(row.area(), row.areaId(),
            row.gazetteer(), row.establishmentMeans(), row.threatStatus(),
            resolveRefId(refIdMap, DISTRIBUTION_ENTITY, srcRowId, row.referenceId(), issues), row.remarks(), null),
        req -> key(req.area(), req.areaId(), req.gazetteer(), req.establishmentMeans(), req.threatStatus(),
            req.referenceId(), req.remarks()),
        res -> key(res.area(), res.areaId(), res.gazetteer(), res.establishmentMeans(), res.threatStatus(),
            res.referenceId(), res.remarks()),
        taxonChild::deleteDistributions);

    ChildTypeOps<EstimateRequest, EstimateResponse> estimateOps = new ChildTypeOps<>(
        ESTIMATE_ENTITY, true, estimates::findByUsage, estimates::insert,
        (row, refIdMap, usageIdMap, srcRowId, issues) -> new EstimateRequest(row.estimate(), row.type(),
            resolveRefId(refIdMap, ESTIMATE_ENTITY, srcRowId, row.referenceId(), issues), row.remarks(), null),
        req -> key(req.estimate(), req.type(), req.referenceId(), req.remarks()),
        res -> key(res.estimate(), res.type(), res.referenceId(), res.remarks()),
        taxonChild::deleteEstimates);

    ChildTypeOps<MediaRequest, MediaResponse> mediaOps = new ChildTypeOps<>(
        MEDIA_ENTITY, true, media::findByUsage, media::insert,
        (row, refIdMap, usageIdMap, srcRowId, issues) -> new MediaRequest(row.url(), row.type(), row.title(),
            row.creator(), row.license(), row.link(), row.remarks(), null),
        req -> key(req.url(), req.type(), req.title(), req.creator(), req.license(), req.link(), req.remarks()),
        res -> key(res.url(), res.type(), res.title(), res.creator(), res.license(), res.link(), res.remarks()),
        taxonChild::deleteMedia);

    ChildTypeOps<PropertyRequest, PropertyResponse> propertyOps = new ChildTypeOps<>(
        PROPERTY_ENTITY, true, properties::findByUsage, properties::insert,
        (row, refIdMap, usageIdMap, srcRowId, issues) -> new PropertyRequest(row.property(), row.value(),
            row.page(), resolveRefId(refIdMap, PROPERTY_ENTITY, srcRowId, row.referenceId(), issues),
            row.remarks(), null),
        req -> key(req.property(), req.value(), req.page(), req.referenceId(), req.remarks()),
        res -> key(res.property(), res.value(), res.page(), res.referenceId(), res.remarks()),
        taxonChild::deleteProperties);

    ChildTypeOps<VernacularRequest, VernacularResponse> vernacularOps = new ChildTypeOps<>(
        VERNACULAR_ENTITY, true, vernaculars::findByUsage, vernaculars::insert,
        (row, refIdMap, usageIdMap, srcRowId, issues) -> new VernacularRequest(row.name(), row.language(),
            row.country(), row.sex(), row.preferred(),
            resolveRefId(refIdMap, VERNACULAR_ENTITY, srcRowId, row.referenceId(), issues), row.remarks(), null),
        req -> key(req.name(), req.language(), req.country(), req.sex(), req.preferred(), req.referenceId(),
            req.remarks()),
        res -> key(res.name(), res.language(), res.country(), res.sex(), res.preferred(), res.referenceId(),
            res.remarks()),
        taxonChild::deleteVernaculars);

    ChildTypeOps<TypeMaterialRequest, TypeMaterialResponse> typeMaterialOps = new ChildTypeOps<>(
        TYPE_MATERIAL_ENTITY, false, typeMaterials::findByUsage, typeMaterials::insert,
        (row, refIdMap, usageIdMap, srcRowId, issues) -> new TypeMaterialRequest(row.citation(), row.status(),
            row.institutionCode(), row.catalogNumber(), row.occurrenceId(), row.locality(), row.country(),
            row.collector(), row.date(), row.sex(),
            resolveRefId(refIdMap, TYPE_MATERIAL_ENTITY, srcRowId, row.referenceId(), issues), row.link(),
            row.remarks(), row.latitude(), row.longitude(), null),
        req -> key(req.citation(), req.status(), req.institutionCode(), req.catalogNumber(), req.occurrenceId(),
            req.locality(), req.country(), req.collector(), req.date(), req.sex(), req.referenceId(), req.link(),
            req.remarks(), req.latitude(), req.longitude()),
        res -> key(res.citation(), res.status(), res.institutionCode(), res.catalogNumber(), res.occurrenceId(),
            res.locality(), res.country(), res.collector(), res.date(), res.sex(), res.referenceId(), res.link(),
            res.remarks(), res.latitude(), res.longitude()),
        // No bulk "delete by usage" mapper method for this name-scoped type (unlike the 5
        // taxon-scoped types' TaxonChildMapper methods) -- fetch + delete each existing row by id.
        (projectId, usageId) -> typeMaterials.findByUsage(projectId, usageId)
            .forEach(r -> typeMaterials.delete(projectId, r.id())));

    ChildTypeOps<NameRelationRequest, NameRelationResponse> nameRelationOps = new ChildTypeOps<>(
        NAME_RELATION_ENTITY, false, nameRelations::findByUsage, nameRelations::insert,
        (row, refIdMap, usageIdMap, srcRowId, issues) -> {
          Integer relatedTarget = usageIdMap.get(String.valueOf(row.relatedUsageId()));
          if (relatedTarget == null) {
            issues.add(new MergeIssue(NAME_RELATION_ENTITY, srcRowId,
                "relatedNameID " + row.relatedUsageId() + " not resolved"));
            return null;
          }
          return new NameRelationRequest(relatedTarget, row.type(),
              resolveRefId(refIdMap, NAME_RELATION_ENTITY, srcRowId, row.referenceId(), issues), row.page(),
              row.remarks(), null);
        },
        req -> key(req.relatedUsageId(), req.type(), req.referenceId(), req.page(), req.remarks()),
        res -> key(res.relatedUsageId(), res.type(), res.referenceId(), res.page(), res.remarks()),
        (projectId, usageId) -> nameRelations.findByUsage(projectId, usageId)
            .forEach(r -> nameRelations.delete(projectId, r.id())));

    return List.of(distributionOps, estimateOps, mediaOps, propertyOps, vernacularOps, typeMaterialOps,
        nameRelationOps);
  }

  private List<ChildWorkItem> buildChildWorkItems(Map<String, Integer> usageIdMap, Set<String> matchedSrcIds) {
    List<ChildWorkItem> items = new ArrayList<>();
    for (ChildTypeOps<?, ?> ops : childTypeOps()) {
      for (Map.Entry<String, Integer> e : usageIdMap.entrySet()) {
        items.add(new ChildWorkItem(ops, e.getKey(), e.getValue(), matchedSrcIds.contains(e.getKey())));
      }
    }
    return items;
  }

  private void applyChildEntities(int sourceId, int targetId, int userId, Mode mode,
      Map<String, Integer> usageIdMap, Set<String> matchedSrcIds, Map<String, Integer> refIdMap,
      Set<Integer> acceptedTargetIds, List<MergeIssue> issues) {
    for (ChildWorkItem w : buildChildWorkItems(usageIdMap, matchedSrcIds)) {
      applyOneChildTypeForUsage(w.ops(), sourceId, targetId, userId, mode, w.srcUsageIdStr(), w.targetUsageId(),
          w.matched(), refIdMap, usageIdMap, acceptedTargetIds, issues);
    }
  }

  // One (child type, source usage) unit of work: NEW usage -> add every source row of this type
  // (remapped); MATCHED usage under NEW_ONLY -> skip entirely; MATCHED under OVERWRITE -> replace
  // the target's whole set of this type (delete-then-insert); MATCHED under FILL_GAPS -> add only
  // source rows not already present on the target, deduped by content. The accepted-only guard
  // applies identically to NEW and MATCHED usages, mirroring ImportRunService's acceptedUsageIds.
  private <REQ, RES> void applyOneChildTypeForUsage(ChildTypeOps<REQ, RES> ops, int sourceId, int targetId,
      int userId, Mode mode, String srcUsageIdStr, int targetUsageId, boolean isMatched,
      Map<String, Integer> refIdMap, Map<String, Integer> usageIdMap, Set<Integer> acceptedTargetIds,
      List<MergeIssue> issues) {
    if (isMatched && mode == Mode.NEW_ONLY) {
      return;
    }
    if (ops.acceptedOnly() && !acceptedTargetIds.contains(targetUsageId)) {
      return;
    }
    int srcUsageId = Integer.parseInt(srcUsageIdStr);
    List<RES> sourceRows = ops.findByUsage().apply(sourceId, srcUsageId);
    if (sourceRows.isEmpty()) {
      return;
    }

    if (!isMatched || mode == Mode.OVERWRITE) {
      if (isMatched) {
        ops.deleteAllForUsage().deleteAllForUsage(targetId, targetUsageId);
      }
      for (RES row : sourceRows) {
        REQ req = ops.remap().remap(row, refIdMap, usageIdMap, srcUsageIdStr, issues);
        if (req == null) {
          continue;
        }
        int id = idSeq.allocate(targetId, ops.idSeqEntity());
        ops.insert().insert(targetId, id, targetUsageId, req, userId);
      }
    } else {
      // MATCHED + FILL_GAPS: add only source rows not already present on the target, by content.
      List<RES> targetRows = ops.findByUsage().apply(targetId, targetUsageId);
      Set<Object> existingKeys = new HashSet<>();
      for (RES tr : targetRows) {
        existingKeys.add(ops.contentKeyFromResponse().apply(tr));
      }
      for (RES row : sourceRows) {
        REQ req = ops.remap().remap(row, refIdMap, usageIdMap, srcUsageIdStr, issues);
        if (req == null) {
          continue;
        }
        if (existingKeys.contains(ops.contentKey().apply(req))) {
          continue;
        }
        int id = idSeq.allocate(targetId, ops.idSeqEntity());
        ops.insert().insert(targetId, id, targetUsageId, req, userId);
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Shared helpers
  // ---------------------------------------------------------------------------------------------

  // Field-level merge for a MATCHED record's scalar reconciliation (Step 1): OVERWRITE takes the
  // source's value whenever the source actually HAS one (never destructively blanks a target field
  // just because a sparser source row happens to lack it); FILL_GAPS takes the source's value only
  // when the target's own is blank, never overwriting a non-blank target value. Both branches are
  // therefore non-destructive by construction -- neither mode can ever turn a populated target field
  // into a blank one.
  private static String mergeString(String tgt, String src, Mode mode) {
    boolean srcBlank = src == null || src.isBlank();
    if (mode == Mode.OVERWRITE) {
      return srcBlank ? tgt : src;
    }
    boolean tgtBlank = tgt == null || tgt.isBlank();
    return tgtBlank ? src : tgt;
  }

  private static <T> T mergeScalar(T tgt, T src, Mode mode) {
    if (mode == Mode.OVERWRITE) {
      return src == null ? tgt : src;
    }
    return tgt == null ? src : tgt;
  }

  private static <T> List<T> mergeList(List<T> tgt, List<T> src, Mode mode) {
    boolean srcBlank = src == null || src.isEmpty();
    if (mode == Mode.OVERWRITE) {
      return srcBlank ? tgt : src;
    }
    boolean tgtBlank = tgt == null || tgt.isEmpty();
    return tgtBlank ? src : tgt;
  }

  // Defensive parse of a MATCHED candidate's targetId: null on a null/blank/non-numeric value
  // instead of letting Integer.parseInt throw NumberFormatException and fail the whole apply over
  // one bad row in a stored plan (hand-edited via applyOverrides, or from an older/buggy
  // compute-plan run).
  private static Integer parseTargetId(Candidate c) {
    String targetId = c.targetId();
    if (targetId == null || targetId.isBlank()) {
      return null;
    }
    try {
      return Integer.parseInt(targetId.trim());
    } catch (NumberFormatException e) {
      return null;
    }
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

  // A plan with NO MATCHED/POSSIBLE_* candidate at all -- every reference and every name-usage is
  // NEW -- e.g. merging into an empty target. An empty plan (source has nothing at all) trivially
  // counts too (allMatch on an empty stream is true): harmless, there's nothing to apply either way.
  private static boolean isFullImport(MergePlan plan) {
    return plan.references().stream().allMatch(c -> c.category() == Category.NEW)
        && plan.names().stream().allMatch(c -> c.category() == Category.NEW);
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
