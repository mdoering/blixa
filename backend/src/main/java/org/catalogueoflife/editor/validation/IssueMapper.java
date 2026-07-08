package org.catalogueoflife.editor.validation;

import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

// map-underscore-to-camel-case (application.yml) auto-maps every selected column onto Issue's
// properties, the same way Task/Change/Project's simple selects do. `context` is JSONB (see
// V7__issue.sql) and is bound/read as raw JSON text via the same #{param}::jsonb cast used by
// Change.diff/Project.metadata (see ChangeMapper/ProjectMapper). Read queries beyond findByEntity
// (list/filter/summary) are added in Task 2's IssueService/IssueController.
@Mapper
public interface IssueMapper {

  @Insert("""
      INSERT INTO issue (project_id, entity_type, entity_id, rule, severity, message, context)
      VALUES (#{projectId}, #{entityType}, #{entityId}, #{rule}, #{severity}, #{message}, #{context}::jsonb)
      """)
  @Options(useGeneratedKeys = true, keyProperty = "id")
  void insert(Issue issue);

  // Every issue for one entity, regardless of status -- ValidationService.revalidateUsage
  // reconciles against ALL of them (even a DONE/REJECTED one can transition again).
  @Select("""
      SELECT * FROM issue
      WHERE project_id = #{projectId} AND entity_type = #{entityType} AND entity_id = #{entityId}
      """)
  List<Issue> findByEntity(@Param("projectId") int projectId, @Param("entityType") String entityType,
      @Param("entityId") int entityId);

  // Refreshes a still-firing rule's finding WITHOUT touching status/reviewer: an OPEN issue stays
  // OPEN, and an ACCEPTED/REJECTED reviewer decision survives the recompute (Global Constraint:
  // "reviewer decisions survive recompute").
  @Update("""
      UPDATE issue
      SET severity = #{severity}, message = #{message}, context = #{context}::jsonb, updated_at = now()
      WHERE id = #{id}
      """)
  int updateFinding(@Param("id") int id, @Param("severity") String severity,
      @Param("message") String message, @Param("context") String context);

  // A DONE issue (previously ACCEPTED, then cleared) whose rule fires again is a regression: back
  // to OPEN, clearing the stale reviewer/reviewedAt from the old, now-superseded review.
  @Update("""
      UPDATE issue
      SET severity = #{severity}, message = #{message}, context = #{context}::jsonb,
          status = 'OPEN', reviewer_id = NULL, reviewed_at = NULL, updated_at = now()
      WHERE id = #{id}
      """)
  int reopen(@Param("id") int id, @Param("severity") String severity,
      @Param("message") String message, @Param("context") String context);

  // An ACCEPTED issue whose finding cleared: the work is done, but the row is kept (not deleted)
  // as a completed-work record.
  @Update("UPDATE issue SET status = 'DONE', updated_at = now() WHERE id = #{id}")
  int markDone(@Param("id") int id);

  // An OPEN or REJECTED issue whose finding cleared simply disappears.
  @Delete("DELETE FROM issue WHERE id = #{id}")
  int deleteById(@Param("id") int id);

  // Reviewer lifecycle transition (accept/reject/reopen, action -> status mapping owned by Task 2's
  // IssueService). Already needed here so ValidationReconcileIT can simulate "a reviewer accepted
  // this issue" directly, ahead of Task 2's review endpoint existing.
  @Update("""
      UPDATE issue
      SET status = #{status}, reviewer_id = #{reviewerId}, reviewed_at = now(), updated_at = now()
      WHERE id = #{id}
      """)
  int review(@Param("id") int id, @Param("status") String status, @Param("reviewerId") Integer reviewerId);
}
