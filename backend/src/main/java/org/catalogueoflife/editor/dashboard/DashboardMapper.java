package org.catalogueoflife.editor.dashboard;

import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

// Cross-project aggregation queries for the personal dashboard (GET /api/me/dashboard). Every
// project-scoped query takes the caller's membership project-id list; the service short-circuits an
// empty membership so the IN (...) lists are never empty.
@Mapper
public interface DashboardMapper {

  // (projectId, key, count) row -- `key` is a per-query grouping label (a status name, or a literal).
  record ProjectCount(int projectId, String key, long count) {}

  // Usage counts grouped by status, per project (headline accepted/synonym numbers).
  @Select("""
      <script>
      SELECT project_id AS projectId, status AS key, COUNT(*) AS count
      FROM name_usage
      WHERE project_id IN <foreach item='id' collection='pids' open='(' separator=',' close=')'>#{id}</foreach>
      GROUP BY project_id, status
      </script>
      """)
  List<ProjectCount> usageStatusCounts(@Param("pids") List<Integer> pids);

  // Total OPEN issues per project (any severity) -- the project card's "open issues" number.
  @Select("""
      <script>
      SELECT project_id AS projectId, 'open' AS key, COUNT(*) AS count
      FROM issue
      WHERE status = 'OPEN'
        AND project_id IN <foreach item='id' collection='pids' open='(' separator=',' close=')'>#{id}</foreach>
      GROUP BY project_id
      </script>
      """)
  List<ProjectCount> openIssueCounts(@Param("pids") List<Integer> pids);

  // OPEN ERROR-severity issues per project -- the inbox "open errors" card.
  @Select("""
      <script>
      SELECT project_id AS projectId, 'error' AS key, COUNT(*) AS count
      FROM issue
      WHERE status = 'OPEN' AND severity = 'ERROR'
        AND project_id IN <foreach item='id' collection='pids' open='(' separator=',' close=')'>#{id}</foreach>
      GROUP BY project_id
      </script>
      """)
  List<ProjectCount> openErrorCounts(@Param("pids") List<Integer> pids);

  // REVIEW-state discussions per project -- external submissions awaiting triage.
  @Select("""
      <script>
      SELECT project_id AS projectId, 'review' AS key, COUNT(*) AS count
      FROM discussion
      WHERE status = 'REVIEW'
        AND project_id IN <foreach item='id' collection='pids' open='(' separator=',' close=')'>#{id}</foreach>
      GROUP BY project_id
      </script>
      """)
  List<ProjectCount> reviewCounts(@Param("pids") List<Integer> pids);

  @Select("SELECT COUNT(*) FROM app_user WHERE state = 'PENDING'")
  int countPendingUsers();

  // A name_usage the caller still holds an unexpired lock on (across all projects).
  record LockRow(int projectId, int usageId, String scientificName, OffsetDateTime acquiredAt) {}

  @Select("""
      SELECT l.project_id AS projectId, l.entity_id AS usageId, n.scientific_name AS scientificName,
             l.acquired_at AS acquiredAt
      FROM lock l
      JOIN name_usage n ON n.project_id = l.project_id AND n.id = l.entity_id
      WHERE l.user_id = #{userId} AND l.entity_type = 'name_usage' AND l.expires_at > now()
      ORDER BY l.acquired_at DESC
      """)
  List<LockRow> myLocks(@Param("userId") int userId);

  // A name_usage the caller most recently edited (latest change per usage), newest first.
  record RecentTaxon(int projectId, int usageId, String scientificName, OffsetDateTime editedAt) {}

  @Select("""
      <script>
      SELECT c.project_id AS projectId, c.entity_id AS usageId, n.scientific_name AS scientificName,
             MAX(c.at) AS editedAt
      FROM change c
      JOIN name_usage n ON n.project_id = c.project_id AND n.id = c.entity_id
      WHERE c.user_id = #{userId} AND c.entity_type = 'name_usage'
        AND c.project_id IN <foreach item='id' collection='pids' open='(' separator=',' close=')'>#{id}</foreach>
      GROUP BY c.project_id, c.entity_id, n.scientific_name
      ORDER BY editedAt DESC
      LIMIT #{limit}
      </script>
      """)
  List<RecentTaxon> recentTaxa(@Param("userId") int userId, @Param("pids") List<Integer> pids,
      @Param("limit") int limit);

  // A discussion comment (by someone else) on a thread the caller cares about, since their marker.
  record PingRow(int projectId, int discussionId, String title, String snippet,
      OffsetDateTime createdAt) {}

  // The pings WHERE-clause, shared by count + list: comments by others on a discussion the caller
  // authored, follows, or is @-mentioned in (text match -- no mention table exists). `since` null =>
  // never visited => all comments count.
  String PING_WHERE = """
        c.author_id != #{userId}
        AND c.created_at > COALESCE(#{since}, TIMESTAMPTZ 'epoch')
        AND c.project_id IN <foreach item='id' collection='pids' open='(' separator=',' close=')'>#{id}</foreach>
        AND (
          d.author_id = #{userId}
          OR EXISTS (SELECT 1 FROM discussion_follow f
                     WHERE f.project_id = c.project_id AND f.discussion_id = c.discussion_id
                       AND f.user_id = #{userId})
          OR c.body ILIKE '%@' || #{username} || '%'
          OR c.body ILIKE '%@' || #{orcid} || '%'
        )
      """;

  @Select("""
      <script>
      SELECT COUNT(*)
      FROM discussion_comment c
      JOIN discussion d ON d.project_id = c.project_id AND d.id = c.discussion_id
      WHERE
      """ + PING_WHERE + """
      </script>
      """)
  long pingCount(@Param("userId") int userId, @Param("orcid") String orcid,
      @Param("username") String username, @Param("pids") List<Integer> pids,
      @Param("since") OffsetDateTime since);

  @Select("""
      <script>
      SELECT c.project_id AS projectId, c.discussion_id AS discussionId, d.title AS title,
             left(c.body, 140) AS snippet, c.created_at AS createdAt
      FROM discussion_comment c
      JOIN discussion d ON d.project_id = c.project_id AND d.id = c.discussion_id
      WHERE
      """ + PING_WHERE + """
      ORDER BY c.created_at DESC
      LIMIT #{limit}
      </script>
      """)
  List<PingRow> pings(@Param("userId") int userId, @Param("orcid") String orcid,
      @Param("username") String username, @Param("pids") List<Integer> pids,
      @Param("since") OffsetDateTime since, @Param("limit") int limit);
}
