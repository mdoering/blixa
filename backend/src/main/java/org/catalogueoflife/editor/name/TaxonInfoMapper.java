package org.catalogueoflife.editor.name;

import java.util.List;
import life.catalogue.api.vocab.Environment;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

// Taxon-level attributes for a single (accepted) usage. Reads happen through NameUsageMapper's
// LEFT JOIN so the NameUsage POJO/response are populated in one query; this mapper owns only the
// write side (upsert/delete) plus a count used by tests and future callers (e.g. P2 demote).
@Mapper
public interface TaxonInfoMapper {

  @Insert("""
      INSERT INTO taxon_info (project_id, usage_id, extinct, environment,
          temporal_range_start, temporal_range_end)
      VALUES (#{projectId}, #{usageId}, #{extinct},
          #{environment,typeHandler=org.catalogueoflife.editor.name.EnvironmentArrayTypeHandler},
          #{temporalRangeStart}, #{temporalRangeEnd})
      ON CONFLICT (project_id, usage_id) DO UPDATE SET
          extinct = EXCLUDED.extinct,
          environment = EXCLUDED.environment,
          temporal_range_start = EXCLUDED.temporal_range_start,
          temporal_range_end = EXCLUDED.temporal_range_end
      """)
  void upsert(@Param("projectId") int projectId, @Param("usageId") int usageId,
      @Param("extinct") Boolean extinct, @Param("environment") List<Environment> environment,
      @Param("temporalRangeStart") String temporalRangeStart,
      @Param("temporalRangeEnd") String temporalRangeEnd);

  @Delete("DELETE FROM taxon_info WHERE project_id = #{projectId} AND usage_id = #{usageId}")
  void delete(@Param("projectId") int projectId, @Param("usageId") int usageId);

  @Select("SELECT count(*) FROM taxon_info WHERE project_id = #{projectId} AND usage_id = #{usageId}")
  int count(@Param("projectId") int projectId, @Param("usageId") int usageId);
}
