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
}
