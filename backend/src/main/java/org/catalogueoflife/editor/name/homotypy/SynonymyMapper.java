package org.catalogueoflife.editor.name.homotypy;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SynonymyMapper {

  // Every usage transitively homotypically related to #{usageId} via name_relation edges of a
  // homotypic type (walked in BOTH directions, type normalized case/_/-). Depth-guarded; excludes
  // the seed usage itself. Blixa datasets are small so depth 100 is ample.
  @Select("""
      WITH RECURSIVE closure(uid, depth) AS (
        SELECT #{usageId}, 0
        UNION
        SELECT CASE WHEN nr.usage_id = c.uid THEN nr.related_usage_id ELSE nr.usage_id END, c.depth + 1
        FROM closure c
        JOIN name_relation nr ON nr.project_id = #{projectId}
          AND lower(regexp_replace(nr.type, '[_-]', ' ', 'g'))
              IN ('basionym','homotypic','spelling correction','based on','replacement name','superfluous')
          AND (nr.usage_id = c.uid OR nr.related_usage_id = c.uid)
        WHERE c.depth < 100
      )
      SELECT DISTINCT uid FROM closure WHERE uid IS NOT NULL AND uid <> #{usageId}
      """)
  List<Integer> homotypicClosure(@Param("projectId") int projectId, @Param("usageId") int usageId);
}
