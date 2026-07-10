package org.catalogueoflife.editor.coldp.export;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.catalogueoflife.editor.coldp.io.ColdpMetadata;
import org.catalogueoflife.editor.coldp.io.ColdpMetadata.ColdpMetadataDto;
import org.catalogueoflife.editor.coldp.io.ColdpZip;
import org.catalogueoflife.editor.project.Licenses;
import org.catalogueoflife.editor.project.Project;
import org.catalogueoflife.editor.project.ProjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

// The ColDP entity-writing entry point for an export (called off-request by ExportRunService.run,
// on ExportAsyncConfig.EXECUTOR_BEAN): builds a flat ColDP folder in a fresh temp dir, zips it to
// targetZip (ColdpZip.zipFolder), and cleans the temp dir up. Writes metadata.yaml (the project's
// own fields, via ColdpMetadata.write) and the combined NameUsage.tsv (NameUsageColdpWriter) --
// later tasks add reference.tsv etc to this same method's temp-dir population step, without
// touching the zip/cleanup shape or its (int projectId, Path targetZip) signature.
@Component
public class ColdpWriter {

  private final ProjectMapper projects;
  private final NameUsageColdpWriter nameUsageWriter;

  public ColdpWriter(ProjectMapper projects, NameUsageColdpWriter nameUsageWriter) {
    this.projects = projects;
    this.nameUsageWriter = nameUsageWriter;
  }

  // Per-entity tallies for the export_run row's *_count columns. referenceCount is still 0 -- no
  // reference.tsv file is written yet; a later task populates it the same way nameUsageCount is
  // populated here.
  public record Counts(int nameUsageCount, int referenceCount) {
    public static final Counts ZERO = new Counts(0, 0);
  }

  public Counts write(int projectId, Path targetZip) throws IOException {
    Project project = projects.findById(projectId);
    if (project == null) {
      // Shouldn't happen in practice: ExportRunService.start already resolved projectId via
      // requireRole before this runs, so the project existed at request time. Guards against the
      // narrow race of a project being deleted while its export is queued/running.
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "project not found");
    }
    Path tempDir = Files.createTempDirectory("coldp-export-" + projectId + "-");
    int nameUsageCount;
    try {
      ColdpMetadata.write(tempDir, toMetadataDto(project));
      nameUsageCount = nameUsageWriter.write(tempDir, projectId, project.getNomCode());
      ColdpZip.zipFolder(tempDir, targetZip);
    } finally {
      deleteRecursively(tempDir);
    }
    return new Counts(nameUsageCount, 0);
  }

  private static ColdpMetadataDto toMetadataDto(Project p) {
    return new ColdpMetadataDto(
        p.getTitle(),
        p.getAlias(),
        p.getDescription(),
        Licenses.toWire(p.getLicense()),
        p.getGeographicScope(),
        p.getTaxonomicScope());
  }

  // Best-effort recursive delete of the working temp dir: a leftover temp dir on cleanup failure
  // (e.g. a file locked by another process) must never fail the export itself -- the zip has
  // already been written by the time this runs.
  private static void deleteRecursively(Path dir) {
    try (var walk = Files.walk(dir)) {
      walk.sorted(Comparator.reverseOrder()).forEach(p -> {
        try {
          Files.deleteIfExists(p);
        } catch (IOException ignored) {
          // best-effort
        }
      });
    } catch (IOException ignored) {
      // best-effort
    }
  }
}
