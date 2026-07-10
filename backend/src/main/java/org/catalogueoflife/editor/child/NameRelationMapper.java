package org.catalogueoflife.editor.child;

import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.catalogueoflife.editor.child.dto.NameRelationRequest;
import org.catalogueoflife.editor.child.dto.NameRelationResponse;

@Mapper
public interface NameRelationMapper {

  String SELECT = """
      SELECT nr.id, nr.usage_id, nr.related_usage_id, r.scientific_name AS related_name,
             nr.type, nr.reference_id, nr.page, nr.remarks, nr.version
      FROM name_relation nr
      LEFT JOIN name_usage r ON r.project_id = nr.project_id AND r.id = nr.related_usage_id
      """;

  @Select(SELECT + " WHERE nr.project_id = #{projectId} AND nr.usage_id = #{usageId} ORDER BY nr.id")
  List<NameRelationResponse> findByUsage(@Param("projectId") int projectId, @Param("usageId") int usageId);

  @Select(SELECT + " WHERE nr.project_id = #{projectId} ORDER BY nr.id")
  List<NameRelationResponse> findByProject(@Param("projectId") int projectId);

  @Select(SELECT + " WHERE nr.project_id = #{projectId} AND nr.id = #{id}")
  NameRelationResponse findById(@Param("projectId") int projectId, @Param("id") int id);

  @Insert("""
      INSERT INTO name_relation (project_id, id, usage_id, related_usage_id, type, reference_id,
          page, remarks, modified_by)
      VALUES (#{projectId}, #{id}, #{usageId}, #{r.relatedUsageId}, #{r.type}, #{r.referenceId},
          #{r.page}, #{r.remarks}, #{modifiedBy})
      """)
  void insert(@Param("projectId") int projectId, @Param("id") int id, @Param("usageId") int usageId,
      @Param("r") NameRelationRequest r, @Param("modifiedBy") int modifiedBy);

  @Update("""
      UPDATE name_relation SET related_usage_id = #{r.relatedUsageId}, type = #{r.type},
          reference_id = #{r.referenceId}, page = #{r.page}, remarks = #{r.remarks},
          modified = now(), modified_by = #{modifiedBy}, version = version + 1
      WHERE project_id = #{projectId} AND id = #{id} AND version = #{r.version}
      """)
  int update(@Param("projectId") int projectId, @Param("id") int id,
      @Param("r") NameRelationRequest r, @Param("modifiedBy") int modifiedBy);

  @Delete("DELETE FROM name_relation WHERE project_id = #{projectId} AND id = #{id}")
  int delete(@Param("projectId") int projectId, @Param("id") int id);
}
