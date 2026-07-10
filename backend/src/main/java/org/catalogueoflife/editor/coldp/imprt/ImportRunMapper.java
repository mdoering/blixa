package org.catalogueoflife.editor.coldp.imprt;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

// map-underscore-to-camel-case (application.yml) auto-maps every selected column onto ImportRun's
// properties, the same way ExportRunMapper's simple selects do.
@Mapper
public interface ImportRunMapper {

  // Same insert-then-read-back-the-generated-id shape as ExportRunMapper.insertRunning: the
  // caller passes an ImportRun with userId/sourceName/preserveIds/idScope set and reads
  // run.getId() afterwards. project_id is left null -- the import job sets it once the project it
  // creates exists (see setProject).
  @Insert("INSERT INTO import_run (user_id, source_name, preserve_ids, id_scope, status) "
      + "VALUES (#{userId}, #{sourceName}, #{preserveIds}, #{idScope}, 'RUNNING')")
  @Options(useGeneratedKeys = true, keyProperty = "id")
  void insertRunning(ImportRun run);

  @Update("UPDATE import_run SET project_id = #{projectId} WHERE id = #{runId}")
  int setProject(@Param("runId") long runId, @Param("projectId") long projectId);

  @Update("""
      UPDATE import_run
      SET status = 'DONE', name_usage_count = #{nameUsageCount}, reference_count = #{referenceCount},
          author_count = #{authorCount}, issues = #{issues,jdbcType=OTHER}::jsonb, finished_at = now()
      WHERE id = #{runId}
      """)
  int finish(@Param("runId") long runId, @Param("nameUsageCount") int nameUsageCount,
      @Param("referenceCount") int referenceCount, @Param("authorCount") int authorCount,
      @Param("issues") String issues);

  @Update("UPDATE import_run SET status = 'FAILED', error = #{error}, finished_at = now() WHERE id = #{runId}")
  int fail(@Param("runId") long runId, @Param("error") String error);

  @Select("SELECT * FROM import_run WHERE id = #{runId}")
  ImportRun findById(@Param("runId") long runId);

  // Latest-run view (an eventual GET .../import/latest, mirroring ExportRunMapper.findLatestByProject):
  // the most recent run started by this user regardless of status, or null if none has ever been
  // started. started_at DESC uses import_run_user_idx (user_id, started_at DESC); the ", id DESC"
  // tiebreaker only matters for two rows sharing a started_at timestamp (same-millisecond inserts).
  @Select("SELECT * FROM import_run WHERE user_id = #{userId} ORDER BY started_at DESC, id DESC LIMIT 1")
  ImportRun findLatestByUser(@Param("userId") int userId);

  // Startup recovery sweep (ImportRunRecovery): the executor backing a run is in-memory and
  // per-instance (ImportAsyncConfig), so a RUNNING row can only be left behind by an instance that
  // no longer exists (a restart/redeploy killed it mid-run). Bulk UPDATE with no id filter: every
  // RUNNING row at startup is, by definition, stale.
  @Update("UPDATE import_run SET status = 'FAILED', error = 'interrupted by restart', "
      + "finished_at = now() WHERE status = 'RUNNING'")
  int failStaleRunning();
}
