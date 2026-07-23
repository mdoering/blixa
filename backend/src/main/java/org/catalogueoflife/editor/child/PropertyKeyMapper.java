package org.catalogueoflife.editor.child;

import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.catalogueoflife.editor.child.dto.PropertyKeyInfo;

// Project-wide taxon property-key overview + reconciliation, mirroring the journal-name facet/merge
// on reference.container_title (see ReferenceMapper.containerTitleFacet / mergeContainerTitle).
@Mapper
public interface PropertyKeyMapper {

  // Every key in the project: the union of used keys (distinct property.property) and defined keys
  // (property_key), each with its usage count (0 for defined-but-unused) and description (null for
  // used-but-undefined). Ordered most-used first, then alphabetically -- the overview + autocomplete.
  @Select("""
      SELECT keys.k AS "key",
             COALESCE(f.cnt, 0) AS "count",
             pk.description AS description
      FROM (
        SELECT DISTINCT property AS k FROM property
          WHERE project_id = #{projectId} AND property IS NOT NULL AND property <> ''
        UNION
        SELECT key AS k FROM property_key WHERE project_id = #{projectId}
      ) keys
      LEFT JOIN (
        SELECT property AS k, count(*) AS cnt FROM property
          WHERE project_id = #{projectId} AND property IS NOT NULL AND property <> ''
          GROUP BY property
      ) f ON f.k = keys.k
      LEFT JOIN property_key pk ON pk.project_id = #{projectId} AND pk.key = keys.k
      ORDER BY "count" DESC, "key"
      """)
  List<PropertyKeyInfo> keyFacet(@Param("projectId") int projectId);

  // A single key's overview row (always returns one row, count 0 / description null if unknown) --
  // returned from PUT so the UI gets the resulting count + description without a full refetch.
  @Select("""
      SELECT #{key} AS "key",
             (SELECT count(*) FROM property
                WHERE project_id = #{projectId} AND property = #{key}) AS "count",
             (SELECT description FROM property_key
                WHERE project_id = #{projectId} AND key = #{key}) AS description
      """)
  PropertyKeyInfo keyInfo(@Param("projectId") int projectId, @Param("key") String key);

  @Select("SELECT description FROM property_key WHERE project_id = #{projectId} AND key = #{key}")
  String descriptionOf(@Param("projectId") int projectId, @Param("key") String key);

  // Define a standard key / edit its description (upsert). A null/blank description keeps the row but
  // clears the text.
  @Insert("""
      INSERT INTO property_key (project_id, key, description)
      VALUES (#{projectId}, #{key}, #{description})
      ON CONFLICT (project_id, key) DO UPDATE SET description = EXCLUDED.description
      """)
  void upsertDefinition(@Param("projectId") int projectId, @Param("key") String key,
      @Param("description") String description);

  @Delete("DELETE FROM property_key WHERE project_id = #{projectId} AND key = #{key}")
  int deleteDefinition(@Param("projectId") int projectId, @Param("key") String key);

  @Delete({"<script>",
      "DELETE FROM property_key WHERE project_id = #{projectId} AND key IN",
      "<foreach item='k' collection='keys' open='(' separator=',' close=')'>#{k}</foreach>",
      "</script>"})
  int deleteDefinitions(@Param("projectId") int projectId, @Param("keys") List<String> keys);

  // Bulk field normalization: rewrites every property row whose key is one of `variants` (which may
  // include `canonical` itself, a harmless no-op match) to `canonical`. Maintenance cleanup across
  // many rows, not a per-row edit -- deliberately no version bump / audit row, same convention as
  // ReferenceMapper.mergeContainerTitle.
  @org.apache.ibatis.annotations.Update({"<script>",
      "UPDATE property SET property = #{canonical}",
      "WHERE project_id = #{projectId} AND property IN",
      "<foreach item='v' collection='variants' open='(' separator=',' close=')'>#{v}</foreach>",
      "</script>"})
  int mergeKey(@Param("projectId") int projectId, @Param("canonical") String canonical,
      @Param("variants") List<String> variants);
}
