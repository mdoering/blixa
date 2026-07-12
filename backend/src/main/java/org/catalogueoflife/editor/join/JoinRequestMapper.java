package org.catalogueoflife.editor.join;

import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface JoinRequestMapper {

  // Idempotent: a repeat request from the same ORCID for the same project is a no-op, so a
  // visitor re-submitting the form (e.g. after a network retry) never errors nor duplicates.
  @Insert("""
      INSERT INTO join_request (project_id, orcid, name, message)
      VALUES (#{projectId}, #{orcid}, #{name}, #{message})
      ON CONFLICT (project_id, orcid) DO NOTHING
      """)
  void insertIgnoreDup(JoinRequest r);

  @Select("SELECT * FROM join_request WHERE project_id = #{projectId} ORDER BY created_at")
  List<JoinRequest> findByProject(@Param("projectId") int projectId);

  @Select("SELECT count(*) FROM join_request WHERE project_id = #{projectId}")
  int countByProject(@Param("projectId") int projectId);

  @Delete("DELETE FROM join_request WHERE project_id = #{projectId} AND id = #{id}")
  int delete(@Param("projectId") int projectId, @Param("id") int id);
}
