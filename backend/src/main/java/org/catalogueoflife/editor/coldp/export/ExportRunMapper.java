package org.catalogueoflife.editor.coldp.export;

import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

// map-underscore-to-camel-case (application.yml) auto-maps every selected column onto ExportRun's
// properties, the same way ColMatchRunMapper's simple selects do.
@Mapper
public interface ExportRunMapper {

  // Same insert-then-read-back-the-generated-id shape as ColMatchRunMapper.insertRunning: the
  // caller passes an ExportRun with just projectId set and reads run.getId() afterwards.
  @Insert("INSERT INTO export_run (project_id, status) VALUES (#{projectId}, 'RUNNING')")
  @Options(useGeneratedKeys = true, keyProperty = "id")
  void insertRunning(ExportRun run);

  @Update("""
      UPDATE export_run
      SET status = 'DONE', file_path = #{filePath}, file_name = #{fileName}, file_size = #{fileSize},
          name_usage_count = #{nameUsageCount}, reference_count = #{referenceCount}, finished_at = now()
      WHERE id = #{runId}
      """)
  int finish(@Param("runId") long runId, @Param("filePath") String filePath,
      @Param("fileName") String fileName, @Param("fileSize") long fileSize,
      @Param("nameUsageCount") int nameUsageCount, @Param("referenceCount") int referenceCount);

  @Update("UPDATE export_run SET status = 'FAILED', error = #{error}, finished_at = now() WHERE id = #{runId}")
  int fail(@Param("runId") long runId, @Param("error") String error);

  @Select("SELECT * FROM export_run WHERE id = #{runId}")
  ExportRun findById(@Param("runId") long runId);

  // Latest-run view (GET .../export/latest via ExportRunService.latest): the most recent run for
  // the project regardless of status, or null if none has ever been started. started_at DESC uses
  // export_run_project_idx (project_id, started_at DESC); the ", id DESC" tiebreaker only matters
  // for two rows sharing a started_at timestamp (same-millisecond inserts).
  @Select("SELECT * FROM export_run WHERE project_id = #{projectId} ORDER BY started_at DESC, id DESC LIMIT 1")
  ExportRun findLatestByProject(@Param("projectId") int projectId);

  // One-active-run-per-project guard (ExportRunService.start's pre-check; export_run_active_idx is
  // the race-proof DB-level backstop for the same invariant).
  @Select("SELECT * FROM export_run WHERE project_id = #{projectId} AND status = 'RUNNING' LIMIT 1")
  ExportRun findRunningByProject(@Param("projectId") int projectId);

  // Startup recovery sweep (ExportRunRecovery): the executor backing a run is in-memory and
  // per-instance (ExportAsyncConfig), so a RUNNING row can only be left behind by an instance that
  // no longer exists (a restart/redeploy killed it mid-run). Bulk UPDATE with no id filter: every
  // RUNNING row at startup is, by definition, stale.
  @Update("UPDATE export_run SET status = 'FAILED', error = 'interrupted by restart', "
      + "finished_at = now() WHERE status = 'RUNNING'")
  int failStaleRunning();

  // ExportRetentionSweep's candidates: terminal (DONE/FAILED) rows whose finished_at is older than
  // coldp.export.ttl. Scoped to terminal rows only -- a RUNNING row (which shouldn't exist this
  // old thanks to failStaleRunning, but just in case) is never swept out from under a job.
  @Select("SELECT * FROM export_run WHERE status IN ('DONE', 'FAILED') AND finished_at < #{cutoff}")
  List<ExportRun> findTerminalFinishedBefore(@Param("cutoff") OffsetDateTime cutoff);

  @Delete("DELETE FROM export_run WHERE id = #{runId}")
  int deleteById(@Param("runId") long runId);
}
