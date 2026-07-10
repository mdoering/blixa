package org.catalogueoflife.editor.child;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.catalogueoflife.editor.child.dto.MapAreaRecord;
import org.catalogueoflife.editor.child.dto.MapPointRecord;

// Aggregates distribution + type-specimen data over a focal usage AND its whole subtree, for
// MapDataService/the map view. `sub` is a recursive walk DOWN the tree via parent_id (the same
// direction as TreeMapper.isDescendant, just without its status = 'ACCEPTED' filter -- a synonym
// can never be a parent, so every row below the focal usage is already accepted regardless; the
// focal usage itself is included whatever its own status is). The `depth < 10000` guard on the
// recursive member matches every other recursive CTE in this codebase (TreeMapper.findPath,
// NameUsageMapper.findAncestorGenusName/findClassification, etc.) -- a defensive
// statement-timeout/DoS bound in case a cycle ever slips past the write-time guards
// (TreeMapper.isDescendant); it does not change behavior for any valid (acyclic) tree.
@Mapper
public interface MapDataMapper {

  @Select("""
      WITH RECURSIVE sub AS (
        SELECT id, 0 AS depth FROM name_usage WHERE project_id = #{projectId} AND id = #{usageId}
        UNION ALL
        SELECT c.id, sub.depth + 1 FROM name_usage c JOIN sub
          ON c.project_id = #{projectId} AND c.parent_id = sub.id
        WHERE sub.depth < 10000
      )
      SELECT d.usage_id, u.scientific_name AS name, (d.usage_id = #{usageId}) AS focal,
             d.gazetteer, d.area_id, d.area
      FROM distribution d JOIN sub ON d.usage_id = sub.id
      JOIN name_usage u ON u.project_id = #{projectId} AND u.id = d.usage_id
      WHERE d.project_id = #{projectId}
      ORDER BY focal DESC, d.usage_id
      """)
  List<MapAreaRecord> findSubtreeDistributions(@Param("projectId") int projectId,
      @Param("usageId") int usageId);

  @Select("""
      WITH RECURSIVE sub AS (
        SELECT id, 0 AS depth FROM name_usage WHERE project_id = #{projectId} AND id = #{usageId}
        UNION ALL
        SELECT c.id, sub.depth + 1 FROM name_usage c JOIN sub
          ON c.project_id = #{projectId} AND c.parent_id = sub.id
        WHERE sub.depth < 10000
      )
      SELECT tm.usage_id, u.scientific_name AS name, (tm.usage_id = #{usageId}) AS focal,
             tm.status, tm.latitude, tm.longitude, tm.locality
      FROM type_material tm JOIN sub ON tm.usage_id = sub.id
      JOIN name_usage u ON u.project_id = #{projectId} AND u.id = tm.usage_id
      WHERE tm.project_id = #{projectId} AND tm.latitude IS NOT NULL AND tm.longitude IS NOT NULL
      ORDER BY focal DESC, tm.usage_id
      """)
  List<MapPointRecord> findSubtreeTypePoints(@Param("projectId") int projectId,
      @Param("usageId") int usageId);
}
