package org.catalogueoflife.editor.tree;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
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
  // The anchor (base case) IS filtered to status = 'ACCEPTED': the target id itself must be a
  // tree node, otherwise a synonym's id would leak a (bogus) path out of /tree/path -- see
  // TreeService.path's isEmpty()->404 check, which relies on a non-accepted target producing no
  // rows here. Every ancestor above the anchor is necessarily ACCEPTED anyway since parent_id
  // only ever links accepted->accepted. The depth bound on the recursive member is a defensive
  // termination guarantee (statement-timeout DoS guard) in case a cycle ever slips past the
  // create/update/move guards -- it does not change behavior for any valid (acyclic) tree.
  @Select("""
      WITH RECURSIVE anc AS (
        SELECT project_id, id, parent_id, scientific_name, rank, 0 AS depth
        FROM name_usage WHERE project_id = #{projectId} AND id = #{id} AND status = 'ACCEPTED'
        UNION ALL
        SELECT n.project_id, n.id, n.parent_id, n.scientific_name, n.rank, anc.depth + 1
        FROM name_usage n JOIN anc ON n.project_id = anc.project_id AND n.id = anc.parent_id
        WHERE anc.depth < 10000
      )
      SELECT id, scientific_name AS scientificName, rank FROM anc ORDER BY depth DESC
      """)
  List<PathNode> findPath(@Param("projectId") int projectId, @Param("id") int id);

  // Is candidateId equal to rootId, or within rootId's accepted subtree? Used by TreeService.move
  // and NameUsageService's create/update parentId guard to reject a reparent that would create a
  // cycle: a node can never become a descendant of its own descendant (or of itself). Restricted
  // to status = 'ACCEPTED' like the rest of the tree -- parent_id only ever links
  // accepted->accepted anyway (see V3__name_core.sql). The depth bound is the same defensive
  // termination guarantee as findPath's.
  @Select("""
      WITH RECURSIVE sub AS (
        SELECT project_id, id, 0 AS depth FROM name_usage
        WHERE project_id = #{projectId} AND id = #{rootId}
        UNION ALL
        SELECT n.project_id, n.id, sub.depth + 1 FROM name_usage n
        JOIN sub ON n.project_id = sub.project_id AND n.parent_id = sub.id
        WHERE n.status = 'ACCEPTED' AND sub.depth < 10000
      )
      SELECT EXISTS (SELECT 1 FROM sub WHERE id = #{candidateId})
      """)
  boolean isDescendant(@Param("projectId") int projectId, @Param("rootId") int rootId,
      @Param("candidateId") int candidateId);

  // Optimistic-locked reparent: 0 affected rows means either the usage no longer exists (already
  // deleted by another actor) or -- far more likely -- version is stale (concurrent edit);
  // TreeService.move turns that into a 409, matching NameUsageMapper.update's CAS pattern.
  @Update("""
      UPDATE name_usage
      SET parent_id = #{parentId}, version = version + 1, modified = now()
      WHERE project_id = #{projectId} AND id = #{id} AND version = #{version}
      """)
  int reparent(@Param("projectId") int projectId, @Param("id") int id,
      @Param("parentId") Integer parentId, @Param("version") int version);

  // Per-project advisory lock, held for the rest of the current transaction (pg_advisory_xact_lock
  // auto-releases at COMMIT/ROLLBACK -- no explicit unlock needed/possible). Any @Transactional
  // method that reads the tree shape to decide a reparent and then writes parent_id (TreeService.move;
  // NameUsageService.create/update when parentId is being set) must call this FIRST, before the
  // isDescendant/accepted-parent check: it serializes concurrent reparents within one project so two
  // racing moves can't each pass their own cycle check against a pre-move tree and then both commit,
  // jointly creating a cycle that neither check alone would have allowed.
  //
  // The return value is unused (callers invoke this purely for its side effect and discard the
  // result) -- it's boolean rather than void only because MyBatis has no way to bind a @Select
  // method with a void return type to a real query result without an extra ResultHandler
  // parameter (pg_advisory_xact_lock's own return type is SQL void, which isn't a type MyBatis's
  // automatic result mapping can construct either), and executeUpdate() -- the one MyBatis path
  // that does support a void return -- rejects SELECT statements outright at the JDBC level.
  // `IS NOT NULL` turns the call into a trivially mappable boolean (always true) without changing
  // its locking behavior.
  @Select("SELECT pg_advisory_xact_lock(#{projectId}) IS NOT NULL")
  boolean lockProject(@Param("projectId") int projectId);
}
