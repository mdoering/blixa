package org.catalogueoflife.editor.merge;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

// map-underscore-to-camel-case (application.yml) auto-maps every selected column onto MergeRun's
// properties, the same way ImportRunMapper's simple selects do.
@Mapper
public interface MergeRunMapper {

  // Same insert-then-read-back-the-generated-id shape as ImportRunMapper.insertRunning: the caller
  // passes a MergeRun with userId/sourceProjectId/targetProjectId set and reads run.getId()
  // afterwards. mode/transactional/plan/metrics/issues/planned_at/finished_at/error all stay their
  // column defaults (NULL) until setPlanned/startApply/finish/fail move the run through its phases.
  @Insert("INSERT INTO merge_run (user_id, source_project_id, target_project_id, status) "
      + "VALUES (#{userId}, #{sourceProjectId}, #{targetProjectId}, 'RUNNING')")
  @Options(useGeneratedKeys = true, keyProperty = "id")
  void insertRunning(MergeRun run);

  // Compute-plan finishes: the plan + its impact metrics are stored together and the run moves
  // RUNNING -> PLANNED, ready for the curator to review/override before apply.
  @Update("""
      UPDATE merge_run
      SET status = 'PLANNED', plan = #{plan,jdbcType=OTHER}::jsonb,
          metrics = #{metrics,jdbcType=OTHER}::jsonb, planned_at = now()
      WHERE id = #{runId}
      """)
  int setPlanned(@Param("runId") long runId, @Param("plan") String plan, @Param("metrics") String metrics);

  // A narrow plan-only replace: status/planned_at/metrics are left as-is. MergeService.applyOverrides
  // (Task 5) does NOT use this -- an override always changes the impact metrics too, so it reuses
  // setPlanned to store the mutated plan and the recomputed metrics together in one UPDATE (a no-op
  // on status/planned_at there, since the run is already PLANNED when overrides are legal). This
  // method is kept for a plan-only write with no metrics change and is exercised directly by
  // MergeRunMapperIT.
  @Update("UPDATE merge_run SET plan = #{plan,jdbcType=OTHER}::jsonb WHERE id = #{runId}")
  int updatePlan(@Param("runId") long runId, @Param("plan") String plan);

  // Apply starts: the curator's chosen mode/transactional flag is recorded and the run moves
  // PLANNED -> APPLYING.
  @Update("UPDATE merge_run SET status = 'APPLYING', mode = #{mode}, transactional = #{transactional} "
      + "WHERE id = #{runId}")
  int startApply(@Param("runId") long runId, @Param("mode") String mode,
      @Param("transactional") boolean transactional);

  @Update("""
      UPDATE merge_run
      SET status = 'DONE', issues = #{issues,jdbcType=OTHER}::jsonb, finished_at = now()
      WHERE id = #{runId}
      """)
  int finish(@Param("runId") long runId, @Param("issues") String issues);

  // Belt-and-braces guard: AND status <> 'DONE' means a stray post-DONE call (e.g. a post-commit
  // revalidateProject failure after apply's finish already ran -- the import final-review lesson,
  // see ImportRunMapper.fail) can never clobber an already-finished row. Deliberately not narrowed
  // to a single pre-terminal status (unlike ImportRunMapper's AND status = 'RUNNING'): merge_run has
  // two distinct in-flight phases (RUNNING compute-plan, APPLYING apply) that both legitimately fail,
  // so the guard is "anything but DONE" rather than one specific status.
  @Update("UPDATE merge_run SET status = 'FAILED', error = #{error}, finished_at = now() "
      + "WHERE id = #{runId} AND status <> 'DONE'")
  int fail(@Param("runId") long runId, @Param("error") String error);

  @Select("SELECT * FROM merge_run WHERE id = #{runId}")
  MergeRun findById(@Param("runId") long runId);

  // Latest-run view (GET .../merge/latest): the most recent run for the target project regardless
  // of status, or null if none has ever been started. started_at DESC uses merge_run_target_idx
  // (target_project_id, started_at DESC); the ", id DESC" tiebreaker only matters for two rows
  // sharing a started_at timestamp (same-millisecond inserts).
  @Select("SELECT * FROM merge_run WHERE target_project_id = #{targetProjectId} "
      + "ORDER BY started_at DESC, id DESC LIMIT 1")
  MergeRun findLatestByTarget(@Param("targetProjectId") int targetProjectId);

  // One-active-run-per-target guard (MergeService.start's pre-check; merge_run_active_idx is the
  // race-proof DB-level backstop for the same invariant -- see its comment in V19__merge_run.sql for
  // why RUNNING/APPLYING but not PLANNED holds the lock).
  @Select("SELECT * FROM merge_run WHERE target_project_id = #{targetProjectId} "
      + "AND status IN ('RUNNING','APPLYING') LIMIT 1")
  MergeRun findActiveByTarget(@Param("targetProjectId") int targetProjectId);

  // Startup recovery sweep (MergeRunRecovery): the executor backing a run is in-memory and
  // per-instance (MergeAsyncConfig), so a RUNNING or APPLYING row can only be left behind by an
  // instance that no longer exists (a restart/redeploy killed it mid-run) -- there is nothing left
  // to ever finish or fail it otherwise. Bulk UPDATE with no id filter: every RUNNING/APPLYING row
  // at startup is, by definition, stale. Unlike ImportRunMapper.failStaleRunning (RUNNING only),
  // this also sweeps APPLYING -- merge_run's second in-flight phase.
  @Update("UPDATE merge_run SET status = 'FAILED', error = 'interrupted by restart', "
      + "finished_at = now() WHERE status IN ('RUNNING','APPLYING')")
  int failStaleRunning();
}
