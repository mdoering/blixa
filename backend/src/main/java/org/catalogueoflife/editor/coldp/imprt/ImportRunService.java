package org.catalogueoflife.editor.coldp.imprt;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import life.catalogue.api.model.VerbatimRecord;
import life.catalogue.coldp.ColdpTerm;
import life.catalogue.csv.ColdpReader;
import org.catalogueoflife.editor.coldp.imprt.dto.ImportRunResponse;
import org.catalogueoflife.editor.coldp.io.ColdpMetadata;
import org.catalogueoflife.editor.coldp.io.ColdpMetadata.ColdpMetadataDto;
import org.catalogueoflife.editor.coldp.io.ColdpZip;
import org.catalogueoflife.editor.project.Project;
import org.catalogueoflife.editor.project.ProjectService;
import org.catalogueoflife.editor.project.dto.CreateProjectRequest;
import org.catalogueoflife.editor.project.dto.UpdateProjectMetadataRequest;
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
// POST /projects). This task's run() only opens the archive and creates the project (phases 1-2);
// Tasks 3-5 extend it to load references, names and the taxon/synonym tree.
@Service
public class ImportRunService {

  private static final Logger log = LoggerFactory.getLogger(ImportRunService.class);

  private final ImportRunMapper runs;
  private final ProjectService projectService;
  private final ObjectMapper json;
  private final Path importDir;
  private final long maxBytes;

  // Self-reference through the Spring proxy so run()'s @Async and loadTransactional's @Transactional
  // actually go through their proxied annotations -- see ExportRunService's identical `self` field
  // (and ColMatchJobService's run -> self.matchOneScope split) for the same reason: a plain
  // `this.foo(...)` call bypasses the proxy entirely. @Lazy avoids a circular bean-creation error
  // (this bean depending on itself while still being constructed).
  @Autowired
  @Lazy
  private ImportRunService self;

  public ImportRunService(ImportRunMapper runs, ProjectService projectService, ObjectMapper json,
      @Value("${coldp.import.dir:${java.io.tmpdir}/coldp-imports}") String importDir,
      @Value("${coldp.import.max-bytes:104857600}") long maxBytes) {
    this.runs = runs;
    this.projectService = projectService;
    this.json = json;
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
  // called start(). loadTransactional does the actual archive-open + project-creation work inside
  // its own @Transactional (see its javadoc for why runs.finish/runs.fail must stay OUT here rather
  // than inside it); any exception it throws marks the whole row FAILED with the exception's message
  // rather than leaving it stuck RUNNING forever -- there is no caller left to propagate the
  // exception to once we're here, same contract as ExportRunService.run / ColMatchJobService.run.
  // The extracted temp dir is always removed afterwards, success or failure: once this method
  // returns, nothing further ever reads it again.
  @Async(ImportAsyncConfig.EXECUTOR_BEAN)
  public void run(long runId, Path dir, int userId, boolean preserveIds, String idScope) {
    try {
      self.loadTransactional(runId, dir, userId);
      // Counts stay 0 / issues null in this task -- Tasks 3-5 extend loadTransactional to actually
      // load references/names/the tree and report real counts here.
      runs.finish(runId, 0, 0, 0, null);
    } catch (Exception e) {
      log.warn("import run {} failed for user {}: {}", runId, userId, e.getMessage(), e);
      runs.fail(runId, e.getMessage());
    } finally {
      deleteQuietly(dir);
    }
  }

  // Phases 1-2 of the import: open the archive, require it actually has usage data, read
  // metadata.yaml, peek the nomenclatural code off the first data row, and create + configure the
  // project. @Transactional so a failure partway through (e.g. an invalid license in metadata.yaml)
  // rolls back the whole thing -- the just-inserted project row along with it -- rather than leaving
  // a half-configured project behind; matches the spec's "rollback + FAILED, delete nothing [from
  // the DB]" contract for a failed import. Deliberately does NOT call runs.finish/runs.fail itself:
  // those live in the non-transactional run() above, in their own separate (uncommitted-rollback-
  // proof) statements, so the FAILED status write always survives even when this transaction rolls
  // back -- exactly the split ColMatchJobService.run/runSync/matchOneScope uses (there, matchOneScope
  // is the @Transactional leaf and runSync/run stay outside it).
  @Transactional(rollbackFor = Exception.class)
  public void loadTransactional(long runId, Path dir, int userId) throws IOException {
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
