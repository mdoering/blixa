package org.catalogueoflife.editor.tree;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.catalogueoflife.editor.tree.dto.PathNode;

// The classification tree is the name_usage.parent_id self-relation restricted to ACCEPTED
// usages only (synonyms attach via synonym_accepted and are never tree nodes -- see
// V3__name_core.sql). Traversal uses parent_id + the existing (project_id, parent_id) index
// (name_usage_parent_idx) and recursive CTEs; ltree/closure-table materialization is deliberately
// deferred (see the plan's self-review notes) until full-descendant counts or subtree locking
// need it.
//
// TreeNode/PathNode are records; MyBatis's automatic constructor-based result mapping (enabled by
// the -parameters compiler flag, see pom.xml) instantiates them directly from the aliased select
// columns below without any @Results/@ResultMap boilerplate.
@Mapper
public interface TreeMapper {

  @Select("""
      SELECT n.id, n.scientific_name AS scientificName, n.authorship, n.rank, n.status, n.ordinal,
        (SELECT COUNT(*) FROM name_usage c
           WHERE c.project_id = n.project_id AND c.parent_id = n.id AND c.status = 'ACCEPTED') AS childCount
      FROM name_usage n
      WHERE n.project_id = #{projectId} AND n.parent_id IS NULL AND n.status = 'ACCEPTED'
      ORDER BY n.ordinal NULLS LAST, n.scientific_name
      LIMIT #{limit} OFFSET #{offset}
      """)
  List<TreeNode> findRoots(@Param("projectId") int projectId, @Param("limit") int limit,
      @Param("offset") int offset);

  @Select("""
      SELECT n.id, n.scientific_name AS scientificName, n.authorship, n.rank, n.status, n.ordinal,
        (SELECT COUNT(*) FROM name_usage c
           WHERE c.project_id = n.project_id AND c.parent_id = n.id AND c.status = 'ACCEPTED') AS childCount
      FROM name_usage n
      WHERE n.project_id = #{projectId} AND n.parent_id = #{parentId} AND n.status = 'ACCEPTED'
      ORDER BY n.ordinal NULLS LAST, n.scientific_name
      LIMIT #{limit} OFFSET #{offset}
      """)
  List<TreeNode> findChildren(@Param("projectId") int projectId, @Param("parentId") int parentId,
      @Param("limit") int limit, @Param("offset") int offset);

  // Root-first ancestor path (including the node itself) via a recursive walk up parent_id.
  // Deliberately NOT filtered to status = 'ACCEPTED': the target id itself may be looked up
  // regardless of status (TreeService decides what that means), and every ancestor above it is
  // necessarily ACCEPTED anyway since parent_id only ever links accepted->accepted.
  @Select("""
      WITH RECURSIVE anc AS (
        SELECT project_id, id, parent_id, scientific_name, rank, 0 AS depth
        FROM name_usage WHERE project_id = #{projectId} AND id = #{id}
        UNION ALL
        SELECT n.project_id, n.id, n.parent_id, n.scientific_name, n.rank, anc.depth + 1
        FROM name_usage n JOIN anc ON n.project_id = anc.project_id AND n.id = anc.parent_id
      )
      SELECT id, scientific_name AS scientificName, rank FROM anc ORDER BY depth DESC
      """)
  List<PathNode> findPath(@Param("projectId") int projectId, @Param("id") int id);
}
