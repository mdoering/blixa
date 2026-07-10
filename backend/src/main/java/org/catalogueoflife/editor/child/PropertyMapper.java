package org.catalogueoflife.editor.child;

import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.catalogueoflife.editor.child.dto.PropertyRequest;
import org.catalogueoflife.editor.child.dto.PropertyResponse;

@Mapper
public interface PropertyMapper {

  String SELECT = """
      SELECT id, usage_id, property, value, page, reference_id, remarks, version
      FROM property
      """;

  @Select(SELECT + " WHERE project_id = #{projectId} AND usage_id = #{usageId} ORDER BY id")
  List<PropertyResponse> findByUsage(@Param("projectId") int projectId, @Param("usageId") int usageId);

  @Select(SELECT + " WHERE project_id = #{projectId} ORDER BY id")
  List<PropertyResponse> findByProject(@Param("projectId") int projectId);

  @Select(SELECT + " WHERE project_id = #{projectId} AND id = #{id}")
  PropertyResponse findById(@Param("projectId") int projectId, @Param("id") int id);

  @Insert("""
      INSERT INTO property (project_id, id, usage_id, property, value, page, reference_id, remarks,
          modified_by)
      VALUES (#{projectId}, #{id}, #{usageId}, #{r.property}, #{r.value}, #{r.page}, #{r.referenceId},
          #{r.remarks}, #{modifiedBy})
      """)
  void insert(@Param("projectId") int projectId, @Param("id") int id, @Param("usageId") int usageId,
      @Param("r") PropertyRequest r, @Param("modifiedBy") int modifiedBy);

  @Update("""
      UPDATE property SET property = #{r.property}, value = #{r.value}, page = #{r.page},
          reference_id = #{r.referenceId}, remarks = #{r.remarks},
          modified = now(), modified_by = #{modifiedBy}, version = version + 1
      WHERE project_id = #{projectId} AND id = #{id} AND version = #{r.version}
      """)
  int update(@Param("projectId") int projectId, @Param("id") int id,
      @Param("r") PropertyRequest r, @Param("modifiedBy") int modifiedBy);

  @Delete("DELETE FROM property WHERE project_id = #{projectId} AND id = #{id}")
  int delete(@Param("projectId") int projectId, @Param("id") int id);
}
