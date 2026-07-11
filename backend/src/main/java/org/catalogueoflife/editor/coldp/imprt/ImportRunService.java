package org.catalogueoflife.editor.coldp.imprt;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import life.catalogue.api.model.VerbatimRecord;
import life.catalogue.api.vocab.Environment;
import life.catalogue.api.vocab.Gender;
import life.catalogue.api.vocab.NomStatus;
import life.catalogue.coldp.ColdpTerm;
import life.catalogue.csv.ColdpReader;
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
import org.catalogueoflife.editor.coldp.imprt.dto.ImportRunResponse;
import org.catalogueoflife.editor.coldp.io.ColdpMetadata;
import org.catalogueoflife.editor.coldp.io.ColdpMetadata.ColdpMetadataDto;
import org.catalogueoflife.editor.coldp.io.ColdpZip;
import org.catalogueoflife.editor.name.Author;
import org.catalogueoflife.editor.name.AuthorMapper;
import org.catalogueoflife.editor.name.IdSeqMapper;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.name.NameUsageMapper;
import org.catalogueoflife.editor.name.Reference;
import org.catalogueoflife.editor.name.ReferenceMapper;
import org.catalogueoflife.editor.name.Status;
import org.catalogueoflife.editor.name.SynonymAcceptedMapper;
import org.catalogueoflife.editor.name.TaxonInfoMapper;
import org.catalogueoflife.editor.parse.NameParserService;
import org.catalogueoflife.editor.project.Project;
import org.catalogueoflife.editor.project.ProjectService;
import org.catalogueoflife.editor.project.dto.CreateProjectRequest;
import org.catalogueoflife.editor.project.dto.UpdateProjectMetadataRequest;
import org.catalogueoflife.editor.validation.ValidationService;
import org.gbif.nameparser.api.NamePart;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

// The ColDP import job: start/run are the async trigger (ImportRunController -> start, which
// authorizes the caller and inserts the RUNNING import_run row on the request thread, then fires
// run() -- @Async off ImportAsyncConfig's dedicated single-thread pool -- so the request returns
// immediately with a 202 while the (possibly long) archive walk proceeds in the background).
// Mirrors ExportRunService's start/run shape, but authorization is "any authenticated user" (no
// project-role check -- the project doesn't exist yet; the caller becomes its OWNER, exactly like
// POST /projects). run() opens the archive, creates the project, then loads references, authors,
// name-usages and finally the 7 taxon/name child-entity files (loadReferences/loadAuthors/
// loadNameUsages/loadChildEntities, phases 1-5), consuming/extending the ImportContext
// (refIds/authorIds/usageIds source-id maps) built here. Once loadTransactional's transaction
// commits, run() explicitly revalidates the whole new project (ValidationService.revalidateProject)
// -- the raw-mapper inserts throughout this whole load never go through
// NameUsageService/ReferenceService/AbstractChildEntityService, so none of them ever publish a
// ValidationEvent (see ValidationTrigger); nothing would otherwise validate an imported project at
// all.
@Service
public class ImportRunService {

  private static final Logger log = LoggerFactory.getLogger(ImportRunService.class);

  // id_seq entity strings -- must match ReferenceService.ENTITY / the "author" string used
  // throughout (there is no AuthorService/AuthorController; the app has no author-creation path of
  // its own -- see AuthorColdpWriter's javadoc -- so this import job is the only writer of author
  // rows besides direct-mapper test fixtures like ReferenceExportIT's AUTHOR_ENTITY).
  private static final String REFERENCE_ENTITY = "reference";
  private static final String AUTHOR_ENTITY = "author";
  // Matches NameUsageService.ENTITY / the idSeq entity string NameUsageColdpWriter's rows are keyed
  // by on export -- see NameUsageService's private ENTITY constant of the same value.
  private static final String NAME_USAGE_ENTITY = "name_usage";
  // A pro-parte derived row's id, exactly as NameUsageColdpWriter.synonymRows mints it:
  // "<primaryUsageId>-<acceptedId>", both plain non-negative integers.
  private static final Pattern PRO_PARTE_ID = Pattern.compile("^(\\d+)-(\\d+)$");

  // The 7 child-entity idSeq entity strings -- each MUST match its own AbstractChildEntityService
  // subclass's entity()/ENTITY (DistributionService/EstimateService/MediaService/PropertyService/
  // VernacularService's `entity()` override, TypeMaterialService/NameRelationService's own ENTITY
  // constant) since id_seq's per-(project, entity) counter is shared with every other writer of that
  // entity -- reusing a different string here would silently allocate ids from the wrong counter.
  private static final String TYPE_MATERIAL_ENTITY = "type_material";
  private static final String DISTRIBUTION_ENTITY = "distribution";
  private static final String VERNACULAR_ENTITY = "vernacular";
  private static final String MEDIA_ENTITY = "media";
  private static final String ESTIMATE_ENTITY = "estimate";
  private static final String NAME_RELATION_ENTITY = "name_relation";
  private static final String PROPERTY_ENTITY = "property";

  private final ImportRunMapper runs;
  private final ProjectService projectService;
  private final ObjectMapper json;
  private final Path importDir;
  private final long maxBytes;
  private final IdSeqMapper idSeq;
  private final ReferenceMapper references;
  private final AuthorMapper authors;
  private final NameUsageMapper usages;
  private final SynonymAcceptedMapper synonymAccepted;
  private final TaxonInfoMapper taxonInfo;
  private final NameParserService parser;
  private final TypeMaterialMapper typeMaterials;
  private final DistributionMapper distributions;
  private final VernacularMapper vernaculars;
  private final MediaMapper media;
  private final EstimateMapper estimates;
  private final NameRelationMapper nameRelations;
  private final PropertyMapper properties;
  private final ValidationService validationService;

  // Self-reference through the Spring proxy so run()'s @Async and loadTransactional's @Transactional
  // actually go through their proxied annotations -- see ExportRunService's identical `self` field
  // (and ColMatchJobService's run -> self.matchOneScope split) for the same reason: a plain
  // `this.foo(...)` call bypasses the proxy entirely. @Lazy avoids a circular bean-creation error
  // (this bean depending on itself while still being constructed).
  @Autowired
  @Lazy
  private ImportRunService self;

  public ImportRunService(ImportRunMapper runs, ProjectService projectService, ObjectMapper json,
      IdSeqMapper idSeq, ReferenceMapper references, AuthorMapper authors, NameUsageMapper usages,
      SynonymAcceptedMapper synonymAccepted, TaxonInfoMapper taxonInfo, NameParserService parser,
      TypeMaterialMapper typeMaterials, DistributionMapper distributions, VernacularMapper vernaculars,
      MediaMapper media, EstimateMapper estimates, NameRelationMapper nameRelations,
      PropertyMapper properties, ValidationService validationService,
      @Value("${coldp.import.dir:${java.io.tmpdir}/coldp-imports}") String importDir,
      @Value("${coldp.import.max-bytes:104857600}") long maxBytes) {
    this.runs = runs;
    this.projectService = projectService;
    this.json = json;
    this.idSeq = idSeq;
    this.references = references;
    this.authors = authors;
    this.usages = usages;
    this.synonymAccepted = synonymAccepted;
    this.taxonInfo = taxonInfo;
    this.parser = parser;
    this.typeMaterials = typeMaterials;
    this.distributions = distributions;
    this.vernaculars = vernaculars;
    this.media = media;
    this.estimates = estimates;
    this.nameRelations = nameRelations;
    this.properties = properties;
    this.validationService = validationService;
    this.importDir = Path.of(importDir);
    this.maxBytes = maxBytes;
    try {
      Files.createDirectories(this.importDir);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to create import dir " + importDir, e);
    }
  }

