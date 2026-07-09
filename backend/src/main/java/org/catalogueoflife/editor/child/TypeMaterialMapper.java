package org.catalogueoflife.editor.child;

import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.catalogueoflife.editor.child.dto.TypeMaterialRequest;
import org.catalogueoflife.editor.child.dto.TypeMaterialResponse;

@Mapper
public interface TypeMaterialMapper {

  // "date" is quoted: it is a keyword in SQL, though non-reserved in Postgres.
  String SELECT = """
      SELECT id, usage_id, citation, status, institution_code, catalog_number, occurrence_id,
             locality, country, collector, "date", sex, reference_id, link, remarks, version
      FROM type_material
      """;

  @Select(SELECT + " WHERE project_id = #{projectId} AND usage_id = #{usageId} ORDER BY id")
  List<TypeMaterialResponse> findByUsage(@Param("projectId") int projectId, @Param("usageId") int usageId);

  @Select(SELECT + " WHERE project_id = #{projectId} AND id = #{id}")
  TypeMaterialResponse findById(@Param("projectId") int projectId, @Param("id") int id);

  @Insert("""
      INSERT INTO type_material (project_id, id, usage_id, citation, status, institution_code,
          catalog_number, occurrence_id, locality, country, collector, "date", sex, reference_id,
          link, remarks, modified_by)
      VALUES (#{projectId}, #{id}, #{usageId}, #{r.citation}, #{r.status}, #{r.institutionCode},
          #{r.catalogNumber}, #{r.occurrenceId}, #{r.locality}, #{r.country}, #{r.collector},
          #{r.date}, #{r.sex}, #{r.referenceId}, #{r.link}, #{r.remarks}, #{modifiedBy})
      """)
  void insert(@Param("projectId") int projectId, @Param("id") int id, @Param("usageId") int usageId,
      @Param("r") TypeMaterialRequest r, @Param("modifiedBy") int modifiedBy);

  @Update("""
      UPDATE type_material SET citation = #{r.citation}, status = #{r.status},
          institution_code = #{r.institutionCode}, catalog_number = #{r.catalogNumber},
          occurrence_id = #{r.occurrenceId}, locality = #{r.locality}, country = #{r.country},
          collector = #{r.collector}, "date" = #{r.date}, sex = #{r.sex},
          reference_id = #{r.referenceId}, link = #{r.link}, remarks = #{r.remarks},
          modified = now(), modified_by = #{modifiedBy}, version = version + 1
      WHERE project_id = #{projectId} AND id = #{id} AND version = #{r.version}
      """)
  int update(@Param("projectId") int projectId, @Param("id") int id,
      @Param("r") TypeMaterialRequest r, @Param("modifiedBy") int modifiedBy);

  @Delete("DELETE FROM type_material WHERE project_id = #{projectId} AND id = #{id}")
  int delete(@Param("projectId") int projectId, @Param("id") int id);
}
