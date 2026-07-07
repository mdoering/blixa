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
      INSERT INTO project (slug, title, alias, description, nom_code, license, version,
                           issued, geographic_scope, taxonomic_scope, doi, metadata)
      VALUES (#{slug}, #{title}, #{alias}, #{description}, #{nomCode}, #{license}, #{version},
              #{issued}, #{geographicScope}, #{taxonomicScope}, #{doi}, #{metadata}::jsonb)
      """)
  @Options(useGeneratedKeys = true, keyProperty = "id")
  void insert(Project p);

  @Select("SELECT * FROM project WHERE id = #{id}")
  Project findById(long id);

  @Select("SELECT * FROM project WHERE slug = #{slug}")
  Project findBySlug(String slug);

  @Select("""
      SELECT p.* FROM project p
      JOIN project_member m ON m.project_id = p.id
      WHERE m.user_id = #{userId}
      ORDER BY p.title
      """)
  List<Project> findByMember(long userId);

  @Update("""
      UPDATE project
      SET title = #{title}, alias = #{alias}, description = #{description},
          nom_code = #{nomCode}, license = #{license}, version = #{version},
          issued = #{issued}, geographic_scope = #{geographicScope},
          taxonomic_scope = #{taxonomicScope}, doi = #{doi},
          metadata = #{metadata}::jsonb, updated_at = now()
      WHERE id = #{id}
      """)
  void updateMetadata(Project p);
}