  // Validates the upload, inserts the RUNNING import_run row synchronously (so the 202 response
  // always has a real id to poll), then extracts the archive ON THE REQUEST THREAD -- unlike the
  // rest of the job, extraction is deliberately NOT async, so a malformed zip or one that trips the
  // decompressed-byte/entry cap (ColdpZip.extractToTemp's zip-bomb guard) fails the request fast
  // with a clear 400 instead of silently failing a background job the caller has to go poll for.
  // Only once extraction succeeds does the (possibly long) archive walk move to the background via
  // self.run.
  public ImportRunResponse start(int userId, MultipartFile file, boolean preserveIds, String idScope) {
    if (file == null || file.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "file is required");
    }
    if (preserveIds && (idScope == null || idScope.isBlank())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "idScope is required when preserveIds is set");
    }
    if (file.getSize() > maxBytes) {
      throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE,
          "archive exceeds " + maxBytes + " bytes");
    }

    ImportRun run = new ImportRun();
    run.setUserId(userId);
    run.setSourceName(file.getOriginalFilename());
    run.setPreserveIds(preserveIds);
    run.setIdScope(idScope);
    runs.insertRunning(run);
    long runId = run.getId();

    Path dir = importDir.resolve(String.valueOf(runId));
    try (InputStream in = file.getInputStream()) {
      ColdpZip.extractToTemp(in, dir, maxBytes);
    } catch (IOException e) {
      runs.fail(runId, e.getMessage());
      deleteQuietly(dir);
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid archive: " + e.getMessage());
    }

    try {
      self.run(runId, dir, userId, preserveIds, idScope);
    } catch (TaskRejectedException e) {
      // The single-thread, bounded-queue (queueCapacity(50)) executor is full: self.run(...) throws
      // synchronously at this call site, before run()'s own try/catch (which maps failures to
      // runs.fail) ever gets a chance to run. Without this catch the just-inserted RUNNING row would
      // be stuck forever and the caller would see an unhandled 500 instead of a clean, retryable 503.
      runs.fail(runId, "import service busy — try again later");
      deleteQuietly(dir);
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
          "import service busy, try again later");
    }

    return ImportRunResponse.of(runs.findById(runId), json);
  }

  // Poll target for the 202's id: scoped to the requesting user (import has no project to check
  // membership against yet, and even once it does, the run itself is a personal upload, not a
  // shared project resource) -- 404 both for a missing runId and for one that belongs to a
  // different user, never leaking another user's import.
  public ImportRunResponse get(int userId, long runId) {
    ImportRun run = runs.findById(runId);
    if (run == null || !run.getUserId().equals(userId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "import run not found");
    }
    return ImportRunResponse.of(run, json);
  }

  // Latest-run view (load-on-mount for an eventual import page): returns null (-> 204 at the
  // controller) rather than 404 when this user has never started an import.
  public ImportRunResponse latest(int userId) {
    ImportRun run = runs.findLatestByUser(userId);
    return run == null ? null : ImportRunResponse.of(run, json);
  }

  // The async body: runs on ImportAsyncConfig's single-thread pool, off the request thread that
  // called start(). loadTransactional does the actual archive-open + project-creation + entity-load
  // work inside its own @Transactional (see its javadoc for why runs.finish/runs.fail must stay OUT
  // here rather than inside it); any exception it throws marks the whole row FAILED with the
  // exception's message rather than leaving it stuck RUNNING forever -- there is no caller left to
  // propagate the exception to once we're here, same contract as ExportRunService.run /
  // ColMatchJobService.run. The extracted temp dir is always removed afterwards, success or failure:
  // once this method returns, nothing further ever reads it again.
  //
  // validationService.revalidateProject runs LAST, after loadTransactional's transaction has already
  // committed (ctx is only ever assigned once that call returns successfully) and after runs.finish
  // has recorded DONE -- never nested inside loadTransactional itself: revalidateProject manages its
  // OWN per-usage transactions via ValidationService's own @Lazy self (see its javadoc), so calling
  // it from in here would wrap every one of those per-usage transactions inside loadTransactional's
  // single outer @Transactional instead, holding every usage's IssueMapper.lockUsage advisory lock
  // for the whole recompute. If loadTransactional throws, ctx is never assigned, this line never
  // runs, and the catch below marks the run FAILED -- exactly the "only revalidate on success"
  // contract; there is nothing to revalidate for a project whose load never committed.
  //
  // revalidateProject gets its OWN inner try/catch, deliberately NOT the outer one: by the time it
  // runs, runs.finish has already committed DONE for a project whose data is already live in the DB
  // (loadTransactional's transaction committed inside self.loadTransactional, before runs.finish even
  // ran). revalidateProject walks untrusted, freshly-imported data through the full validation-rule
  // set; if any rule throws, that must never be allowed to fall into the outer catch below and call
  // runs.fail -- ImportRunMapper.fail also guards on status='RUNNING' now, so it would be a no-op
  // against the already-DONE row in practice, but relying on that guard here would be misleading: the
  // real fix is that a post-commit, best-effort step's failure was never a reason to report the whole
  // import as FAILED in the first place (that hid the "open imported project" link and prompted a
  // pointless duplicate re-import). So its exception is logged and swallowed instead -- the project
  // stays DONE, simply without a fresh revalidation pass; a later edit or explicit re-validation will
  // catch up.
  @Async(ImportAsyncConfig.EXECUTOR_BEAN)
  public void run(long runId, Path dir, int userId, boolean preserveIds, String idScope) {
    try {
      ImportContext ctx = self.loadTransactional(runId, dir, userId, preserveIds, idScope);
      runs.finish(runId, ctx.nameUsageCount, ctx.referenceCount, ctx.authorCount,
          ctx.issues.isEmpty() ? null : json.writeValueAsString(ctx.issues));
      try {
        validationService.revalidateProject(ctx.projectId);
      } catch (Exception ve) {
        log.warn("post-import revalidation failed for run {} (project {}); import stays DONE: {}",
            runId, ctx.projectId, ve.getMessage(), ve);
      }
    } catch (Exception e) {
      log.warn("import run {} failed for user {}: {}", runId, userId, e.getMessage(), e);
      runs.fail(runId, e.getMessage());
    } finally {
      deleteQuietly(dir);
    }
  }

  // Phases 1-3 of the import: open the archive, require it actually has usage data, read
  // metadata.yaml, peek the nomenclatural code off the first data row, create + configure the
  // project, then load references and authors -- in that order, since they have no forward
  // dependencies on anything else in the archive (Task 4's name/usage load, and Task 5's child-entity
  // load, both come after and consume the ImportContext this method returns). @Transactional so a failure
  // partway through (e.g. an invalid license in metadata.yaml, or a malformed reference row) rolls
  // back the whole thing -- the just-inserted project row along with it -- rather than leaving a
  // half-configured project behind; matches the spec's "rollback + FAILED, delete nothing [from the
  // DB]" contract for a failed import. Deliberately does NOT call runs.finish/runs.fail itself: those
  // live in the non-transactional run() above, in their own separate (uncommitted-rollback-proof)
  // statements, so the FAILED status write always survives even when this transaction rolls back --
  // exactly the split ColMatchJobService.run/runSync/matchOneScope uses (there, matchOneScope is the
  // @Transactional leaf and runSync/run stay outside it).
  @Transactional(rollbackFor = Exception.class)
  public ImportContext loadTransactional(long runId, Path dir, int userId, boolean preserveIds,
      String idScope) throws IOException {
    ColdpReader reader = ColdpReader.from(dir);
    if (!(reader.hasSchema(ColdpTerm.NameUsage)
        || (reader.hasSchema(ColdpTerm.Name) && reader.hasSchema(ColdpTerm.Taxon)))) {
      throw new IllegalStateException("archive has neither a NameUsage file nor Name+Taxon files");
    }

    ColdpMetadataDto md = ColdpMetadata.read(dir);
    String title = md.title() != null && !md.title().isBlank()
        ? md.title()
        : filenameStem(runs.findById(runId).getSourceName());

    NomCode nomCode = peekNomCode(reader);
    String nomCodeName = nomCode == null ? null : nomCode.name();

    Project p = projectService.create(userId, new CreateProjectRequest(title, nomCodeName));
    projectService.updateMetadata(userId, p.getId(), new UpdateProjectMetadataRequest(
        title, md.alias(), md.description(), nomCodeName, md.license(),
        md.geographicScope(), md.taxonomicScope(), null, null));
    runs.setProject(runId, p.getId());

    ImportContext ctx = new ImportContext(p.getId());
    loadReferences(reader, ctx, userId, preserveIds, idScope);
    loadAuthors(reader, ctx, userId, preserveIds, idScope);
    loadNameUsages(reader, ctx, userId, preserveIds, idScope, nomCode);
    loadChildEntities(reader, ctx, userId);
    return ctx;
  }

  // References first, before names: nothing in Reference.tsv ever points at a name/usage/author (a
  // reference's own `author`/`editor` columns are free-text citation strings, not authorID
  // references), so this has no forward dependency on anything else in the archive. Inverts
  // ReferenceColdpWriter.row -- see that method for the authoritative column<->field pairing this
  // mirrors in reverse.
  private void loadReferences(ColdpReader reader, ImportContext ctx, int userId, boolean preserveIds,
      String idScope) {
    reader.stream(ColdpTerm.Reference).forEach(rec -> {
      Reference r = new Reference();
      r.setAlternativeId(alternativeIds(rec, preserveIds, idScope));
      r.setCitation(rec.get(ColdpTerm.citation));
      r.setType(rec.get(ColdpTerm.type));
      r.setAuthor(rec.get(ColdpTerm.author));
      r.setEditor(rec.get(ColdpTerm.editor));
      r.setTitle(rec.get(ColdpTerm.title));
      r.setContainerTitle(rec.get(ColdpTerm.containerTitle));
      r.setIssued(rec.get(ColdpTerm.issued));
      r.setVolume(rec.get(ColdpTerm.volume));
      r.setIssue(rec.get(ColdpTerm.issue));
      r.setPage(rec.get(ColdpTerm.page));
      r.setPublisher(rec.get(ColdpTerm.publisher));
      r.setDoi(rec.get(ColdpTerm.doi));
      r.setIsbn(rec.get(ColdpTerm.isbn));
      r.setIssn(rec.get(ColdpTerm.issn));
      r.setLink(rec.get(ColdpTerm.link));
      r.setAccessed(rec.get(ColdpTerm.accessed));
      r.setRemarks(rec.get(ColdpTerm.remarks));
      r.setProjectId(ctx.projectId);
      r.setId(idSeq.allocate(ctx.projectId, REFERENCE_ENTITY));
      r.setModifiedBy(userId);
      references.insert(r);
      String srcId = rec.get(ColdpTerm.ID);
      // A source archive with two rows sharing the same ColDP ID would otherwise silently last-win
      // this map -- the earlier row's own new id becomes unreachable by source id, so anything that
      // cross-references it (e.g. a NameUsage's referenceID) mis-resolves to the later row instead.
      // The row is still inserted either way; this only records the diagnostic.
      if (srcId != null && ctx.refIds.containsKey(srcId)) {
        ctx.issue(REFERENCE_ENTITY, srcId, "duplicate ID — later row shadows earlier in cross-references");
      }
      ctx.refIds.put(srcId, r.getId());
      ctx.referenceCount++;
    });
  }

  // Author.tsv is optional -- most archives don't have one at all (the app itself never writes one
  // for a project with zero authors; see AuthorColdpWriter's javadoc), so this only runs when the
  // schema is actually present, mirroring hasSchema's role in peekNomCode's Name+Taxon fallback
  // above. Inverts AuthorColdpWriter.row the same way loadReferences inverts ReferenceColdpWriter.row.
  private void loadAuthors(ColdpReader reader, ImportContext ctx, int userId, boolean preserveIds,
      String idScope) {
    if (!reader.hasSchema(ColdpTerm.Author)) {
      return;
    }
    reader.stream(ColdpTerm.Author).forEach(rec -> {
      Author a = new Author();
      a.setAlternativeId(alternativeIds(rec, preserveIds, idScope));
      a.setGiven(rec.get(ColdpTerm.given));
      a.setFamily(rec.get(ColdpTerm.family));
      a.setSuffix(rec.get(ColdpTerm.suffix));
      a.setAbbreviationBotany(rec.get(ColdpTerm.abbreviationBotany));
      a.setAffiliation(rec.get(ColdpTerm.affiliation));
      a.setCountry(rec.get(ColdpTerm.country));
      a.setBirth(rec.get(ColdpTerm.birth));
      a.setBirthPlace(rec.get(ColdpTerm.birthPlace));
      a.setDeath(rec.get(ColdpTerm.death));
      a.setLink(rec.get(ColdpTerm.link));
      a.setRemarks(rec.get(ColdpTerm.remarks));
      a.setProjectId(ctx.projectId);
      a.setId(idSeq.allocate(ctx.projectId, AUTHOR_ENTITY));
      a.setModifiedBy(userId);
      authors.insert(a);
      String srcId = rec.get(ColdpTerm.ID);
      // See loadReferences' identical duplicate-ID diagnostic above.
      if (srcId != null && ctx.authorIds.containsKey(srcId)) {
        ctx.issue(AUTHOR_ENTITY, srcId, "duplicate ID — later row shadows earlier in cross-references");
      }
      ctx.authorIds.put(srcId, a.getId());
      ctx.authorCount++;
    });
  }

  // Task 4: loads name-usages, either from the combined NameUsage.tsv (readCombinedRows) or, when
  // the archive instead has separate Name+Taxon(+Synonym) files, synthesized into the same combined
  // shape (readSplitFormRows) so every row from here on is processed identically regardless of
  // archive shape (Step 7 in the design brief).
  //
  // Two-phase insert: name_usage.parent_id/basionym_id are non-deferrable self-referencing compound
  // FKs (V3__name_core.sql/V8), so a row referencing a not-yet-inserted parent/basionym would fail
  // at insert, and import can't assume the archive lists parents before their children. Pass 1
  // (insertPrimaryUsage) therefore inserts every row with parent_id/basionym_id left NULL --
  // published_in_reference_id/reference_id[] CAN be set already since References were loaded
  // earlier in this same transaction (Task 3) -- while allocating the row's id and recording the
  // source-id -> new-id mapping in ctx.usageIds. Only once every row has been inserted and
  // ctx.usageIds is complete does Pass 2 resolve parent_id/basionym_id via the new
  // NameUsageMapper.updateHierarchy and create synonym_accepted links.
  //
  // Status inverse: a NON-accepted row's parentID is a synonym_accepted LINK, not a parent_id --
  // the exact inverse of NameUsageColdpWriter.synonymRows, including the UNASSESSED case (exported
  // as parentID=<accepted> + status "provisionally accepted", see coldpStatus's javadoc): on import
  // that parentID becomes a synonym link, never a classification parent_id.
  //
  // Pro-parte re-merge: NameUsageColdpWriter.synonymRows emits a pro-parte synonym (one usage
  // linked to N accepted names) as a primary row (ID=<usageId>, parentID=lowest acceptedId) plus
  // one derived "<usageId>-<acceptedId>" row per additional target; this reverses that by NOT
  // creating a second usage for a "<n>-<m>" row when a primary row ID=<n> exists with the same
  // scientificName -- instead adding an extra synonym_accepted link onto the already-inserted
  // primary. A "<n>-<m>" row with no matching primary (or a differing name) falls back to an
  // ordinary row (best-effort, per the design brief).
  private void loadNameUsages(ColdpReader reader, ImportContext ctx, int userId, boolean preserveIds,
      String idScope, NomCode nomCode) {
    List<Map<ColdpTerm, String>> allRows;
    // source Name ID -> the Taxon/Synonym row ID that used it (first-wins if a Name is shared),
    // built by readSplitFormRows below; empty (and therefore a no-op) for the combined form, which
    // never populates it. See its use resolving split-form basionymID below.
    Map<String, String> nameIdToUsageSourceId;
    if (reader.hasSchema(ColdpTerm.NameUsage)) {
      allRows = readCombinedRows(reader);
      nameIdToUsageSourceId = Map.of();
    } else {
      SplitFormRows split = readSplitFormRows(reader, ctx);
      allRows = split.rows();
      nameIdToUsageSourceId = split.nameIdToUsageSourceId();
    }

    Map<String, Map<ColdpTerm, String>> byId = new LinkedHashMap<>();
    for (Map<ColdpTerm, String> row : allRows) {
      String id = row.get(ColdpTerm.ID);
      if (id != null) {
        byId.putIfAbsent(id, row);
      }
    }

    List<Map<ColdpTerm, String>> primaryRows = new ArrayList<>();
    List<Map<ColdpTerm, String>> proParteExtra = new ArrayList<>();
    for (Map<ColdpTerm, String> row : allRows) {
      String id = row.get(ColdpTerm.ID);
      Matcher m = id == null ? null : PRO_PARTE_ID.matcher(id);
      Map<ColdpTerm, String> primary = (m != null && m.matches()) ? byId.get(m.group(1)) : null;
      boolean isProParteExtra = primary != null
          && Objects.equals(primary.get(ColdpTerm.scientificName), row.get(ColdpTerm.scientificName));
      (isProParteExtra ? proParteExtra : primaryRows).add(row);
    }

    // Pass 1: insert every primary row with parent_id/basionym_id NULL, remembering (usage, row)
    // pairs for Pass 2 below. insertPrimaryUsage returns null (having already recorded its own
    // ctx.issue) for a row it refuses to insert at all -- e.g. a blank/missing scientificName --
    // which must NOT enter Pass 2's pending list or ctx.usageIds, so neither Pass 2 nor the
    // pro-parte re-merge below ever resolve a reference to a usage that doesn't exist.
    record Pending(NameUsage usage, Map<ColdpTerm, String> row) {}
    List<Pending> pending = new ArrayList<>(primaryRows.size());
    for (Map<ColdpTerm, String> row : primaryRows) {
      NameUsage u = insertPrimaryUsage(row, ctx, userId, preserveIds, idScope, nomCode);
      if (u != null) {
        pending.add(new Pending(u, row));
      }
    }

    // Name-ID -> new-usage-id, derived from nameIdToUsageSourceId now that Pass 1 has finished and
    // ctx.usageIds (source usage id -> new id) is complete. Split-form Name.basionymID is a Name-id,
    // not a Taxon/Synonym id, so a plain ctx.usageIds lookup for it always misses even for a
    // perfectly valid archive -- this is the fallback Pass 2 tries next, below.
    Map<String, Integer> nameIdToUsage = new HashMap<>();
    for (Map.Entry<String, String> e : nameIdToUsageSourceId.entrySet()) {
      Integer usageId = ctx.usageIds.get(e.getValue());
      if (usageId != null) {
        nameIdToUsage.put(e.getKey(), usageId);
      }
    }

    // Pass 2: every usage now exists, so parent_id/basionym_id (accepted) or a synonym_accepted
    // link (non-accepted) can finally be resolved; a dangling reference is surfaced as an
    // ImportIssue rather than failing the whole import. basionymID additionally falls back to
    // nameIdToUsage (split form only -- see above); parentID never does, since a split-form
    // parentID/taxonID is already a Taxon/Synonym id, resolved by ctx.usageIds directly exactly
    // like the combined form.
    for (Pending p : pending) {
      Map<ColdpTerm, String> row = p.row();
      String rowId = row.get(ColdpTerm.ID);
      Integer basionymNewId =
          resolveUsageRef(ctx, nameIdToUsage, row.get(ColdpTerm.basionymID), "basionym", rowId);
      if (p.usage().getStatus() == Status.ACCEPTED) {
        Integer parentNewId = resolveUsageRef(ctx, null, row.get(ColdpTerm.parentID), "parent", rowId);
        usages.updateHierarchy(ctx.projectId, p.usage().getId(), parentNewId, basionymNewId, userId);
      } else {
        Integer acceptedNewId = resolveUsageRef(ctx, null, row.get(ColdpTerm.parentID), "parent", rowId);
        usages.updateHierarchy(ctx.projectId, p.usage().getId(), null, basionymNewId, userId);
        if (acceptedNewId != null) {
          synonymAccepted.link(ctx.projectId, p.usage().getId(), acceptedNewId, 0);
        }
      }
    }

    // Pro-parte re-merge: each "<primaryId>-<acceptedId>" row becomes one extra synonym_accepted
    // link on the already-inserted primary synonym, ordinal 1, 2, ... continuing after the
    // primary's own ordinal-0 link created in Pass 2 above.
    Map<String, Integer> nextOrdinal = new HashMap<>();
    for (Map<ColdpTerm, String> row : proParteExtra) {
      String id = row.get(ColdpTerm.ID);
      Matcher m = PRO_PARTE_ID.matcher(id);
      m.matches(); // already verified true when this row was classified into proParteExtra above
      String primarySrcId = m.group(1);
      String acceptedSrcId = m.group(2);
      Integer synNewId = ctx.usageIds.get(primarySrcId);
      Integer accNewId = ctx.usageIds.get(acceptedSrcId);
      if (synNewId == null || accNewId == null) {
        ctx.issue("name_usage", id, "pro-parte accepted " + acceptedSrcId + " not found");
        continue;
      }
      int ordinal = nextOrdinal.merge(primarySrcId, 1, Integer::sum);
      synonymAccepted.link(ctx.projectId, synNewId, accNewId, ordinal);
    }
  }

  // Task 5: loads the 7 taxon/name child-entity files, called AFTER loadNameUsages so ctx.usageIds
  // is complete for every usage the archive could possibly reference. TypeMaterial + NameRelation
  // key off the NAME (ColdpTerm.nameID = the usage id, since our model collapses name+taxon into one
  // row -- see NameUsage's class doc); the other five (Distribution/VernacularName/Media/
  // SpeciesEstimate/TaxonProperty) key off the TAXON (ColdpTerm.taxonID = the usage id) -- exactly
  // which FK column a file uses is fixed by ColdpTerm.RESOURCES, not a per-writer/-reader choice; see
  // ChildColdpWriter's javadoc, which every one of the 7 loaders below inverts field-for-field, row
  // method by row method. Every insert here bypasses AbstractChildEntityService/the individual
  // per-entity Services entirely (no audit trail, no per-row ValidationEvent -- the whole project is
  // explicitly revalidated once, after the load transaction commits; see run()) and instead allocates
  // a fresh id and calls the mapper's raw insert() directly, exactly like loadReferences/loadAuthors/
  // insertPrimaryUsage above. A row whose parent usage (taxonID/nameID) can't be resolved is skipped
  // with a ctx.issue rather than failing the whole import -- never fatal, matching every other
  // dangling-reference case in this file. The 5 taxon-scoped loaders additionally require the
  // resolved taxonID to be ACCEPTED (ctx.acceptedUsageIds, populated in Task 4's Pass 1): this
  // mirrors AbstractChildEntityService.requireAcceptedUsage, which every ordinary (non-import) write
  // path for these 5 entities enforces, and NameUsageService.writeTaxonInfo's taxonChildren.dropAll
  // on any status change away from ACCEPTED -- a synonym's taxonID must never be allowed to end up
  // owning one of these 5 rows, import included. TypeMaterial + NameRelation key off nameID and apply
  // to ANY usage status, so they deliberately do NOT consult acceptedUsageIds.
  private void loadChildEntities(ColdpReader reader, ImportContext ctx, int userId) {
    loadTypeMaterial(reader, ctx, userId);
    loadDistribution(reader, ctx, userId);
    loadVernacular(reader, ctx, userId);
    loadMedia(reader, ctx, userId);
    loadEstimate(reader, ctx, userId);
    loadNameRelation(reader, ctx, userId);
    loadProperty(reader, ctx, userId);
  }

  // Inverts ChildColdpWriter.typeMaterialRow. nameID, not taxonID (see loadChildEntities' javadoc).
  // occurrenceId is left null: TypeMaterial carries no occurrenceID ColdpTerm column at all (see
  // ChildColdpWriter.typeMaterialRow's own comment), so there is nothing to invert it from.
  private void loadTypeMaterial(ColdpReader reader, ImportContext ctx, int userId) {
    if (!reader.hasSchema(ColdpTerm.TypeMaterial)) {
      return;
    }
    reader.stream(ColdpTerm.TypeMaterial).forEach(rec -> {
      String rowId = rec.get(ColdpTerm.ID);
      String nameSrc = rec.get(ColdpTerm.nameID);
      Integer usageId = ctx.usageIds.get(nameSrc);
      if (usageId == null) {
        ctx.issue(TYPE_MATERIAL_ENTITY, rowId, "name " + nameSrc + " not found");
        return;
      }
      TypeMaterialRequest r = new TypeMaterialRequest(
          rec.get(ColdpTerm.citation),
          rec.get(ColdpTerm.status),
          rec.get(ColdpTerm.institutionCode),
          rec.get(ColdpTerm.catalogNumber),
          null,
          rec.get(ColdpTerm.locality),
          rec.get(ColdpTerm.country),
          rec.get(ColdpTerm.collector),
          rec.get(ColdpTerm.date),
          rec.get(ColdpTerm.sex),
          resolveRef(ctx, TYPE_MATERIAL_ENTITY, rowId, rec.get(ColdpTerm.referenceID)),
          rec.get(ColdpTerm.link),
          rec.get(ColdpTerm.remarks),
          ColdpParse.doubleOrNull(rec.get(ColdpTerm.latitude)),
          ColdpParse.doubleOrNull(rec.get(ColdpTerm.longitude)),
          null);
      int id = idSeq.allocate(ctx.projectId, TYPE_MATERIAL_ENTITY);
      typeMaterials.insert(ctx.projectId, id, usageId, r, userId);
    });
  }

  // Inverts ChildColdpWriter.distributionRow. taxonID (see loadChildEntities' javadoc). Distribution
  // has no ID column of its own in ColDP (see ColdpTerm.RESOURCES) -- a fresh id is always allocated,
  // never read off the row.
  private void loadDistribution(ColdpReader reader, ImportContext ctx, int userId) {
    if (!reader.hasSchema(ColdpTerm.Distribution)) {
      return;
    }
    reader.stream(ColdpTerm.Distribution).forEach(rec -> {
      String rowId = rec.get(ColdpTerm.ID);
      String taxonSrc = rec.get(ColdpTerm.taxonID);
      Integer usageId = ctx.usageIds.get(taxonSrc);
      if (usageId == null) {
        ctx.issue(DISTRIBUTION_ENTITY, rowId, "taxon " + taxonSrc + " not found");
        return;
      }
      if (!ctx.acceptedUsageIds.contains(usageId)) {
        ctx.issue(DISTRIBUTION_ENTITY, rowId,
            "taxon " + taxonSrc + " is not accepted — distribution skipped");
        return;
      }
      DistributionRequest r = new DistributionRequest(
          rec.get(ColdpTerm.area),
          rec.get(ColdpTerm.areaID),
          rec.get(ColdpTerm.gazetteer),
          rec.get(ColdpTerm.establishmentMeans),
          rec.get(ColdpTerm.threatStatus),
          resolveRef(ctx, DISTRIBUTION_ENTITY, rowId, rec.get(ColdpTerm.referenceID)),
          rec.get(ColdpTerm.remarks),
          null);
      int id = idSeq.allocate(ctx.projectId, DISTRIBUTION_ENTITY);
      distributions.insert(ctx.projectId, id, usageId, r, userId);
    });
  }

  // Inverts ChildColdpWriter.vernacularRow. taxonID (see loadChildEntities' javadoc); no ID column
  // (see loadDistribution). `preferred` inverts str(Boolean) the same way Task 4's extinct column
  // does (u.setExtinct in insertPrimaryUsage): null stays null, any non-null string goes through
  // Boolean.valueOf.
  private void loadVernacular(ColdpReader reader, ImportContext ctx, int userId) {
    if (!reader.hasSchema(ColdpTerm.VernacularName)) {
      return;
    }
    reader.stream(ColdpTerm.VernacularName).forEach(rec -> {
      String rowId = rec.get(ColdpTerm.ID);
      String taxonSrc = rec.get(ColdpTerm.taxonID);
      Integer usageId = ctx.usageIds.get(taxonSrc);
      if (usageId == null) {
        ctx.issue(VERNACULAR_ENTITY, rowId, "taxon " + taxonSrc + " not found");
        return;
      }
      if (!ctx.acceptedUsageIds.contains(usageId)) {
        ctx.issue(VERNACULAR_ENTITY, rowId,
            "taxon " + taxonSrc + " is not accepted — vernacular skipped");
        return;
      }
      String preferredStr = rec.get(ColdpTerm.preferred);
      VernacularRequest r = new VernacularRequest(
          rec.get(ColdpTerm.name),
          rec.get(ColdpTerm.language),
          rec.get(ColdpTerm.country),
          rec.get(ColdpTerm.sex),
          preferredStr == null ? null : Boolean.valueOf(preferredStr),
          resolveRef(ctx, VERNACULAR_ENTITY, rowId, rec.get(ColdpTerm.referenceID)),
          rec.get(ColdpTerm.remarks),
          null);
      int id = idSeq.allocate(ctx.projectId, VERNACULAR_ENTITY);
      vernaculars.insert(ctx.projectId, id, usageId, r, userId);
    });
  }

  // Inverts ChildColdpWriter.mediaRow. taxonID (see loadChildEntities' javadoc); no ID column (see
  // loadDistribution).
  private void loadMedia(ColdpReader reader, ImportContext ctx, int userId) {
    if (!reader.hasSchema(ColdpTerm.Media)) {
      return;
    }
    reader.stream(ColdpTerm.Media).forEach(rec -> {
      String rowId = rec.get(ColdpTerm.ID);
      String taxonSrc = rec.get(ColdpTerm.taxonID);
      Integer usageId = ctx.usageIds.get(taxonSrc);
      if (usageId == null) {
        ctx.issue(MEDIA_ENTITY, rowId, "taxon " + taxonSrc + " not found");
        return;
      }
      if (!ctx.acceptedUsageIds.contains(usageId)) {
        ctx.issue(MEDIA_ENTITY, rowId, "taxon " + taxonSrc + " is not accepted — media skipped");
        return;
      }
      MediaRequest r = new MediaRequest(
          rec.get(ColdpTerm.url),
          rec.get(ColdpTerm.type),
          rec.get(ColdpTerm.title),
          rec.get(ColdpTerm.creator),
          rec.get(ColdpTerm.license),
          rec.get(ColdpTerm.link),
          rec.get(ColdpTerm.remarks),
          null);
      int id = idSeq.allocate(ctx.projectId, MEDIA_ENTITY);
      media.insert(ctx.projectId, id, usageId, r, userId);
    });
  }

  // Inverts ChildColdpWriter.estimateRow. taxonID (see loadChildEntities' javadoc); no ID column
  // (see loadDistribution).
  private void loadEstimate(ColdpReader reader, ImportContext ctx, int userId) {
    if (!reader.hasSchema(ColdpTerm.SpeciesEstimate)) {
      return;
    }
    reader.stream(ColdpTerm.SpeciesEstimate).forEach(rec -> {
      String rowId = rec.get(ColdpTerm.ID);
      String taxonSrc = rec.get(ColdpTerm.taxonID);
      Integer usageId = ctx.usageIds.get(taxonSrc);
      if (usageId == null) {
        ctx.issue(ESTIMATE_ENTITY, rowId, "taxon " + taxonSrc + " not found");
        return;
      }
      if (!ctx.acceptedUsageIds.contains(usageId)) {
        ctx.issue(ESTIMATE_ENTITY, rowId,
            "taxon " + taxonSrc + " is not accepted — estimate skipped");
        return;
      }
      EstimateRequest r = new EstimateRequest(
          ColdpParse.intOrNull(rec.get(ColdpTerm.estimate)),
          rec.get(ColdpTerm.type),
          resolveRef(ctx, ESTIMATE_ENTITY, rowId, rec.get(ColdpTerm.referenceID)),
          rec.get(ColdpTerm.remarks),
          null);
      int id = idSeq.allocate(ctx.projectId, ESTIMATE_ENTITY);
      estimates.insert(ctx.projectId, id, usageId, r, userId);
    });
  }

  // Inverts ChildColdpWriter.nameRelationRow. Both endpoints are NAMES, not taxa (nameID +
  // relatedNameID -- see loadChildEntities' javadoc and ColdpTerm.RESOURCES); no ID column (see
  // loadDistribution). Unlike referenceID (optional -- resolveRef only issues when the column is
  // actually populated), relatedNameID is this entity's whole point, so a blank/missing/dangling
  // value skips the row with an issue exactly like the primary nameID does, rather than inserting a
  // relation to nothing.
  private void loadNameRelation(ColdpReader reader, ImportContext ctx, int userId) {
    if (!reader.hasSchema(ColdpTerm.NameRelation)) {
      return;
    }
    reader.stream(ColdpTerm.NameRelation).forEach(rec -> {
      String rowId = rec.get(ColdpTerm.ID);
      String nameSrc = rec.get(ColdpTerm.nameID);
      Integer usageId = ctx.usageIds.get(nameSrc);
      if (usageId == null) {
        ctx.issue(NAME_RELATION_ENTITY, rowId, "name " + nameSrc + " not found");
        return;
      }
      String relatedSrc = rec.get(ColdpTerm.relatedNameID);
      Integer relatedUsageId = relatedSrc == null ? null : ctx.usageIds.get(relatedSrc);
      if (relatedUsageId == null) {
        ctx.issue(NAME_RELATION_ENTITY, rowId,
            relatedSrc == null ? "missing relatedNameID" : "relatedNameID " + relatedSrc + " not found");
        return;
      }
      NameRelationRequest r = new NameRelationRequest(
          relatedUsageId,
          rec.get(ColdpTerm.type),
          resolveRef(ctx, NAME_RELATION_ENTITY, rowId, rec.get(ColdpTerm.referenceID)),
          rec.get(ColdpTerm.page),
          rec.get(ColdpTerm.remarks),
          null);
      int id = idSeq.allocate(ctx.projectId, NAME_RELATION_ENTITY);
      nameRelations.insert(ctx.projectId, id, usageId, r, userId);
    });
  }

  // Inverts ChildColdpWriter.propertyRow. taxonID (see loadChildEntities' javadoc); no ID column
  // (see loadDistribution).
  private void loadProperty(ColdpReader reader, ImportContext ctx, int userId) {
    if (!reader.hasSchema(ColdpTerm.TaxonProperty)) {
      return;
    }
    reader.stream(ColdpTerm.TaxonProperty).forEach(rec -> {
      String rowId = rec.get(ColdpTerm.ID);
      String taxonSrc = rec.get(ColdpTerm.taxonID);
      Integer usageId = ctx.usageIds.get(taxonSrc);
      if (usageId == null) {
        ctx.issue(PROPERTY_ENTITY, rowId, "taxon " + taxonSrc + " not found");
        return;
      }
      if (!ctx.acceptedUsageIds.contains(usageId)) {
        ctx.issue(PROPERTY_ENTITY, rowId,
            "taxon " + taxonSrc + " is not accepted — property skipped");
        return;
      }
      PropertyRequest r = new PropertyRequest(
          rec.get(ColdpTerm.property),
          rec.get(ColdpTerm.value),
          rec.get(ColdpTerm.page),
          resolveRef(ctx, PROPERTY_ENTITY, rowId, rec.get(ColdpTerm.referenceID)),
          rec.get(ColdpTerm.remarks),
          null);
      int id = idSeq.allocate(ctx.projectId, PROPERTY_ENTITY);
      properties.insert(ctx.projectId, id, usageId, r, userId);
    });
  }

  // Shared referenceID resolution for all 7 child loaders above: unlike the parent taxonID/nameID
  // (mandatory -- a missing/dangling one skips the whole row), a child entity's own referenceID is
  // optional (every one of the 7 Request DTOs has a nullable referenceId) -- blank/missing is simply
  // null, no issue; only an actually-populated-but-dangling value is surfaced, mirroring
  // insertPrimaryUsage's own referenceID/nameReferenceID handling above.
  private Integer resolveRef(ImportContext ctx, String entity, String rowId, String refSourceId) {
    if (refSourceId == null || refSourceId.isBlank()) {
      return null;
    }
    Integer refId = ctx.refIds.get(refSourceId);
    if (refId == null) {
      ctx.issue(entity, rowId, "referenceID " + refSourceId + " not found");
    }
    return refId;
  }

  // Pass 1 for one primary row: allocates the id, builds + parses the NameUsage, inserts it with
  // parent_id/basionym_id left null, upserts taxon_info for an accepted row carrying any of
  // extinct/environment/temporalRange, and records the source-id -> new-id mapping Pass 2 (and any
  // later task consuming ctx.usageIds) resolves everything else through. Returns null (having
  // already recorded a ctx.issue and inserted NOTHING) for a row this import cannot possibly use --
  // currently only a blank/missing scientificName, since name_usage.scientific_name is NOT NULL
  // (V3) and a usage with no name at all is meaningless; the caller must skip such a row rather
  // than add it to Pass 2's pending list or ctx.usageIds.
  private NameUsage insertPrimaryUsage(Map<ColdpTerm, String> row, ImportContext ctx, int userId,
      boolean preserveIds, String idScope, NomCode nomCode) {
    String scientificName = row.get(ColdpTerm.scientificName);
    if (scientificName == null || scientificName.isBlank()) {
      ctx.issue("name_usage", row.get(ColdpTerm.ID), "skipped: blank scientificName");
      return null;
    }

    NameUsage u = new NameUsage();
    u.setProjectId(ctx.projectId);
    u.setId(idSeq.allocate(ctx.projectId, NAME_USAGE_ENTITY));
    u.setModifiedBy(userId);

    // Inverts NameUsageColdpWriter.nameFields/acceptedRow field-for-field.
    u.setScientificName(scientificName);
    u.setAuthorship(row.get(ColdpTerm.authorship));
    u.setRank(row.get(ColdpTerm.rank));
    u.setUninomial(row.get(ColdpTerm.uninomial));
    u.setGenus(row.get(ColdpTerm.genericName));
    u.setInfragenericEpithet(row.get(ColdpTerm.infragenericEpithet));
    u.setSpecificEpithet(row.get(ColdpTerm.specificEpithet));
    u.setInfraspecificEpithet(row.get(ColdpTerm.infraspecificEpithet));
    u.setCultivarEpithet(row.get(ColdpTerm.cultivarEpithet));
    u.setNotho(ColdpParse.parseEnum(NamePart.class, row.get(ColdpTerm.notho)));
    u.setCombinationAuthorship(row.get(ColdpTerm.combinationAuthorship));
    u.setCombinationExAuthorship(row.get(ColdpTerm.combinationExAuthorship));
    u.setCombinationAuthorshipYear(row.get(ColdpTerm.combinationAuthorshipYear));
    u.setBasionymAuthorship(row.get(ColdpTerm.basionymAuthorship));
    u.setBasionymExAuthorship(row.get(ColdpTerm.basionymExAuthorship));
    u.setBasionymAuthorshipYear(row.get(ColdpTerm.basionymAuthorshipYear));
    u.setNamePhrase(row.get(ColdpTerm.namePhrase));
    u.setPublishedInYear(ColdpParse.intOrNull(row.get(ColdpTerm.namePublishedInYear)));
    u.setPublishedInPage(row.get(ColdpTerm.namePublishedInPage));
    u.setPublishedInPageLink(row.get(ColdpTerm.namePublishedInPageLink));
    u.setGender(ColdpParse.parseEnum(Gender.class, row.get(ColdpTerm.gender)));
    u.setEtymology(row.get(ColdpTerm.etymology));
    u.setNomStatus(ColdpParse.parseEnum(NomStatus.class, row.get(ColdpTerm.nameStatus)));
    u.setOrdinal(ColdpParse.intOrNull(row.get(ColdpTerm.ordinal)));
    u.setRemarks(row.get(ColdpTerm.remarks));

    List<String> altIds = new ArrayList<>(ColdpParse.csv(row.get(ColdpTerm.alternativeID)));
    if (preserveIds) {
      String curie = idScope + ":" + row.get(ColdpTerm.ID);
      if (!altIds.contains(curie)) {
        altIds.add(curie);
      }
    }
    u.setAlternativeId(altIds.isEmpty() ? null : altIds);

    Status status = ColdpParse.parseStatus(row.get(ColdpTerm.status));
    u.setStatus(status == null ? Status.UNASSESSED : status);

    // extinct/environment/temporalRange* live in taxon_info and only ever apply to accepted usages
    // -- acceptedRow is the only NameUsageColdpWriter branch that writes them (synonymRows never
    // does), so only an ACCEPTED row is allowed to populate them here.
    if (u.getStatus() == Status.ACCEPTED) {
      String extinctStr = row.get(ColdpTerm.extinct);
      u.setExtinct(extinctStr == null ? null : Boolean.valueOf(extinctStr));
      List<Environment> envs = ColdpParse.csv(row.get(ColdpTerm.environment)).stream()
          .map(e -> ColdpParse.parseEnum(Environment.class, e))
          .filter(Objects::nonNull)
          .toList();
      u.setEnvironment(envs.isEmpty() ? null : envs);
      u.setTemporalRangeStart(row.get(ColdpTerm.temporalRangeStart));
      u.setTemporalRangeEnd(row.get(ColdpTerm.temporalRangeEnd));
    }

    // References were already inserted earlier in this same transaction (Task 3, loadReferences),
    // so remapping both the single published-in reference and the taxonomic reference_id[] is safe
    // right now, well before Pass 2 resolves the self-referencing hierarchy columns. Both the
    // single nameReferenceID and each entry of the referenceID list are looked up directly in
    // ctx.refIds (a plain source-id string match, exactly how References were keyed in
    // loadReferences) and a dangling entry in either is surfaced as a ctx.issue -- symmetric
    // handling, rather than referenceID silently dropping non-numeric/unmatched ids while
    // nameReferenceID silently nulled them.
    String nameRefSrc = row.get(ColdpTerm.nameReferenceID);
    if (nameRefSrc == null || nameRefSrc.isBlank()) {
      u.setPublishedInReferenceId(null);
    } else {
      Integer nameRefId = ctx.refIds.get(nameRefSrc);
      if (nameRefId == null) {
        ctx.issue("name_usage", row.get(ColdpTerm.ID), "nameReferenceID " + nameRefSrc + " not found");
      }
      u.setPublishedInReferenceId(nameRefId);
    }
    List<Integer> refIdList = new ArrayList<>();
    for (String refSrc : ColdpParse.csv(row.get(ColdpTerm.referenceID))) {
      Integer refId = ctx.refIds.get(refSrc);
      if (refId == null) {
        ctx.issue("name_usage", row.get(ColdpTerm.ID), "referenceID " + refSrc + " not found");
      } else {
        refIdList.add(refId);
      }
    }
    // Empty -> null (not []), matching NameUsageService.create's own convention for a usage with no
    // taxonomic references; both export as null anyway (NameUsageColdpWriter).
    u.setReferenceId(refIdList.isEmpty() ? null : refIdList);

    // parent_id/basionym_id stay null here -- Pass 2 in loadNameUsages sets them via
    // NameUsageMapper.updateHierarchy once every usage in the archive has been inserted.

    // Archive atomized columns set above are advisory: parseInto unconditionally clears and
    // re-derives them (plus sanctioningAuthor -- a known loss) from scientificName/authorship/rank,
    // matching NameUsageService.create's own parse-before-insert order exactly. Never throws.
    parser.parseInto(u, nomCode);
    // parseInto only ever (re-)sets rank when the parse itself yields one -- see its javadoc and
    // ParsedNameMapping.applyTo's `if (pn.getRank() != null)` guard -- so an unparsable name
    // (virus/OTU/BOLD-BIN/placeholder, or simply a blank archive rank column) can leave
    // u.getRank() null/blank, which would violate name_usage.rank's NOT NULL constraint (V3).
    // Fall back to the parser's own UNRANKED sentinel, stored in the SAME lower-case form
    // ParsedNameMapping.applyTo uses on a successful parse (Rank.UNRANKED.name().toLowerCase()),
    // rather than let one bad archive row abort the entire import with an opaque SQL error.
    if (u.getRank() == null || u.getRank().isBlank()) {
      u.setRank("unranked");
    }

    usages.insert(u);
    if (u.getStatus() == Status.ACCEPTED && hasTaxonInfo(u)) {
      taxonInfo.upsert(ctx.projectId, u.getId(), u.getExtinct(), u.getEnvironment(),
          u.getTemporalRangeStart(), u.getTemporalRangeEnd());
    }
    String usageSrcId = row.get(ColdpTerm.ID);
    // See loadReferences' identical duplicate-ID diagnostic; here a shadowed source id means any
    // later parentID/basionymID/taxonID/nameID reference to the earlier row resolves to this one
    // instead (Pass 2 and every Task 5 child loader consult ctx.usageIds the same way).
    if (usageSrcId != null && ctx.usageIds.containsKey(usageSrcId)) {
      ctx.issue("name_usage", usageSrcId, "duplicate ID — later row shadows earlier in cross-references");
    }
    ctx.usageIds.put(usageSrcId, u.getId());
    if (u.getStatus() == Status.ACCEPTED) {
      ctx.acceptedUsageIds.add(u.getId());
    }
    ctx.nameUsageCount++;
    return u;
  }

  private static boolean hasTaxonInfo(NameUsage u) {
    return u.getExtinct() != null || u.getEnvironment() != null
        || u.getTemporalRangeStart() != null || u.getTemporalRangeEnd() != null;
  }

  // Pass-2 helper: resolves a row's parentID/basionymID source-id string to the new id Pass 1
  // allocated for it, or issues a dangling-reference ImportIssue and returns null (a bad/missing
  // reference in the archive is surfaced, never fatal to the rest of the import). `fallback`, when
  // non-null, is tried only after a plain ctx.usageIds miss -- used for split-form basionymID (see
  // loadNameUsages' nameIdToUsage); every other caller passes null, so a miss there is always a
  // genuine dangling reference.
  private Integer resolveUsageRef(ImportContext ctx, Map<String, Integer> fallback, String sourceId,
      String kind, String rowId) {
    if (sourceId == null || sourceId.isBlank()) {
      return null;
    }
    Integer newId = ctx.usageIds.get(sourceId);
    if (newId == null && fallback != null) {
      newId = fallback.get(sourceId);
    }
    if (newId == null) {
      ctx.issue("name_usage", rowId, kind + " " + sourceId + " not found");
    }
    return newId;
  }

  // The fixed set of ColdpTerm columns loadNameUsages reads off every row, whichever form the
  // archive uses: for the combined form (readCombinedRows/toUsageRow) these are literally
  // NameUsage.tsv's own column names; for the split form (readSplitFormRows/synthesizeUsageRow)
  // they are synthesized under the SAME keys, so insertPrimaryUsage/loadNameUsages never need to
  // care which form actually produced a given row.
  private static final List<ColdpTerm> USAGE_ROW_TERMS = List.of(
      ColdpTerm.ID, ColdpTerm.parentID, ColdpTerm.basionymID, ColdpTerm.status,
      ColdpTerm.scientificName, ColdpTerm.authorship, ColdpTerm.rank, ColdpTerm.notho,
      ColdpTerm.uninomial, ColdpTerm.genericName, ColdpTerm.infragenericEpithet,
      ColdpTerm.specificEpithet, ColdpTerm.infraspecificEpithet, ColdpTerm.cultivarEpithet,
      ColdpTerm.combinationAuthorship, ColdpTerm.combinationExAuthorship,
      ColdpTerm.combinationAuthorshipYear, ColdpTerm.basionymAuthorship,
      ColdpTerm.basionymExAuthorship, ColdpTerm.basionymAuthorshipYear, ColdpTerm.namePhrase,
      ColdpTerm.nameReferenceID, ColdpTerm.namePublishedInYear, ColdpTerm.namePublishedInPage,
      ColdpTerm.namePublishedInPageLink, ColdpTerm.gender, ColdpTerm.etymology,
      ColdpTerm.nameStatus, ColdpTerm.referenceID, ColdpTerm.ordinal, ColdpTerm.remarks,
      ColdpTerm.alternativeID, ColdpTerm.extinct, ColdpTerm.environment,
      ColdpTerm.temporalRangeStart, ColdpTerm.temporalRangeEnd);

  private static List<Map<ColdpTerm, String>> readCombinedRows(ColdpReader reader) {
    return reader.stream(ColdpTerm.NameUsage).map(ImportRunService::toUsageRow).toList();
  }

  private static Map<ColdpTerm, String> toUsageRow(VerbatimRecord rec) {
    Map<ColdpTerm, String> row = new LinkedHashMap<>();
    for (ColdpTerm t : USAGE_ROW_TERMS) {
      row.put(t, rec.get(t));
    }
    return row;
  }

  // readSplitFormRows' return: the synthesized combined rows, plus the source Name-ID -> source
  // Taxon/Synonym-ID side index loadNameUsages needs to resolve a split-form Name.basionymID (see
  // its nameIdToUsage / Fix-3 comment there) -- Name.basionymID is a Name-id, but ctx.usageIds is
  // keyed by Taxon/Synonym ids, so that side index is the only way to bridge the two.
  private record SplitFormRows(List<Map<ColdpTerm, String>> rows, Map<String, String> nameIdToUsageSourceId) {}

  // Split-form synthesis (design brief Step 7): the archive has Name+Taxon(+Synonym) instead of a
  // combined NameUsage file. Our model is combined, so every Taxon/Synonym row is joined to its
  // Name (via nameID) into one synthetic combined row under the same USAGE_ROW_TERMS keys
  // readCombinedRows uses -- a Name shared by N usages therefore correctly yields N separate
  // name_usage rows, one per Taxon/Synonym row that references it. A dangling nameID (no matching
  // Name row) is surfaced as an ImportIssue and that Taxon/Synonym row is skipped. Also records,
  // first-wins, each Name's own ID against the ID of the first Taxon/Synonym row that used it, so
  // loadNameUsages can later resolve a basionymID that points at a Name rather than a usage.
  private SplitFormRows readSplitFormRows(ColdpReader reader, ImportContext ctx) {
    Map<String, VerbatimRecord> namesById = new HashMap<>();
    reader.stream(ColdpTerm.Name).forEach(r -> namesById.put(r.get(ColdpTerm.ID), r));

    List<Map<ColdpTerm, String>> rows = new ArrayList<>();
    Map<String, String> nameIdToUsageSourceId = new HashMap<>();
    reader.stream(ColdpTerm.Taxon).forEach(r -> {
      String nameId = r.get(ColdpTerm.nameID);
      VerbatimRecord nameRec = namesById.get(nameId);
      if (nameRec == null) {
        ctx.issue("name_usage", r.get(ColdpTerm.ID), "nameID " + nameId + " not found in Name.tsv");
        return;
      }
      nameIdToUsageSourceId.putIfAbsent(nameId, r.get(ColdpTerm.ID));
      rows.add(synthesizeUsageRow(nameRec, r, false));
    });
    if (reader.hasSchema(ColdpTerm.Synonym)) {
      reader.stream(ColdpTerm.Synonym).forEach(r -> {
        String nameId = r.get(ColdpTerm.nameID);
        VerbatimRecord nameRec = namesById.get(nameId);
        if (nameRec == null) {
          ctx.issue("name_usage", r.get(ColdpTerm.ID), "nameID " + nameId + " not found in Name.tsv");
          return;
        }
        nameIdToUsageSourceId.putIfAbsent(nameId, r.get(ColdpTerm.ID));
        rows.add(synthesizeUsageRow(nameRec, r, true));
      });
    }
    return new SplitFormRows(rows, nameIdToUsageSourceId);
  }

  // Joins one Name row with the Taxon/Synonym row using it: name-level columns come from `nameRec`
  // (Name.tsv's own column names -- genus/referenceID/publishedInYear/.../status differ from the
  // combined form's genericName/nameReferenceID/namePublishedInYear/.../nameStatus, so this is
  // exactly where that renaming happens), taxon/synonym-level columns come from `usageRec`. A
  // Synonym row's own taxonID becomes the combined row's parentID (Synonym has no parentID column
  // of its own). A Taxon row's own status is implicit -- ColDP's Taxon.tsv carries no status column
  // at all, so "accepted" is assumed, or "provisionally accepted" when the row carries
  // provisional=true. A Synonym row's own status column (synonym/ambiguous synonym/misapplied) is
  // honored as-is, defaulting to "synonym" when blank.
  private static Map<ColdpTerm, String> synthesizeUsageRow(VerbatimRecord nameRec, VerbatimRecord usageRec,
      boolean isSynonym) {
    Map<ColdpTerm, String> row = new LinkedHashMap<>();
    row.put(ColdpTerm.ID, usageRec.get(ColdpTerm.ID));
    row.put(ColdpTerm.parentID, isSynonym ? usageRec.get(ColdpTerm.taxonID) : usageRec.get(ColdpTerm.parentID));
    row.put(ColdpTerm.basionymID, nameRec.get(ColdpTerm.basionymID));
    if (isSynonym) {
      String synStatus = usageRec.get(ColdpTerm.status);
      row.put(ColdpTerm.status, synStatus == null ? "synonym" : synStatus);
    } else {
      boolean provisional = Boolean.parseBoolean(usageRec.get(ColdpTerm.provisional));
      row.put(ColdpTerm.status, provisional ? "provisionally accepted" : "accepted");
    }
    row.put(ColdpTerm.scientificName, nameRec.get(ColdpTerm.scientificName));
    row.put(ColdpTerm.authorship, nameRec.get(ColdpTerm.authorship));
    row.put(ColdpTerm.rank, nameRec.get(ColdpTerm.rank));
    row.put(ColdpTerm.notho, nameRec.get(ColdpTerm.notho));
    row.put(ColdpTerm.uninomial, nameRec.get(ColdpTerm.uninomial));
    row.put(ColdpTerm.genericName, nameRec.get(ColdpTerm.genus));
    row.put(ColdpTerm.infragenericEpithet, nameRec.get(ColdpTerm.infragenericEpithet));
    row.put(ColdpTerm.specificEpithet, nameRec.get(ColdpTerm.specificEpithet));
    row.put(ColdpTerm.infraspecificEpithet, nameRec.get(ColdpTerm.infraspecificEpithet));
    row.put(ColdpTerm.cultivarEpithet, nameRec.get(ColdpTerm.cultivarEpithet));
    row.put(ColdpTerm.combinationAuthorship, nameRec.get(ColdpTerm.combinationAuthorship));
    row.put(ColdpTerm.combinationExAuthorship, nameRec.get(ColdpTerm.combinationExAuthorship));
    row.put(ColdpTerm.combinationAuthorshipYear, nameRec.get(ColdpTerm.combinationAuthorshipYear));
    row.put(ColdpTerm.basionymAuthorship, nameRec.get(ColdpTerm.basionymAuthorship));
    row.put(ColdpTerm.basionymExAuthorship, nameRec.get(ColdpTerm.basionymExAuthorship));
    row.put(ColdpTerm.basionymAuthorshipYear, nameRec.get(ColdpTerm.basionymAuthorshipYear));
    row.put(ColdpTerm.namePhrase, usageRec.get(ColdpTerm.namePhrase));
    row.put(ColdpTerm.nameReferenceID, nameRec.get(ColdpTerm.referenceID));
    row.put(ColdpTerm.namePublishedInYear, nameRec.get(ColdpTerm.publishedInYear));
    row.put(ColdpTerm.namePublishedInPage, nameRec.get(ColdpTerm.publishedInPage));
    row.put(ColdpTerm.namePublishedInPageLink, nameRec.get(ColdpTerm.publishedInPageLink));
    row.put(ColdpTerm.gender, nameRec.get(ColdpTerm.gender));
    row.put(ColdpTerm.etymology, nameRec.get(ColdpTerm.etymology));
    row.put(ColdpTerm.nameStatus, nameRec.get(ColdpTerm.status));
    row.put(ColdpTerm.referenceID, usageRec.get(ColdpTerm.referenceID));
    row.put(ColdpTerm.remarks, usageRec.get(ColdpTerm.remarks));
    row.put(ColdpTerm.alternativeID, mergeCsv(nameRec.get(ColdpTerm.alternativeID),
        isSynonym ? null : usageRec.get(ColdpTerm.alternativeID)));
    if (!isSynonym) {
      row.put(ColdpTerm.ordinal, usageRec.get(ColdpTerm.ordinal));
      row.put(ColdpTerm.extinct, usageRec.get(ColdpTerm.extinct));
      row.put(ColdpTerm.environment, usageRec.get(ColdpTerm.environment));
      row.put(ColdpTerm.temporalRangeStart, usageRec.get(ColdpTerm.temporalRangeStart));
      row.put(ColdpTerm.temporalRangeEnd, usageRec.get(ColdpTerm.temporalRangeEnd));
    }
    return row;
  }

  // Union of two alternativeID CSV strings (Name's own + Taxon's own -- Synonym.tsv has no
  // alternativeID column at all, see ColdpTerm.RESOURCES), de-duplicated, comma-joined; null when
  // both are empty.
  private static String mergeCsv(String a, String b) {
    List<String> merged = new ArrayList<>(ColdpParse.csv(a));
    for (String v : ColdpParse.csv(b)) {
      if (!merged.contains(v)) {
        merged.add(v);
      }
    }
    return merged.isEmpty() ? null : String.join(",", merged);
  }

  // Shared alternativeId-building for both loadReferences and loadAuthors: the archive row's own
  // alternativeID CSV column, plus -- when preserveIds -- an "<idScope>:<archive-row-ID>" CURIE, so
  // a later re-import (or any external tool matching on ids) can recover this row's original source
  // id. ColdpParse.csv returns Guava's immutable splitToList result, so it's always copied into a
  // fresh, mutable ArrayList before any preserveIds append; a duplicate append (the CURIE already
  // present verbatim in the archive's own alternativeID column) is skipped rather than repeated.
  private static List<String> alternativeIds(VerbatimRecord rec, boolean preserveIds, String idScope) {
    List<String> altId = new ArrayList<>(ColdpParse.csv(rec.get(ColdpTerm.alternativeID)));
    if (preserveIds) {
      String curie = idScope + ":" + rec.get(ColdpTerm.ID);
      if (!altId.contains(curie)) {
        altId.add(curie);
      }
    }
    return altId.isEmpty() ? null : altId;
  }

  // The nomenclatural code isn't its own ColDP file -- it rides along as a `code` column on
  // NameUsage (or, in the Name+Taxon-file archive shape, Taxon) rows, and is expected to be
  // dataset-wide, so peeking the first data row's value is sufficient (later tasks that actually
  // walk every row don't need to re-derive this).
  //
  // Known, accepted leak: readFirstRow -> stream(...).findFirst() only pulls ONE row off the
  // univocity-backed iterator (life.catalogue.csv.CsvReader.TermRecIterator), which opens the data
  // file's InputStream in its constructor (nextFile) and only lets univocity close it once iteration
  // is driven to EOF; findFirst() short-circuits after the first element, so that file handle is
  // abandoned rather than closed. Neither CsvReader nor ColdpReader (org.catalogueoflife:reader
  // 1.3.0-SNAPSHOT) implements Closeable/AutoCloseable or exposes any close()/shutdown method, so
  // there is nothing to call here or wrap in try-with-resources -- the handle is only reclaimed when
  // the abandoned parser/stream is GC'd (finalization / Cleaner-driven, JDK/platform dependent). This
  // is one leaked FD per import run, on ImportAsyncConfig's single-thread executor, so imports can
  // never pile these up concurrently; low impact, not an oversight. A real fix would need an
  // upstream close() on CsvReader itself.
  private static NomCode peekNomCode(ColdpReader reader) {
    Optional<VerbatimRecord> row = reader.hasSchema(ColdpTerm.NameUsage)
        ? reader.readFirstRow(ColdpTerm.NameUsage)
        : reader.readFirstRow(ColdpTerm.Taxon);
    return row.map(r -> ColdpParse.parseEnum(NomCode.class, r.get(ColdpTerm.code))).orElse(null);
  }

  // metadata.yaml's title is optional; when absent, fall back to the uploaded filename minus its
  // extension (e.g. "my-checklist.zip" -> "my-checklist") rather than leaving the project untitled.
  private static String filenameStem(String sourceName) {
    if (sourceName == null || sourceName.isBlank()) {
      return "import";
    }
    String base = sourceName;
    int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
    if (slash >= 0) {
      base = base.substring(slash + 1);
    }
    int dot = base.lastIndexOf('.');
    String stem = dot > 0 ? base.substring(0, dot) : base;
    return stem.isBlank() ? "import" : stem;
  }

  // Best-effort recursive delete of the extracted archive's temp dir -- called from run()'s finally
  // (always, success or failure) and from start()'s own extraction-failure path. A leftover
  // directory tree here is a disk-space nuisance, never a correctness issue (nothing downstream
  // reads it again), so failures are swallowed exactly like ExportRunService.run's cleanup.
  private void deleteQuietly(Path dir) {
    try {
      if (!Files.exists(dir)) {
        return;
      }
      try (var walk = Files.walk(dir)) {
        walk.sorted(Comparator.reverseOrder()).forEach(p -> {
          try {
            Files.deleteIfExists(p);
          } catch (IOException ignored) {
            // best-effort
          }
        });
      }
    } catch (IOException ignored) {
      // best-effort
    }
  }
}
