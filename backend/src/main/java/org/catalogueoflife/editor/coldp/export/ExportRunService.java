package org.catalogueoflife.editor.coldp.export;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.catalogueoflife.editor.coldp.export.dto.ExportRunResponse;
import org.catalogueoflife.editor.project.Project;
import org.catalogueoflife.editor.project.ProjectMapper;
import org.catalogueoflife.editor.project.ProjectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

// The project-wide ColDP export job: start/run are the async trigger (ExportRunController -> start,
// which authorizes the caller and inserts the RUNNING export_run row on the request thread, then
// fires run() -- @Async off ExportAsyncConfig's dedicated single-thread pool -- so the request
// returns immediately with a 202 while the (possibly long) export proceeds in the background).
// Mirrors ColMatchJobService's start/run shape exactly, but authorization is "any project member"
// (export is a read, not a write-adjacent bulk action like COL-match) and the job body is
// ColdpWriter.write rather than a per-usage CLB round trip.
@Service
public class ExportRunService {

  private static final Logger log = LoggerFactory.getLogger(ExportRunService.class);
  private static final String EXPORT_IN_PROGRESS = "an export is already in progress for this project";

  private final ExportRunMapper runs;
  private final ProjectService projectService;
  private final ProjectMapper projects;
  private final ColdpWriter writer;
  private final Path exportDir;

  // Self-reference through the Spring proxy so run()'s @Async actually goes through the proxied
  // annotation -- see ColMatchJobService's identical `self` field for the same reason: a plain
  // `this.run(...)` call bypasses the proxy entirely. @Lazy avoids a circular bean-creation error
  // (this bean depending on itself while still being constructed).
  @Autowired
  @Lazy
  private ExportRunService self;

  public ExportRunService(ExportRunMapper runs, ProjectService projectService, ProjectMapper projects,
      ColdpWriter writer, @Value("${coldp.export.dir:${java.io.tmpdir}/coldp-exports}") String exportDir) {
    this.runs = runs;
    this.projectService = projectService;
    this.projects = projects;
    this.writer = writer;
    this.exportDir = Path.of(exportDir);
    try {
      Files.createDirectories(this.exportDir);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to create export dir " + exportDir, e);
    }
  }

  // Authorizes the caller (any member -- export only reads project data, unlike ColMatchJobService.
  // start's owner/editor gate), enforces the one-active-run-per-project guard, inserts the RUNNING
  // export_run row synchronously (so the 202 response always has a real id to poll), and fires the
  // async run through `self` so @Async actually applies, then reads the row straight back for the
  // response body.
  public ExportRunResponse start(int userId, int projectId) {
    projectService.requireRole(userId, projectId);
    // Friendly pre-check: avoids even starting a second job for the common case (no race).
    // export_run_active_idx (V15) is the race-proof backstop below for the two-concurrent-requests
    // case this check alone can't catch.
    if (runs.findRunningByProject(projectId) != null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, EXPORT_IN_PROGRESS);
    }
    ExportRun run = new ExportRun();
    run.setProjectId(projectId);
    try {
      runs.insertRunning(run);
    } catch (DuplicateKeyException e) {
      // Lost the race against another concurrent start() for the same project: both passed the
      // pre-check above before either INSERT committed. export_run_active_idx (a partial unique
      // index on project_id WHERE status='RUNNING') rejects the second INSERT -- same friendly 409
      // as the pre-check, just reached via the DB-level backstop instead.
      throw new ResponseStatusException(HttpStatus.CONFLICT, EXPORT_IN_PROGRESS);
    }
    try {
      self.run(projectId, run.getId(), userId);
    } catch (TaskRejectedException e) {
      // The single-thread, bounded-queue (queueCapacity(50)) executor is full: self.run(...) throws
      // synchronously at this call site, before run()'s own try/catch (which maps failures to
      // runs.fail) ever gets a chance to run. Without this catch the just-inserted RUNNING row would
      // be stuck forever and the caller would see an unhandled 500 instead of a clean, retryable 503.
      runs.fail(run.getId(), "export service busy — try again later");
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
          "export service busy, try again later");
    }
    return ExportRunResponse.of(runs.findById(run.getId()));
  }

  // Poll target for the 202's id: any project member may check progress, 404 both for a
  // non-member and for a runId that exists but belongs to a different project -- never leaking
  // another project's run.
  public ExportRunResponse get(int userId, int projectId, long runId) {
    projectService.requireRole(userId, projectId);
    ExportRun run = requireRunInProject(projectId, runId);
    return ExportRunResponse.of(run);
  }

  // Latest-run view: any project member may read, returns null (-> 204 at the controller) rather
  // than 404 when the project has never had an export -- distinct from get's 404, which signals a
  // runId that doesn't belong to this project at all.
  public ExportRunResponse latest(int userId, int projectId) {
    projectService.requireRole(userId, projectId);
    ExportRun run = runs.findLatestByProject(projectId);
    return run == null ? null : ExportRunResponse.of(run);
  }

  // The download target for GET .../file: 404 unless the run belongs to this project, is DONE, and
  // its file still exists on disk (e.g. hasn't been swept by ExportRetentionSweep).
  public ExportFile fileFor(int userId, int projectId, long runId) {
    projectService.requireRole(userId, projectId);
    ExportRun run = requireRunInProject(projectId, runId);
    if (!"DONE".equals(run.getStatus()) || run.getFilePath() == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "export not ready");
    }
    Path path = Path.of(run.getFilePath());
    if (!Files.isRegularFile(path)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "export file not found");
    }
    return new ExportFile(path, run.getFileName());
  }

  private ExportRun requireRunInProject(int projectId, long runId) {
    ExportRun run = runs.findById(runId);
    if (run == null || !run.getProjectId().equals(projectId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "export run not found");
    }
    return run;
  }

  // The async body: runs on ExportAsyncConfig's single-thread pool, off the request thread that
  // called start(). Any exception (ColdpWriter.write itself failing, or a runs.finish write
  // failing) marks the whole row FAILED with the exception's message rather than leaving it stuck
  // RUNNING forever -- there is no caller left to propagate the exception to once we're here, same
  // contract as ColMatchJobService.run.
  @Async(ExportAsyncConfig.EXECUTOR_BEAN)
  public void run(int projectId, long runId, int userId) {
    Path targetPath = exportDir.resolve(runId + ".zip");
    try {
      ColdpWriter.Counts counts = writer.write(projectId, targetPath);
      String fileName = downloadFileName(projectId);
      runs.finish(runId, targetPath.toString(), fileName, Files.size(targetPath),
          counts.nameUsageCount(), counts.referenceCount());
    } catch (Exception e) {
      log.warn("export run {} failed for project {}: {}", runId, projectId, e.getMessage(), e);
      runs.fail(runId, e.getMessage());
      // Best-effort cleanup of whatever partial file ColdpWriter may have left behind -- a failed
      // run should not leave an orphaned zip on disk.
      try {
        Files.deleteIfExists(targetPath);
      } catch (IOException ignored) {
        // best-effort
      }
    }
  }

  private String downloadFileName(int projectId) {
    Project p = projects.findById(projectId);
    String alias = p != null && p.getAlias() != null && !p.getAlias().isBlank()
        ? p.getAlias()
        : String.valueOf(projectId);
    return alias + "-coldp.zip";
  }

  public record ExportFile(Path path, String fileName) {}
}
