package org.catalogueoflife.editor.mergerecords;

import java.util.Map;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

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

  // --- name-usage FK repoints (merged -> survivor). Order-independent; run all BEFORE deleting merged. ---
  @Update("UPDATE name_usage SET basionym_id = #{survivor} WHERE project_id = #{pid} AND basionym_id = #{merged}")
  int repointBasionym(@Param("pid") int pid, @Param("merged") int merged, @Param("survivor") int survivor);

  // synonym_accepted: pre-delete rows that would collide with an existing (survivor,x) pair, then repoint.
  @Delete("""
      DELETE FROM synonym_accepted d WHERE d.project_id = #{pid} AND d.synonym_id = #{merged}
        AND EXISTS (SELECT 1 FROM synonym_accepted x WHERE x.project_id = #{pid}
                    AND x.synonym_id = #{survivor} AND x.accepted_id = d.accepted_id)""")
  int deleteSynonymCollisions(@Param("pid") int pid, @Param("merged") int merged, @Param("survivor") int survivor);
  @Update("UPDATE synonym_accepted SET synonym_id = #{survivor} WHERE project_id = #{pid} AND synonym_id = #{merged}")
  int repointSynonymId(@Param("pid") int pid, @Param("merged") int merged, @Param("survivor") int survivor);

  @Select("SELECT count(*) FROM synonym_accepted WHERE project_id = #{pid} AND synonym_id = #{id}")
  int acceptedOfCount(@Param("pid") int pid, @Param("id") int id);

  @Delete("""
      DELETE FROM synonym_accepted d WHERE d.project_id = #{pid} AND d.accepted_id = #{merged}
        AND EXISTS (SELECT 1 FROM synonym_accepted x WHERE x.project_id = #{pid}
                    AND x.accepted_id = #{survivor} AND x.synonym_id = d.synonym_id)""")
  int deleteAcceptedCollisions(@Param("pid") int pid, @Param("merged") int merged, @Param("survivor") int survivor);
  @Update("UPDATE synonym_accepted SET accepted_id = #{survivor} WHERE project_id = #{pid} AND accepted_id = #{merged}")
  int repointAcceptedId(@Param("pid") int pid, @Param("merged") int merged, @Param("survivor") int survivor);

  @Delete("DELETE FROM synonym_accepted WHERE project_id = #{pid} AND synonym_id = accepted_id")
  int dropSynonymSelfLinks(@Param("pid") int pid);

  // taxon_info is 1-row-per-usage: drop merged's row if survivor already has one, else repoint.
  @Delete("""
      DELETE FROM taxon_info WHERE project_id = #{pid} AND usage_id = #{merged}
        AND EXISTS (SELECT 1 FROM taxon_info x WHERE x.project_id = #{pid} AND x.usage_id = #{survivor})""")
  int dropTaxonInfoIfSurvivorHas(@Param("pid") int pid, @Param("merged") int merged, @Param("survivor") int survivor);
  @Update("UPDATE taxon_info SET usage_id = #{survivor} WHERE project_id = #{pid} AND usage_id = #{merged}")
  int repointTaxonInfo(@Param("pid") int pid, @Param("merged") int merged, @Param("survivor") int survivor);

  // name_relation has TWO usage pointers; related_usage_id has no FK. Repoint both, then drop self-relations.
  @Update("UPDATE name_relation SET usage_id = #{survivor} WHERE project_id = #{pid} AND usage_id = #{merged}")
  int repointRelationUsage(@Param("pid") int pid, @Param("merged") int merged, @Param("survivor") int survivor);
  @Update("UPDATE name_relation SET related_usage_id = #{survivor} WHERE project_id = #{pid} AND related_usage_id = #{merged}")
  int repointRelationRelated(@Param("pid") int pid, @Param("merged") int merged, @Param("survivor") int survivor);
  @Delete("DELETE FROM name_relation WHERE project_id = #{pid} AND usage_id = related_usage_id")
  int dropSelfRelations(@Param("pid") int pid);

  // simple child tables (PK (project_id,id) -> no collision on repoint)
  @Update("UPDATE vernacular    SET usage_id = #{survivor} WHERE project_id = #{pid} AND usage_id = #{merged}") int repointVernacular(@Param("pid") int pid, @Param("merged") int merged, @Param("survivor") int survivor);
  @Update("UPDATE distribution  SET usage_id = #{survivor} WHERE project_id = #{pid} AND usage_id = #{merged}") int repointDistribution(@Param("pid") int pid, @Param("merged") int merged, @Param("survivor") int survivor);
  @Update("UPDATE media         SET usage_id = #{survivor} WHERE project_id = #{pid} AND usage_id = #{merged}") int repointMedia(@Param("pid") int pid, @Param("merged") int merged, @Param("survivor") int survivor);
  @Update("UPDATE type_material SET usage_id = #{survivor} WHERE project_id = #{pid} AND usage_id = #{merged}") int repointTypeMaterial(@Param("pid") int pid, @Param("merged") int merged, @Param("survivor") int survivor);
  @Update("UPDATE property      SET usage_id = #{survivor} WHERE project_id = #{pid} AND usage_id = #{merged}") int repointProperty(@Param("pid") int pid, @Param("merged") int merged, @Param("survivor") int survivor);
  @Update("UPDATE estimate      SET usage_id = #{survivor} WHERE project_id = #{pid} AND usage_id = #{merged}") int repointEstimate(@Param("pid") int pid, @Param("merged") int merged, @Param("survivor") int survivor);

  // --- reference merge: association counts + repoints (merged -> survivor), run all BEFORE deleteReference. ---
  @Select("""
      SELECT
        (SELECT count(*) FROM name_usage    WHERE project_id = #{pid} AND published_in_reference_id = #{id}) AS "publishedIn",
        (SELECT count(*) FROM name_usage    WHERE project_id = #{pid} AND #{id} = ANY(reference_id))          AS "citedBy",
        (SELECT count(*) FROM name_relation WHERE project_id = #{pid} AND reference_id = #{id})               AS "nameRelations",
        (SELECT count(*) FROM type_material WHERE project_id = #{pid} AND reference_id = #{id})               AS "typeMaterial",
        (SELECT count(*) FROM vernacular    WHERE project_id = #{pid} AND reference_id = #{id})               AS vernacular,
        (SELECT count(*) FROM distribution  WHERE project_id = #{pid} AND reference_id = #{id})               AS distribution,
        (SELECT count(*) FROM estimate      WHERE project_id = #{pid} AND reference_id = #{id})               AS estimate,
        (SELECT count(*) FROM property      WHERE project_id = #{pid} AND reference_id = #{id})               AS property
      """)
  Map<String, Object> referenceCounts(@Param("pid") int pid, @Param("id") int id);

  @Update("UPDATE name_usage SET published_in_reference_id = #{survivor} WHERE project_id = #{pid} AND published_in_reference_id = #{merged}")
  int repointPublishedIn(@Param("pid") int pid, @Param("merged") int merged, @Param("survivor") int survivor);
  // array column: replace merged->survivor in every citing usage's reference_id[], then de-dup the array
  @Update("UPDATE name_usage SET reference_id = array_replace(reference_id, #{merged}, #{survivor}) WHERE project_id = #{pid} AND #{merged} = ANY(reference_id)")
  int repointReferenceArray(@Param("pid") int pid, @Param("merged") int merged, @Param("survivor") int survivor);
  @Update("UPDATE name_usage SET reference_id = (SELECT array_agg(DISTINCT e) FROM unnest(reference_id) e) WHERE project_id = #{pid} AND #{survivor} = ANY(reference_id)")
  int dedupReferenceArray(@Param("pid") int pid, @Param("survivor") int survivor);

  @Update("UPDATE name_relation SET reference_id = #{survivor} WHERE project_id = #{pid} AND reference_id = #{merged}") int repointRefNameRelation(@Param("pid") int pid, @Param("merged") int merged, @Param("survivor") int survivor);
  @Update("UPDATE type_material SET reference_id = #{survivor} WHERE project_id = #{pid} AND reference_id = #{merged}") int repointRefTypeMaterial(@Param("pid") int pid, @Param("merged") int merged, @Param("survivor") int survivor);
  @Update("UPDATE vernacular    SET reference_id = #{survivor} WHERE project_id = #{pid} AND reference_id = #{merged}") int repointRefVernacular(@Param("pid") int pid, @Param("merged") int merged, @Param("survivor") int survivor);
  @Update("UPDATE distribution  SET reference_id = #{survivor} WHERE project_id = #{pid} AND reference_id = #{merged}") int repointRefDistribution(@Param("pid") int pid, @Param("merged") int merged, @Param("survivor") int survivor);
  @Update("UPDATE estimate      SET reference_id = #{survivor} WHERE project_id = #{pid} AND reference_id = #{merged}") int repointRefEstimate(@Param("pid") int pid, @Param("merged") int merged, @Param("survivor") int survivor);
  @Update("UPDATE property      SET reference_id = #{survivor} WHERE project_id = #{pid} AND reference_id = #{merged}") int repointRefProperty(@Param("pid") int pid, @Param("merged") int merged, @Param("survivor") int survivor);
  @Delete("DELETE FROM reference WHERE project_id = #{pid} AND id = #{id}")
  int deleteReference(@Param("pid") int pid, @Param("id") int id);
}
