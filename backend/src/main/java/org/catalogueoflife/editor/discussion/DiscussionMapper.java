package org.catalogueoflife.editor.discussion;

import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

// map-underscore-to-camel-case (application.yml) auto-maps every selected column onto Discussion's
// fields; the LEFT JOIN's COALESCE(display_name, username) AS author_name fills the derived
// authorName. status/visibility/order values are validated + normalized in DiscussionService before
// reaching the ${order} literal substitution below (never user-supplied verbatim).
@Mapper
public interface DiscussionMapper {

  @Insert("""
      INSERT INTO discussion (project_id, id, title, body, status, visibility, author_id, author_orcid, created_via)
      VALUES (#{projectId}, #{id}, #{title}, #{body}, #{status}, #{visibility}, #{authorId}, #{authorOrcid}, #{createdVia})
      """)
  void insert(Discussion d);

  @Select("""
      SELECT d.*, COALESCE(u.display_name, u.username) AS author_name
      FROM discussion d LEFT JOIN app_user u ON u.id = d.author_id
      WHERE d.project_id = #{projectId} AND d.id = #{id}
      """)
  Discussion findByIdInProject(@Param("projectId") int projectId, @Param("id") int id);

  // Public (unauthenticated) reads: only PUBLIC discussions are ever returned.
  @Select("""
      SELECT d.*, COALESCE(u.display_name, u.username) AS author_name
      FROM discussion d LEFT JOIN app_user u ON u.id = d.author_id
      WHERE d.project_id = #{projectId} AND d.visibility = 'PUBLIC'
      ORDER BY d.updated_at DESC, d.id DESC
      """)
  List<Discussion> findPublicByProject(@Param("projectId") int projectId);

  @Select("""
      SELECT d.*, COALESCE(u.display_name, u.username) AS author_name
      FROM discussion d LEFT JOIN app_user u ON u.id = d.author_id
      WHERE d.project_id = #{projectId} AND d.id = #{id} AND d.visibility = 'PUBLIC'
      """)
  Discussion findPublicByIdInProject(@Param("projectId") int projectId, @Param("id") int id);

  // Paged listing with optional full-text search (q, over title+body), status filter, author filter,
  // and created|modified sort. q-present orders by ts_rank; otherwise by the chosen timestamp.
  @Select("""
      <script>
      SELECT d.*, COALESCE(u.display_name, u.username) AS author_name
      FROM discussion d LEFT JOIN app_user u ON u.id = d.author_id
      WHERE d.project_id = #{projectId}
      <if test="q != null and q != ''">
        AND to_tsvector('simple', coalesce(d.title,'') || ' ' || coalesce(d.body,'')) @@ websearch_to_tsquery('simple', #{q})
      </if>
      <if test="status != null">
        AND d.status = #{status}
      </if>
      <if test="authorId != null">
        AND d.author_id = #{authorId}
      </if>
      <choose>
        <when test="q != null and q != ''">
          ORDER BY ts_rank(to_tsvector('simple', coalesce(d.title,'') || ' ' || coalesce(d.body,'')),
                           websearch_to_tsquery('simple', #{q})) DESC, d.id
        </when>
        <when test="sort == 'modified'">
          ORDER BY d.updated_at ${order}, d.id
        </when>
        <otherwise>
          ORDER BY d.created_at ${order}, d.id
        </otherwise>
      </choose>
      LIMIT #{limit} OFFSET #{offset}
      </script>
      """)
  List<Discussion> search(@Param("projectId") int projectId, @Param("q") String q,
      @Param("status") String status, @Param("authorId") Integer authorId,
      @Param("sort") String sort, @Param("order") String order,
      @Param("limit") int limit, @Param("offset") int offset);

  // Count of ALL matches for the same filters (ignoring paging) -- drives DiscussionPage.total.
  @Select("""
      <script>
      SELECT count(*) FROM discussion d
      WHERE d.project_id = #{projectId}
      <if test="q != null and q != ''">
        AND to_tsvector('simple', coalesce(d.title,'') || ' ' || coalesce(d.body,'')) @@ websearch_to_tsquery('simple', #{q})
      </if>
      <if test="status != null">
        AND d.status = #{status}
      </if>
      <if test="authorId != null">
        AND d.author_id = #{authorId}
      </if>
      </script>
      """)
  long count(@Param("projectId") int projectId, @Param("q") String q,
      @Param("status") String status, @Param("authorId") Integer authorId);

  // CAS write on version -- 0 rows updated means a stale version (caller -> 409).
  @Update("""
      UPDATE discussion SET title = #{title}, body = #{body}, updated_at = now(), version = version + 1
      WHERE project_id = #{projectId} AND id = #{id} AND version = #{version}
      """)
  int update(@Param("projectId") int projectId, @Param("id") int id, @Param("title") String title,
      @Param("body") String body, @Param("version") Integer version);

  @Update("""
      UPDATE discussion SET status = #{status}, updated_at = now(), version = version + 1
      WHERE project_id = #{projectId} AND id = #{id}
      """)
  int updateStatus(@Param("projectId") int projectId, @Param("id") int id, @Param("status") String status);

  @Update("""
      UPDATE discussion SET visibility = #{visibility}, updated_at = now(), version = version + 1
      WHERE project_id = #{projectId} AND id = #{id}
      """)
  int updateVisibility(@Param("projectId") int projectId, @Param("id") int id,
      @Param("visibility") String visibility);

  @Delete("DELETE FROM discussion WHERE project_id = #{projectId} AND id = #{id}")
  int delete(@Param("projectId") int projectId, @Param("id") int id);
}
