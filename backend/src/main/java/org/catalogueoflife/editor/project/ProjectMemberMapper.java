package org.catalogueoflife.editor.project;

import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ProjectMemberMapper {

  @Insert("""
      INSERT INTO project_member (project_id, user_id, role)
      VALUES (#{projectId}, #{userId}, #{role})
      ON CONFLICT (project_id, user_id) DO UPDATE SET role = EXCLUDED.role
      """)
  void upsert(ProjectMember m);

  @Select("SELECT role FROM project_member WHERE project_id = #{projectId} AND user_id = #{userId}")
  String findRole(@Param("projectId") int projectId, @Param("userId") int userId);

  @Select("SELECT * FROM project_member WHERE project_id = #{projectId}")
  List<ProjectMember> findByProject(int projectId);

  @Delete("DELETE FROM project_member WHERE project_id = #{projectId} AND user_id = #{userId}")
  void delete(@Param("projectId") int projectId, @Param("userId") int userId);
}
