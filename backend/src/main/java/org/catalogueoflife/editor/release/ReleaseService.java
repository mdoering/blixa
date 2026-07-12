package org.catalogueoflife.editor.release;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.catalogueoflife.editor.coldp.export.ColdpWriter;
import org.catalogueoflife.editor.project.Project;
import org.catalogueoflife.editor.project.ProjectMapper;
import org.catalogueoflife.editor.project.ProjectService;
import org.catalogueoflife.editor.project.Role;
import org.catalogueoflife.editor.release.dto.ReleaseResponse;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

// The project release job: publish authorizes the owner, inserts the BUILDING release row on the
// request thread, then fires build() -- @Async off ReleaseAsyncConfig's dedicated single-thread pool
// -- so the request returns 202 immediately while the (possibly long) ColDP snapshot proceeds in the
// background. Mirrors ExportRunService's start/run shape, but publish is owner-only (a release is a
// published, versioned artifact, not a read like export) and the job body persists a Release row
// instead of an ExportRun.
@Service
public class ReleaseService {

  private static final Logger log = LoggerFactory.getLogger(ReleaseService.class);

  private final ReleaseMapper releases;
  private final ProjectService projectService;
  private final ProjectMapper projects;
  private final ColdpWriter writer;
  private final Path releaseDir;

  // Self-reference through the Spring proxy so build()'s @Async actually goes through the proxied
  // annotation -- see ExportRunService's identical `self` field for the same reason: a plain
  // `this.build(...)` call bypasses the proxy entirely. @Lazy avoids a circular bean-creation error.
  @Autowired @Lazy private ReleaseService self;

  public ReleaseService(ReleaseMapper releases, ProjectService projectService, ProjectMapper projects,
      ColdpWriter writer, @Value("${coldp.release.dir:${java.io.tmpdir}/coldp-releases}") String releaseDir) {
    this.releases = releases;
    this.projectService = projectService;
    this.projects = projects;
    this.writer = writer;
    this.releaseDir = Path.of(releaseDir);
    try {
      Files.createDirectories(this.releaseDir);
    } catch (IOException e) {
      throw new java.io.UncheckedIOException("failed to create release dir " + releaseDir, e);
    }
  }

  public ReleaseResponse publish(int userId, int projectId, String version, String notes) {
    requireOwner(userId, projectId);
    if (version == null || version.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "version is required");
    }
    Release r = new Release();
    r.setProjectId(projectId);
    r.setVersion(version.trim());
    r.setNotes(notes);
    r.setCreatedBy(userId);
    releases.insertBuilding(r);
    try {
      self.build(projectId, r.getId());
    } catch (TaskRejectedException e) {
      releases.fail(r.getId(), "release service busy — try again later");
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "release service busy, try again later");
    }
    return ReleaseResponse.of(releases.findById(r.getId()));
  }

  @Async(ReleaseAsyncConfig.EXECUTOR_BEAN)
  public void build(int projectId, int releaseId) {
    Path target = releaseDir.resolve(releaseId + ".zip");
    try {
      ColdpWriter.Counts counts = writer.write(projectId, target);
      String metrics = "{}"; // Task 3 replaces this with the rich snapshot
      int updated = releases.ready(releaseId, counts.nameUsageCount(), metrics, target.toString(),
          downloadFileName(projectId, releaseId), Files.size(target));
      if (updated == 0) {
        // The release row was deleted (or left BUILDING) mid-build -> the zip we just wrote has no
        // DB reference and no retention sweep, so remove it rather than leak it.
        Files.deleteIfExists(target);
      }
    } catch (Exception e) {
      log.warn("release build {} failed for project {}: {}", releaseId, projectId, e.getMessage(), e);
      releases.fail(releaseId, e.getMessage());
      try { Files.deleteIfExists(target); } catch (IOException ignored) { }
    }
  }

  public List<ReleaseResponse> list(int userId, int projectId) {
    projectService.requireRole(userId, projectId); // any member may view the history
    return releases.findByProject(projectId).stream().map(ReleaseResponse::of).toList();
  }

  @Transactional
  public void delete(int userId, int projectId, int releaseId) {
    requireOwner(userId, projectId);
    Release r = releases.findById(releaseId);
    if (r == null || !r.getProjectId().equals(projectId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "release not found");
    }
    releases.delete(releaseId);
    // delete the file only after the row-delete transaction commits
    String path = r.getFilePath();
    if (path != null) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override public void afterCommit() {
          try { Files.deleteIfExists(Path.of(path)); } catch (IOException ignored) { }
        }
      });
    }
  }

  private void requireOwner(int userId, int projectId) {
    if (!Role.OWNER.dbValue().equals(projectService.requireRole(userId, projectId))) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "owner required");
    }
  }

  private String downloadFileName(int projectId, int releaseId) {
    Project p = projects.findById(projectId);
    String alias = p != null && p.getAlias() != null && !p.getAlias().isBlank()
        ? p.getAlias() : String.valueOf(projectId);
    Release r = releases.findById(releaseId);
    String v = r != null && r.getVersion() != null ? r.getVersion().replaceAll("[^A-Za-z0-9._-]", "_") : "release";
    return alias + "-" + v + "-coldp.zip";
  }
}
