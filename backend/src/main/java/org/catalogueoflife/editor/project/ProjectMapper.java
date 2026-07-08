package org.catalogueoflife.editor.project;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ProjectMapper {

  @Insert("""
      INSERT INTO project (title, alias, description, nom_code, license, geographic_scope, taxonomic_scope, metadata)
      VALUES (#{title}, #{alias}, #{description}, #{nomCode}, #{license}, #{geographicScope}, #{taxonomicScope}, #{metadata}::jsonb)
      """)
  @Options(useGeneratedKeys = true, keyProperty = "id")
  void insert(Project p);

  @Select("SELECT * FROM project WHERE id = #{id}")
  Project findById(int id);

  @Select("""
      SELECT p.* FROM project p
      JOIN project_member m ON m.project_id = p.id
      WHERE m.user_id = #{userId}
      ORDER BY p.title
      """)
  List<Project> findByMember(int userId);

  @Update("""
      UPDATE project
      SET title = #{title}, alias = #{alias}, description = #{description},
          nom_code = #{nomCode}, license = #{license},
          geographic_scope = #{geographicScope}, taxonomic_scope = #{taxonomicScope},
          metadata = #{metadata}::jsonb, updated_at = now()
      WHERE id = #{id}
      """)
  void updateMetadata(Project p);
}
