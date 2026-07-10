package org.catalogueoflife.editor.col;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

// map-underscore-to-camel-case (application.yml) auto-maps every selected column onto ColMatchRun's
// properties, the same way Task/Change/Issue's simple selects do.
@Mapper
public interface ColMatchRunMapper {

  // Same insert-then-read-back-the-generated-id shape as ProjectMapper.insert/TaskMapper.insert:
  // the caller passes a ColMatchRun with just projectId set and reads run.getId() afterwards.
  @Insert("INSERT INTO col_match_run (project_id, status) VALUES (#{projectId}, 'RUNNING')")
  @Options(useGeneratedKeys = true, keyProperty = "id")
  void insertRunning(ColMatchRun run);

  @Update("UPDATE col_match_run SET total = #{total} WHERE id = #{runId}")
  int setTotal(@Param("runId") long runId, @Param("total") int total);

  // processed always increments; exactly one of verified/added/updated/unmatched increments too,
  // selected by outcome (ColOutcome.name()) -- ColMatchJobService.runSync's per-usage tally.
  @Update("""
      <script>
      UPDATE col_match_run
      SET processed = processed + 1,
      <choose>
        <when test="outcome == 'VERIFIED'">verified = verified + 1</when>
        <when test="outcome == 'ADDED'">added = added + 1</when>
        <when test="outcome == 'UPDATED'">updated = updated + 1</when>
        <otherwise>unmatched = unmatched + 1</otherwise>
      </choose>
      WHERE id = #{runId}
      </script>
      """)
  int tick(@Param("runId") long runId, @Param("outcome") String outcome);

  @Update("UPDATE col_match_run SET status = 'DONE', finished_at = now() WHERE id = #{runId}")
  int finish(@Param("runId") long runId);

  @Update("UPDATE col_match_run SET status = 'FAILED', error = #{error}, finished_at = now() WHERE id = #{runId}")
  int fail(@Param("runId") long runId, @Param("error") String error);

  @Select("SELECT * FROM col_match_run WHERE id = #{runId}")
  ColMatchRun findById(@Param("runId") long runId);

  // Latest-run view (ProjectMetadataPage on mount, via ColMatchJobService.latest): the most recent
  // run for the project regardless of status, or null if none has ever been started. started_at DESC
  // uses col_match_run_project_idx (project_id, started_at DESC); the ", id DESC" tiebreaker only
  // matters for two rows sharing a started_at timestamp (same-millisecond inserts), same tiebreak
  // convention as most-recent-first listings elsewhere in this app.
  @Select("SELECT * FROM col_match_run WHERE project_id = #{projectId} ORDER BY started_at DESC, id DESC LIMIT 1")
  ColMatchRun findLatestByProject(@Param("projectId") int projectId);

  // One-active-run-per-project guard (ColMatchJobService.start's pre-check; col_match_run_active_idx
  // is the race-proof DB-level backstop for the same invariant).
  @Select("SELECT * FROM col_match_run WHERE project_id = #{projectId} AND status = 'RUNNING' LIMIT 1")
  ColMatchRun findRunningByProject(@Param("projectId") int projectId);

  // Startup recovery sweep (ColMatchRunRecovery): the executor backing a run is in-memory and
  // per-instance (ColMatchAsyncConfig), so a RUNNING row can only be left behind by an instance
  // that no longer exists (a restart/redeploy killed it mid-run) -- there is nothing left to ever
  // finish or fail it otherwise. Bulk UPDATE with no id filter: every RUNNING row at startup is,
  // by definition, stale.
  @Update("UPDATE col_match_run SET status = 'FAILED', error = 'interrupted by restart', "
      + "finished_at = now() WHERE status = 'RUNNING'")
  int failStaleRunning();
}
