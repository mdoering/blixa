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

  @Insert("""
      INSERT INTO change (project_id, user_id, entity_type, entity_id, operation, diff)
      VALUES (#{projectId}, #{userId}, #{entityType}, #{entityId}, #{operation}, #{diff}::jsonb)
      """)
  @Options(useGeneratedKeys = true, keyProperty = "id")
  void insert(Change c);

  @Select("""
      SELECT c.id, c.project_id, c.user_id, u.username, c.at, c.entity_type, c.entity_id,
             c.operation, c.diff
      FROM change c
      LEFT JOIN app_user u ON u.id = c.user_id
      WHERE c.project_id = #{projectId}
      ORDER BY c.at DESC, c.id DESC
      LIMIT #{limit} OFFSET #{offset}
      """)
  List<Change> findByProject(@Param("projectId") int projectId, @Param("limit") int limit,
      @Param("offset") int offset);

  @Select("""
      SELECT c.id, c.project_id, c.user_id, u.username, c.at, c.entity_type, c.entity_id,
             c.operation, c.diff
      FROM change c
      LEFT JOIN app_user u ON u.id = c.user_id
      WHERE c.project_id = #{projectId} AND c.entity_type = #{entityType} AND c.entity_id = #{entityId}
      ORDER BY c.at DESC, c.id DESC
      LIMIT #{limit} OFFSET #{offset}
      """)
  List<Change> findByEntity(@Param("projectId") int projectId, @Param("entityType") String entityType,
      @Param("entityId") int entityId, @Param("limit") int limit, @Param("offset") int offset);
}
