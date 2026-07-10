package org.catalogueoflife.editor.child;

import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.catalogueoflife.editor.child.dto.DistributionRequest;
import org.catalogueoflife.editor.child.dto.DistributionResponse;

@Mapper
public interface DistributionMapper {

  String SELECT = """
      SELECT id, usage_id, area, area_id, gazetteer, establishment_means, threat_status,
             reference_id, remarks, version
      FROM distribution
      """;

  @Select(SELECT + " WHERE project_id = #{projectId} AND usage_id = #{usageId} ORDER BY id")
  List<DistributionResponse> findByUsage(@Param("projectId") int projectId, @Param("usageId") int usageId);

  @Select(SELECT + " WHERE project_id = #{projectId} ORDER BY id")
  List<DistributionResponse> findByProject(@Param("projectId") int projectId);

  @Select(SELECT + " WHERE project_id = #{projectId} AND id = #{id}")
  DistributionResponse findById(@Param("projectId") int projectId, @Param("id") int id);

  @Insert("""
      INSERT INTO distribution (project_id, id, usage_id, area, area_id, gazetteer,
          establishment_means, threat_status, reference_id, remarks, modified_by)
      VALUES (#{projectId}, #{id}, #{usageId}, #{r.area}, #{r.areaId}, #{r.gazetteer},
          #{r.establishmentMeans}, #{r.threatStatus}, #{r.referenceId}, #{r.remarks}, #{modifiedBy})
      """)
  void insert(@Param("projectId") int projectId, @Param("id") int id, @Param("usageId") int usageId,
      @Param("r") DistributionRequest r, @Param("modifiedBy") int modifiedBy);

  @Update("""
      UPDATE distribution SET area = #{r.area}, area_id = #{r.areaId}, gazetteer = #{r.gazetteer},
          establishment_means = #{r.establishmentMeans}, threat_status = #{r.threatStatus},
          reference_id = #{r.referenceId}, remarks = #{r.remarks},
          modified = now(), modified_by = #{modifiedBy}, version = version + 1
      WHERE project_id = #{projectId} AND id = #{id} AND version = #{r.version}
      """)
  int update(@Param("projectId") int projectId, @Param("id") int id,
      @Param("r") DistributionRequest r, @Param("modifiedBy") int modifiedBy);

  @Delete("DELETE FROM distribution WHERE project_id = #{projectId} AND id = #{id}")
  int delete(@Param("projectId") int projectId, @Param("id") int id);
}
