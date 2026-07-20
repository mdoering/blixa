package org.catalogueoflife.editor.name;

import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

// synonym_accepted links two name_usage rows WITHIN the same project (see the compound
// (project_id, synonym_id/accepted_id) foreign keys in V3__name_core.sql); every method takes
// projectId alongside the usage ids so a link/lookup can never cross project boundaries.
@Mapper
public interface SynonymAcceptedMapper {

  // Returns the affected row count (0 if the pair was already linked, thanks to ON CONFLICT DO
  // NOTHING) so callers -- see NameUsageService.linkSynonym -- can tell a real new link apart
  // from a no-op re-link, e.g. to avoid auditing a change that didn't actually happen.
  @Insert("""
      INSERT INTO synonym_accepted (project_id, synonym_id, accepted_id, ordinal)
      VALUES (#{projectId}, #{s}, #{a}, #{o})
      ON CONFLICT DO NOTHING
      """)
  int link(@Param("projectId") int projectId, @Param("s") int synonymId, @Param("a") int acceptedId,
      @Param("o") Integer ordinal);

  @Delete("""
      DELETE FROM synonym_accepted
      WHERE project_id = #{projectId} AND synonym_id = #{s} AND accepted_id = #{a}
      """)
  int unlink(@Param("projectId") int projectId, @Param("s") int synonymId, @Param("a") int acceptedId);

  // Drop every link from a synonym usage to its accepted target(s). Used when promoting that usage
  // to accepted (NameUsageService.promote): it is no longer a synonym of anything.
  @Delete("DELETE FROM synonym_accepted WHERE project_id = #{projectId} AND synonym_id = #{synonymId}")
  int deleteBySynonym(@Param("projectId") int projectId, @Param("synonymId") int synonymId);

  @Select("""
      SELECT accepted_id FROM synonym_accepted
      WHERE project_id = #{projectId} AND synonym_id = #{synonymId}
      ORDER BY ordinal
      """)
  List<Integer> findAcceptedFor(@Param("projectId") int projectId, @Param("synonymId") int synonymId);

  @Select("""
      SELECT synonym_id FROM synonym_accepted
      WHERE project_id = #{projectId} AND accepted_id = #{acceptedId}
      ORDER BY ordinal
      """)
  List<Integer> findSynonymsOf(@Param("projectId") int projectId, @Param("acceptedId") int acceptedId);

  // Distinct synonym ids linked to ANY of the given accepted usages -- for the WITH_SYNONYMS /
  // SUBTREE deletes (a synonym linked pro-parte to an accepted outside the set is still removed).
  @Select("""
      <script>
      SELECT DISTINCT synonym_id FROM synonym_accepted
      WHERE project_id = #{projectId} AND accepted_id IN
      <foreach collection='acceptedIds' item='a' open='(' separator=',' close=')'>#{a}</foreach>
      </script>
      """)
  List<Integer> synonymIdsForAccepted(@Param("projectId") int projectId,
      @Param("acceptedIds") List<Integer> acceptedIds);

  // Every (synonym_id, accepted_id) link in the project, ordered by synonym_id then accepted_id --
  // coldp/export/NameUsageColdpWriter groups these per synonym (ascending by acceptedId, the
  // group's natural order here) to place the primary NameUsage.tsv row on the lowest accepted id
  // and derive a "<synonymId>-<acceptedId>" row for each additional pro-parte target, without an
  // N+1 per-synonym findAcceptedFor call. Record properties are matched to columns by MyBatis's
  // automatic record support (map-underscore-to-camel-case), same as name/dto/RankName.
  @Select("""
      SELECT synonym_id, accepted_id FROM synonym_accepted
      WHERE project_id = #{projectId}
      ORDER BY synonym_id, accepted_id
      """)
  List<SynAccLink> findAllLinks(@Param("projectId") int projectId);

  // How many accepted usages a synonym is linked to -- validation/rules/SynonymWithoutAcceptedRule
  // fires when this is 0 for a SYNONYM/MISAPPLIED usage (see RuleContext.synonymAcceptedCount()).
  @Select("""
      SELECT COUNT(*) FROM synonym_accepted
      WHERE project_id = #{projectId} AND synonym_id = #{synonymId}
      """)
  int countBySynonym(@Param("projectId") int projectId, @Param("synonymId") int synonymId);
}
