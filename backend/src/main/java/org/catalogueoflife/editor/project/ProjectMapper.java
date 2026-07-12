package org.catalogueoflife.editor.project;

import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
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

  // identifier_scopes is a Postgres JSONB list of {scope, datasetKey} objects --
  // map-underscore-to-camel-case handles the column name but not the JSONB <-> record-list
  // conversion, so it needs the explicit typeHandler here (see IdentifierScopeListTypeHandler);
  // every other column still auto-maps (partial @Results).
  @Select("SELECT * FROM project WHERE id = #{id}")
  @Results(id = "projectResult", value = {
      @Result(property = "identifierScopes", column = "identifier_scopes",
          typeHandler = IdentifierScopeListTypeHandler.class)
  })
  Project findById(int id);

  @Select("""
      SELECT p.* FROM project p
      JOIN project_member m ON m.project_id = p.id
      WHERE m.user_id = #{userId}
      ORDER BY p.title
      """)
  @ResultMap("projectResult")
  List<Project> findByMember(int userId);

  @Update("""
      UPDATE project
      SET title = #{title}, alias = #{alias}, description = #{description},
          nom_code = #{nomCode}, license = #{license},
          geographic_scope = #{geographicScope}, taxonomic_scope = #{taxonomicScope},
          metadata = #{metadata}::jsonb, gbif_occurrence_layer = #{gbifOccurrenceLayer},
          identifier_scopes = #{identifierScopes,typeHandler=org.catalogueoflife.editor.project.IdentifierScopeListTypeHandler,jdbcType=OTHER},
          csl_style = #{cslStyle},
          updated_at = now()
      WHERE id = #{id}
      """)
  void updateMetadata(Project p);

  @Delete("DELETE FROM project WHERE id = #{id}")
  void delete(int id);

  @Update("UPDATE project SET is_public = #{isPublic}, updated_at = now() WHERE id = #{id}")
  void updatePublic(@org.apache.ibatis.annotations.Param("id") int id,
      @org.apache.ibatis.annotations.Param("isPublic") boolean isPublic);
}
