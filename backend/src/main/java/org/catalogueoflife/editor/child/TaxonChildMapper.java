package org.catalogueoflife.editor.child;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

// Bulk cleanup of the taxon-level child entities for a usage that is no longer accepted. Called from
// NameUsageService.writeTaxonInfo so both direct status edits and demote drop them (mirrors how
// taxon_info is dropped). See the child-entities spec.
@Mapper
public interface TaxonChildMapper {

  @Delete("DELETE FROM vernacular WHERE project_id = #{projectId} AND usage_id = #{usageId}")
  int deleteVernaculars(@Param("projectId") int projectId, @Param("usageId") int usageId);

  @Delete("DELETE FROM distribution WHERE project_id = #{projectId} AND usage_id = #{usageId}")
  int deleteDistributions(@Param("projectId") int projectId, @Param("usageId") int usageId);

  @Delete("DELETE FROM media WHERE project_id = #{projectId} AND usage_id = #{usageId}")
  int deleteMedia(@Param("projectId") int projectId, @Param("usageId") int usageId);

  @Delete("DELETE FROM estimate WHERE project_id = #{projectId} AND usage_id = #{usageId}")
  int deleteEstimates(@Param("projectId") int projectId, @Param("usageId") int usageId);

  @Delete("DELETE FROM property WHERE project_id = #{projectId} AND usage_id = #{usageId}")
  int deleteProperties(@Param("projectId") int projectId, @Param("usageId") int usageId);

  default void dropAll(int projectId, int usageId) {
    deleteVernaculars(projectId, usageId);
    deleteDistributions(projectId, usageId);
    deleteMedia(projectId, usageId);
    deleteEstimates(projectId, usageId);
    deleteProperties(projectId, usageId);
  }
}
