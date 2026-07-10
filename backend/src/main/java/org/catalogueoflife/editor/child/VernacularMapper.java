package org.catalogueoflife.editor.child;

import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.catalogueoflife.editor.child.dto.VernacularRequest;
import org.catalogueoflife.editor.child.dto.VernacularResponse;

@Mapper
public interface VernacularMapper {

  String SELECT = """
      SELECT id, usage_id, name, language, country, sex, preferred, reference_id, remarks, version
      FROM vernacular
      """;

  @Select(SELECT + " WHERE project_id = #{projectId} AND usage_id = #{usageId} ORDER BY id")
  List<VernacularResponse> findByUsage(@Param("projectId") int projectId, @Param("usageId") int usageId);

  @Select(SELECT + " WHERE project_id = #{projectId} ORDER BY id")
  List<VernacularResponse> findByProject(@Param("projectId") int projectId);

  @Select(SELECT + " WHERE project_id = #{projectId} AND id = #{id}")
  VernacularResponse findById(@Param("projectId") int projectId, @Param("id") int id);

  @Insert("""
      INSERT INTO vernacular (project_id, id, usage_id, name, language, country, sex, preferred,
          reference_id, remarks, modified_by)
      VALUES (#{projectId}, #{id}, #{usageId}, #{r.name}, #{r.language}, #{r.country}, #{r.sex},
          #{r.preferred}, #{r.referenceId}, #{r.remarks}, #{modifiedBy})
      """)
  void insert(@Param("projectId") int projectId, @Param("id") int id, @Param("usageId") int usageId,
      @Param("r") VernacularRequest r, @Param("modifiedBy") int modifiedBy);

  @Update("""
      UPDATE vernacular SET name = #{r.name}, language = #{r.language}, country = #{r.country},
          sex = #{r.sex}, preferred = #{r.preferred}, reference_id = #{r.referenceId},
          remarks = #{r.remarks}, modified = now(), modified_by = #{modifiedBy}, version = version + 1
      WHERE project_id = #{projectId} AND id = #{id} AND version = #{r.version}
      """)
  int update(@Param("projectId") int projectId, @Param("id") int id,
      @Param("r") VernacularRequest r, @Param("modifiedBy") int modifiedBy);

  @Delete("DELETE FROM vernacular WHERE project_id = #{projectId} AND id = #{id}")
  int delete(@Param("projectId") int projectId, @Param("id") int id);
}
