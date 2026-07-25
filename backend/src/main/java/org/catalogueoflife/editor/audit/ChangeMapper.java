package org.catalogueoflife.editor.audit;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

// change is append-only (see V4__change.sql) -- deliberately no update/delete methods here.
// map-underscore-to-camel-case (application.yml) auto-maps every selected column onto Change's
// properties without needing @Results boilerplate, the same way ProjectMapper/ReferenceMapper's
// simple selects do.
@Mapper
public interface ChangeMapper {

  // The change plus its author's username, its objective's (discussion's) title, and a resolved
  // human label for the changed entity -- all LEFT JOINs so a change with no user / no objective /
  // a since-deleted entity still returns with nulls. `discussion_title` and `entity_label` are
  // read-only display (Change.discussionTitle/entityLabel), never inserted. entity_label is resolved
  // for the two linkable types the History view knows (see HistoryPage.entityLink): a name_usage's
  // scientific name + authorship, and a reference's abbreviated (title-short) or full citation. Other
  // types (and deleted entities) return null, and the UI falls back to "<entityType> #<id>".
  String SELECT = """
      SELECT c.id, c.project_id, c.user_id, u.username, c.at, c.entity_type, c.entity_id,
             c.operation, c.diff, c.discussion_id, d.title AS discussion_title,
             CASE c.entity_type
               WHEN 'name_usage' THEN nu.scientific_name || COALESCE(' ' || nu.authorship, '')
               WHEN 'reference' THEN COALESCE(ref.title_short, ref.citation)
             END AS entity_label
      FROM change c
      LEFT JOIN app_user u ON u.id = c.user_id
      LEFT JOIN discussion d ON d.project_id = c.project_id AND d.id = c.discussion_id
      LEFT JOIN name_usage nu
        ON c.entity_type = 'name_usage' AND nu.project_id = c.project_id AND nu.id = c.entity_id
      LEFT JOIN reference ref
        ON c.entity_type = 'reference' AND ref.project_id = c.project_id AND ref.id = c.entity_id
      """;

  @Insert("""
      INSERT INTO change (project_id, user_id, entity_type, entity_id, operation, diff, discussion_id)
      VALUES (#{projectId}, #{userId}, #{entityType}, #{entityId}, #{operation}, #{diff}::jsonb, #{discussionId})
      """)
  @Options(useGeneratedKeys = true, keyProperty = "id")
  void insert(Change c);

  @Select(SELECT + """
      WHERE c.project_id = #{projectId}
      ORDER BY c.at DESC, c.id DESC
      LIMIT #{limit} OFFSET #{offset}
      """)
  List<Change> findByProject(@Param("projectId") int projectId, @Param("limit") int limit,
      @Param("offset") int offset);

  @Select(SELECT + """
      WHERE c.project_id = #{projectId} AND c.entity_type = #{entityType} AND c.entity_id = #{entityId}
      ORDER BY c.at DESC, c.id DESC
      LIMIT #{limit} OFFSET #{offset}
      """)
  List<Change> findByEntity(@Param("projectId") int projectId, @Param("entityType") String entityType,
      @Param("entityId") int entityId, @Param("limit") int limit, @Param("offset") int offset);

  @Select(SELECT + """
      WHERE c.project_id = #{projectId} AND c.entity_type = #{entityType}
      ORDER BY c.at DESC, c.id DESC
      LIMIT #{limit} OFFSET #{offset}
      """)
  List<Change> findByType(@Param("projectId") int projectId, @Param("entityType") String entityType,
      @Param("limit") int limit, @Param("offset") int offset);

  // Grouped-by-objective changelog: every change authored under one discussion.
  @Select(SELECT + """
      WHERE c.project_id = #{projectId} AND c.discussion_id = #{discussionId}
      ORDER BY c.at DESC, c.id DESC
      LIMIT #{limit} OFFSET #{offset}
      """)
  List<Change> findByDiscussion(@Param("projectId") int projectId,
      @Param("discussionId") int discussionId, @Param("limit") int limit, @Param("offset") int offset);
}
