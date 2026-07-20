package org.catalogueoflife.editor.discussion;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DiscussionApiTokenMapper {

  @Select("SELECT token FROM discussion_api_token WHERE project_id = #{projectId}")
  String findToken(@Param("projectId") int projectId);

  @Insert("""
      INSERT INTO discussion_api_token (project_id, token) VALUES (#{projectId}, #{token})
      ON CONFLICT (project_id) DO UPDATE SET token = #{token}, created_at = now()
      """)
  void upsert(@Param("projectId") int projectId, @Param("token") String token);

  @Delete("DELETE FROM discussion_api_token WHERE project_id = #{projectId}")
  void delete(@Param("projectId") int projectId);
}
