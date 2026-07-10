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
// POST /projects). run() opens the archive, creates the project, then loads references, authors and
// name-usages (loadReferences/loadAuthors/loadNameUsages, phases 1-4), consuming/extending the
// ImportContext (refIds/authorIds/usageIds source-id maps) built here; a later task may extend
// loadTransactional further for any remaining ColDP resource types (vernacular names, distributions,
// media, ...).
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
  @Async(ImportAsyncConfig.EXECUTOR_BEAN)
  public void run(long runId, Path dir, int userId, boolean preserveIds, String idScope) {
    try {
      ImportContext ctx = self.loadTransactional(runId, dir, userId, preserveIds, idScope);
      runs.finish(runId, ctx.nameUsageCount, ctx.referenceCount, ctx.authorCount,
          ctx.issues.isEmpty() ? null : json.writeValueAsString(ctx.issues));
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
  // dependencies on anything else in the archive (Task 4's name/usage load, and Task 5's tree load,
  // both come after and consume the ImportContext this method returns). @Transactional so a failure
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
      ctx.refIds.put(rec.get(ColdpTerm.ID), r.getId());
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
      ctx.authorIds.put(rec.get(ColdpTerm.ID), a.getId());
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
    List<Map<ColdpTerm, String>> allRows = reader.hasSchema(ColdpTerm.NameUsage)
        ? readCombinedRows(reader)
        : readSplitFormRows(reader, ctx);

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
    // pairs for Pass 2 below.
    record Pending(NameUsage usage, Map<ColdpTerm, String> row) {}
    List<Pending> pending = new ArrayList<>(primaryRows.size());
    for (Map<ColdpTerm, String> row : primaryRows) {
      NameUsage u = insertPrimaryUsage(row, ctx, userId, preserveIds, idScope, nomCode);
      pending.add(new Pending(u, row));
    }

    // Pass 2: every usage now exists, so parent_id/basionym_id (accepted) or a synonym_accepted
    // link (non-accepted) can finally be resolved; a dangling reference is surfaced as an
    // ImportIssue rather than failing the whole import.
    for (Pending p : pending) {
      Map<ColdpTerm, String> row = p.row();
      String rowId = row.get(ColdpTerm.ID);
      Integer basionymNewId = resolveUsageRef(ctx, row.get(ColdpTerm.basionymID), "basionym", rowId);
      if (p.usage().getStatus() == Status.ACCEPTED) {
        Integer parentNewId = resolveUsageRef(ctx, row.get(ColdpTerm.parentID), "parent", rowId);
        usages.updateHierarchy(ctx.projectId, p.usage().getId(), parentNewId, basionymNewId, userId);
      } else {
        Integer acceptedNewId = resolveUsageRef(ctx, row.get(ColdpTerm.parentID), "parent", rowId);
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

  // Pass 1 for one primary row: allocates the id, builds + parses the NameUsage, inserts it with
  // parent_id/basionym_id left null, upserts taxon_info for an accepted row carrying any of
  // extinct/environment/temporalRange, and records the source-id -> new-id mapping Pass 2 (and any
  // later task consuming ctx.usageIds) resolves everything else through.
  private NameUsage insertPrimaryUsage(Map<ColdpTerm, String> row, ImportContext ctx, int userId,
      boolean preserveIds, String idScope, NomCode nomCode) {
    NameUsage u = new NameUsage();
    u.setProjectId(ctx.projectId);
    u.setId(idSeq.allocate(ctx.projectId, NAME_USAGE_ENTITY));
    u.setModifiedBy(userId);

    // Inverts NameUsageColdpWriter.nameFields/acceptedRow field-for-field.
    u.setScientificName(row.get(ColdpTerm.scientificName));
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
    // right now, well before Pass 2 resolves the self-referencing hierarchy columns.
    u.setPublishedInReferenceId(ctx.refIds.get(row.get(ColdpTerm.nameReferenceID)));
    u.setReferenceId(ColdpParse.csvInts(row.get(ColdpTerm.referenceID)).stream()
        .map(i -> ctx.refIds.get(String.valueOf(i)))
        .filter(Objects::nonNull)
        .toList());

    // parent_id/basionym_id stay null here -- Pass 2 in loadNameUsages sets them via
    // NameUsageMapper.updateHierarchy once every usage in the archive has been inserted.

    // Archive atomized columns set above are advisory: parseInto unconditionally clears and
    // re-derives them (plus sanctioningAuthor -- a known loss) from scientificName/authorship/rank,
    // matching NameUsageService.create's own parse-before-insert order exactly. Never throws.
    parser.parseInto(u, nomCode);

    usages.insert(u);
    if (u.getStatus() == Status.ACCEPTED && hasTaxonInfo(u)) {
      taxonInfo.upsert(ctx.projectId, u.getId(), u.getExtinct(), u.getEnvironment(),
          u.getTemporalRangeStart(), u.getTemporalRangeEnd());
    }
    ctx.usageIds.put(row.get(ColdpTerm.ID), u.getId());
    ctx.nameUsageCount++;
    return u;
  }

  private static boolean hasTaxonInfo(NameUsage u) {
    return u.getExtinct() != null || u.getEnvironment() != null
        || u.getTemporalRangeStart() != null || u.getTemporalRangeEnd() != null;
  }

  // Pass-2 helper: resolves a row's parentID/basionymID source-id string to the new id Pass 1
  // allocated for it, or issues a dangling-reference ImportIssue and returns null (a bad/missing
  // reference in the archive is surfaced, never fatal to the rest of the import).
  private Integer resolveUsageRef(ImportContext ctx, String sourceId, String kind, String rowId) {
    if (sourceId == null || sourceId.isBlank()) {
      return null;
    }
    Integer newId = ctx.usageIds.get(sourceId);
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

  // Split-form synthesis (design brief Step 7): the archive has Name+Taxon(+Synonym) instead of a
  // combined NameUsage file. Our model is combined, so every Taxon/Synonym row is joined to its
  // Name (via nameID) into one synthetic combined row under the same USAGE_ROW_TERMS keys
  // readCombinedRows uses -- a Name shared by N usages therefore correctly yields N separate
  // name_usage rows, one per Taxon/Synonym row that references it. A dangling nameID (no matching
  // Name row) is surfaced as an ImportIssue and that Taxon/Synonym row is skipped.
  private List<Map<ColdpTerm, String>> readSplitFormRows(ColdpReader reader, ImportContext ctx) {
    Map<String, VerbatimRecord> namesById = new HashMap<>();
    reader.stream(ColdpTerm.Name).forEach(r -> namesById.put(r.get(ColdpTerm.ID), r));

    List<Map<ColdpTerm, String>> rows = new ArrayList<>();
    reader.stream(ColdpTerm.Taxon).forEach(r -> {
      VerbatimRecord nameRec = namesById.get(r.get(ColdpTerm.nameID));
      if (nameRec == null) {
        ctx.issue("name_usage", r.get(ColdpTerm.ID),
            "nameID " + r.get(ColdpTerm.nameID) + " not found in Name.tsv");
        return;
      }
      rows.add(synthesizeUsageRow(nameRec, r, false));
    });
    if (reader.hasSchema(ColdpTerm.Synonym)) {
      reader.stream(ColdpTerm.Synonym).forEach(r -> {
        VerbatimRecord nameRec = namesById.get(r.get(ColdpTerm.nameID));
        if (nameRec == null) {
          ctx.issue("name_usage", r.get(ColdpTerm.ID),
              "nameID " + r.get(ColdpTerm.nameID) + " not found in Name.tsv");
          return;
        }
        rows.add(synthesizeUsageRow(nameRec, r, true));
      });
    }
    return rows;
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
