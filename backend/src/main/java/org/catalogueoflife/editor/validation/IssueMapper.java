package org.catalogueoflife.editor.validation;

import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.catalogueoflife.editor.validation.dto.IssueResponse;

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

  // -- Task 2: read/summary queries for the issue API (list/filter, review lifecycle, on-demand
  // revalidate). All below LOWER() severity/status: the API speaks lowercase (Role.dbValue()/
  // TaskStatus.apiValue()'s convention), the column stores the enum's upper-case name().

  // Every issue for a project, optionally narrowed by status/severity/entityType (each null ->
  // unfiltered, per IssueService's normalize-or-null filter parsing); joins app_user for the
  // reviewer's username (null until reviewed). Ordered by severity criticality (ERROR first) then
  // newest-first -- a reviewer's queue surfaces the worst problems first.
  @Select("""
      <script>
      SELECT i.id AS id, i.entity_type AS entityType, i.entity_id AS entityId, i.rule AS rule,
             LOWER(i.severity) AS severity, i.message AS message, i.context AS context,
             LOWER(i.status) AS status, i.created_at AS createdAt, i.updated_at AS updatedAt,
             i.reviewer_id AS reviewerId, u.username AS reviewerUsername, i.reviewed_at AS reviewedAt
      FROM issue i
      LEFT JOIN app_user u ON u.id = i.reviewer_id
      WHERE i.project_id = #{projectId}
      <if test="status != null">AND i.status = #{status}</if>
      <if test="severity != null">AND i.severity = #{severity}</if>
      <if test="entityType != null">AND i.entity_type = #{entityType}</if>
      ORDER BY CASE i.severity WHEN 'ERROR' THEN 0 WHEN 'WARNING' THEN 1 WHEN 'INFO' THEN 2 ELSE 3 END,
               i.created_at DESC
      LIMIT #{limit} OFFSET #{offset}
      </script>
      """)
  List<IssueResponse> findByProject(@Param("projectId") int projectId, @Param("status") String status,
      @Param("severity") String severity, @Param("entityType") String entityType,
      @Param("limit") int limit, @Param("offset") int offset);

  // Single-issue read scoped to the project -- returns null both when the id doesn't exist and
  // when it belongs to a different project, so IssueService can turn either case into a 404
  // without leaking cross-project existence.
  @Select("""
      SELECT i.id AS id, i.entity_type AS entityType, i.entity_id AS entityId, i.rule AS rule,
             LOWER(i.severity) AS severity, i.message AS message, i.context AS context,
             LOWER(i.status) AS status, i.created_at AS createdAt, i.updated_at AS updatedAt,
             i.reviewer_id AS reviewerId, u.username AS reviewerUsername, i.reviewed_at AS reviewedAt
      FROM issue i
      LEFT JOIN app_user u ON u.id = i.reviewer_id
      WHERE i.project_id = #{projectId} AND i.id = #{id}
      """)
  IssueResponse findById(@Param("projectId") int projectId, @Param("id") int id);

  // Counts grouped by (status, severity) for the project's problems-view summary; IssueService
  // sums these along each axis into byStatus/bySeverity (see IssueSummaryResponse). Uppercase
  // (not LOWER()'d) since IssueService does its own lowercasing while building the maps.
  @Select("""
      SELECT status AS status, severity AS severity, COUNT(*) AS count
      FROM issue
      WHERE project_id = #{projectId}
      GROUP BY status, severity
      """)
  List<StatusSeverityCount> countByStatusSeverity(@Param("projectId") int projectId);

  // Internal aggregation row for countByStatusSeverity -- not exposed via the API directly (see
  // IssueSummaryResponse for the shape the API returns).
  record StatusSeverityCount(String status, String severity, long count) {}
}
