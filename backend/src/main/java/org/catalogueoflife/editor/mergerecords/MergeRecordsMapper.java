package org.catalogueoflife.editor.mergerecords;

import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MergeRecordsMapper {

  // All association counts for one usage in a single round-trip (subqueries). Column labels are the
  // camelCase count keys; MyBatis maps to a Map<String,Object> (values are Long from count()).
  @Select("""
      SELECT
        (SELECT count(*) FROM name_usage       WHERE project_id = #{pid} AND parent_id = #{id})                       AS children,
        (SELECT count(*) FROM synonym_accepted WHERE project_id = #{pid} AND accepted_id = #{id})                     AS synonyms,
        (SELECT count(*) FROM synonym_accepted WHERE project_id = #{pid} AND synonym_id = #{id})                      AS "acceptedOf",
        (SELECT count(*) FROM name_usage       WHERE project_id = #{pid} AND basionym_id = #{id})                     AS "basionymOf",
        (SELECT count(*) FROM name_relation    WHERE project_id = #{pid} AND (usage_id = #{id} OR related_usage_id = #{id})) AS "nameRelations",
        (SELECT count(*) FROM vernacular       WHERE project_id = #{pid} AND usage_id = #{id})                        AS vernacular,
        (SELECT count(*) FROM distribution     WHERE project_id = #{pid} AND usage_id = #{id})                        AS distribution,
        (SELECT count(*) FROM media            WHERE project_id = #{pid} AND usage_id = #{id})                        AS media,
        (SELECT count(*) FROM type_material    WHERE project_id = #{pid} AND usage_id = #{id})                        AS "typeMaterial",
        (SELECT count(*) FROM property         WHERE project_id = #{pid} AND usage_id = #{id})                        AS property,
        (SELECT count(*) FROM estimate         WHERE project_id = #{pid} AND usage_id = #{id})                        AS estimate
      """)
  Map<String, Object> usageCounts(@Param("pid") int pid, @Param("id") int id);
}
