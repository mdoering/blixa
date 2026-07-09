package org.catalogueoflife.editor.name;

import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface NameUsageMapper {

  // id is app-allocated (see IdSeqMapper) and set on `u` before calling insert -- the compound
  // (project_id, id) primary key means there is no DB identity/generated key to read back.
  // version and modified are left out of the insert column list so the DB defaults
  // (0 / now()) apply -- setting them explicitly to a null POJO value would insert
  // an explicit NULL and violate the NOT NULL constraint instead of using the default.
  @Insert("""
      INSERT INTO name_usage (
          project_id, id, coldp_id, alternative_id, parent_id, basionym_id, ordinal,
          status, name_phrase, reference_id,
          scientific_name, authorship, rank, uninomial, genus, infrageneric_epithet,
          specific_epithet, infraspecific_epithet, cultivar_epithet, notho,
          combination_authorship, combination_ex_authorship, combination_authorship_year,
          basionym_authorship, basionym_ex_authorship, basionym_authorship_year,
          sanctioning_author, nom_status, published_in_reference_id, published_in_year,
          published_in_page, published_in_page_link, gender, etymology, name_type,
          parse_state, link, remarks, modified_by)
      VALUES (
          #{projectId}, #{id}, #{coldpId},
          #{alternativeId,typeHandler=org.catalogueoflife.editor.name.StringArrayTypeHandler},
          #{parentId}, #{basionymId}, #{ordinal},
          #{status}, #{namePhrase},
          #{referenceId,typeHandler=org.catalogueoflife.editor.name.IntegerArrayTypeHandler},
          #{scientificName}, #{authorship}, #{rank}, #{uninomial}, #{genus}, #{infragenericEpithet},
          #{specificEpithet}, #{infraspecificEpithet}, #{cultivarEpithet}, #{notho},
          #{combinationAuthorship}, #{combinationExAuthorship}, #{combinationAuthorshipYear},
          #{basionymAuthorship}, #{basionymExAuthorship}, #{basionymAuthorshipYear},
          #{sanctioningAuthor}, #{nomStatus}, #{publishedInReferenceId}, #{publishedInYear},
          #{publishedInPage}, #{publishedInPageLink}, #{gender}, #{etymology}, #{nameType},
          #{parseState}, #{link}, #{remarks}, #{modifiedBy})
      """)
  void insert(NameUsage u);

  @Select("""
      SELECT nu.*, ti.extinct, ti.environment,
             ti.temporal_range_start, ti.temporal_range_end
      FROM name_usage nu
      LEFT JOIN taxon_info ti ON ti.project_id = nu.project_id AND ti.usage_id = nu.id
      WHERE nu.project_id = #{projectId} AND nu.id = #{id}
      """)
  @Results(id = "nameUsageResult", value = {
      @Result(property = "id", column = "id", id = true),
      @Result(property = "projectId", column = "project_id", id = true),
      @Result(property = "alternativeId", column = "alternative_id",
          typeHandler = StringArrayTypeHandler.class),
      @Result(property = "referenceId", column = "reference_id",
          typeHandler = IntegerArrayTypeHandler.class),
      @Result(property = "environment", column = "environment",
          typeHandler = EnvironmentArrayTypeHandler.class)
  })
  NameUsage findByIdInProject(@Param("projectId") int projectId, @Param("id") int id);

  // Unified list/search: q/rank/status are each optional (null -> unfiltered), ANDed together.
  // q is the existing pg_trgm fuzzy filter (scientific_name % q); rank/status are exact matches
  // against their STORED string form (rank: the name-parser Rank's lower-cased name(); status:
  // the Status enum's name()) -- NameUsageService normalizes caller-supplied filter values into
  // that form before calling this. Order is by similarity when q is present (best match first),
  // else alphabetical by scientificName -- matches countMatches' filter set exactly so `total`
  // (ignoring limit/offset) is always consistent with `items`.
  @Select("""
      <script>
      SELECT nu.*, ti.extinct, ti.environment,
             ti.temporal_range_start, ti.temporal_range_end
      FROM name_usage nu
      LEFT JOIN taxon_info ti ON ti.project_id = nu.project_id AND ti.usage_id = nu.id
      WHERE nu.project_id = #{projectId}
      <if test="q != null">AND nu.scientific_name % #{q}</if>
      <if test="rank != null">AND nu.rank = #{rank}</if>
      <if test="status != null">AND nu.status = #{status}</if>
      <choose>
        <when test="q != null">ORDER BY similarity(nu.scientific_name, #{q}) DESC</when>
        <otherwise>ORDER BY nu.scientific_name</otherwise>
      </choose>
      LIMIT #{limit} OFFSET #{offset}
      </script>
      """)
  @ResultMap("nameUsageResult")
  List<NameUsage> searchItems(@Param("projectId") int projectId, @Param("q") String q,
      @Param("rank") String rank, @Param("status") String status,
      @Param("limit") int limit, @Param("offset") int offset);

  // Same filter set as searchItems (q/rank/status), no limit/offset/order -- the total that drives
  // UsagePage.total independent of the page being fetched.
  @Select("""
      <script>
      SELECT COUNT(*) FROM name_usage
      WHERE project_id = #{projectId}
      <if test="q != null">AND scientific_name % #{q}</if>
      <if test="rank != null">AND rank = #{rank}</if>
      <if test="status != null">AND status = #{status}</if>
      </script>
      """)
  long countMatches(@Param("projectId") int projectId, @Param("q") String q,
      @Param("rank") String rank, @Param("status") String status);

  @Delete("DELETE FROM name_usage WHERE project_id = #{projectId} AND id = #{id}")
  int delete(@Param("projectId") int projectId, @Param("id") int id);

  // All usage ids in a project, in id order -- ValidationService.revalidateProject drives its
  // per-usage revalidateUsage loop off this (see validation/ValidationService.java).
  @Select("SELECT id FROM name_usage WHERE project_id = #{projectId} ORDER BY id")
  List<Integer> findIdsByProject(@Param("projectId") int projectId);

  // Count of OTHER usages in the project sharing the same scientificName + authorship --
  // validation/rules/DuplicateNameRule.java's data source. `IS NOT DISTINCT FROM` is Postgres's
  // NULL-safe equality (plain `=` is never true when either side is NULL, which would wrongly
  // treat two authorless duplicates as non-duplicates).
  @Select("""
      SELECT COUNT(*) FROM name_usage
      WHERE project_id = #{projectId} AND scientific_name = #{scientificName}
        AND authorship IS NOT DISTINCT FROM #{authorship}
        AND id != #{excludeId}
      """)
  int countDuplicates(@Param("projectId") int projectId, @Param("scientificName") String scientificName,
      @Param("authorship") String authorship, @Param("excludeId") int excludeId);

  // Every usage in the project citing a given reference as its published_in -- when the reference
  // changes, these are the usages whose year_vs_reference finding may now read differently
  // (validation/ValidationTrigger fires one ValidationEvent per id this returns; see
  // ReferenceService.update).
  @Select("SELECT id FROM name_usage WHERE project_id = #{projectId} AND published_in_reference_id = #{refId}")
  List<Integer> findIdsByPublishedInReference(@Param("projectId") int projectId, @Param("refId") int refId);

  // Direct children of a usage (parent_id = id). The tree only ever links accepted->accepted, so
  // these are the accepted children the demote workflow (NameUsageService.demote) must reassign
  // when their parent is turned into a synonym.
  @Select("SELECT id FROM name_usage WHERE project_id = #{projectId} AND parent_id = #{parentId}")
  List<Integer> findChildIds(@Param("projectId") int projectId, @Param("parentId") int parentId);

  // Scientific name of the nearest STRICT ancestor of rank genus (depth > 0 skips the node itself),
  // or null if there is none -- the classification genus that GenusMismatchRule compares a usage's
  // parsed genus token against. The depth bound is the same defensive cycle guard as TreeMapper's.
  @Select("""
      WITH RECURSIVE anc AS (
        SELECT project_id, id, parent_id, rank, scientific_name, 0 AS depth
        FROM name_usage WHERE project_id = #{projectId} AND id = #{id}
        UNION ALL
        SELECT n.project_id, n.id, n.parent_id, n.rank, n.scientific_name, anc.depth + 1
        FROM name_usage n JOIN anc ON n.project_id = anc.project_id AND n.id = anc.parent_id
        WHERE anc.depth < 10000
      )
      SELECT scientific_name FROM anc WHERE depth > 0 AND rank = 'genus' ORDER BY depth LIMIT 1
      """)
  String findAncestorGenusName(@Param("projectId") int projectId, @Param("id") int id);

  // Bulk reparent every direct child of oldParentId onto newParentId (which may be null = root).
  // Used by demote: a server-orchestrated move of a whole child set under the project advisory lock,
  // so unlike the single-node reparent it carries no per-child optimistic version -- it still bumps
  // version/modified so any client holding a stale child conflicts on its next own write.
  @Update("""
      UPDATE name_usage
      SET parent_id = #{newParentId}, version = version + 1, modified = now(), modified_by = #{modifiedBy}
      WHERE project_id = #{projectId} AND parent_id = #{oldParentId}
      """)
  int reparentChildren(@Param("projectId") int projectId, @Param("oldParentId") int oldParentId,
      @Param("newParentId") Integer newParentId, @Param("modifiedBy") int modifiedBy);

  // Set just the status of a usage (+ bump version/modified). Used by demote's synonymsTo=unassessed
  // branch to detach a now-orphaned synonym; the status value is the Status enum's name().
  @Update("""
      UPDATE name_usage
      SET status = #{status}, version = version + 1, modified = now(), modified_by = #{modifiedBy}
      WHERE project_id = #{projectId} AND id = #{id}
      """)
  int setStatus(@Param("projectId") int projectId, @Param("id") int id,
      @Param("status") String status, @Param("modifiedBy") int modifiedBy);

  @Update("""
      UPDATE name_usage
      SET coldp_id = #{coldpId},
          alternative_id = #{alternativeId,typeHandler=org.catalogueoflife.editor.name.StringArrayTypeHandler},
          parent_id = #{parentId}, basionym_id = #{basionymId}, ordinal = #{ordinal},
          status = #{status}, name_phrase = #{namePhrase},
          reference_id = #{referenceId,typeHandler=org.catalogueoflife.editor.name.IntegerArrayTypeHandler},
          scientific_name = #{scientificName}, authorship = #{authorship}, rank = #{rank},
          uninomial = #{uninomial}, genus = #{genus}, infrageneric_epithet = #{infragenericEpithet},
          specific_epithet = #{specificEpithet}, infraspecific_epithet = #{infraspecificEpithet},
          cultivar_epithet = #{cultivarEpithet}, notho = #{notho},
          combination_authorship = #{combinationAuthorship},
          combination_ex_authorship = #{combinationExAuthorship},
          combination_authorship_year = #{combinationAuthorshipYear},
          basionym_authorship = #{basionymAuthorship},
          basionym_ex_authorship = #{basionymExAuthorship},
          basionym_authorship_year = #{basionymAuthorshipYear},
          sanctioning_author = #{sanctioningAuthor}, nom_status = #{nomStatus},
          published_in_reference_id = #{publishedInReferenceId}, published_in_year = #{publishedInYear},
          published_in_page = #{publishedInPage}, published_in_page_link = #{publishedInPageLink},
          gender = #{gender}, etymology = #{etymology}, name_type = #{nameType},
          parse_state = #{parseState}, link = #{link}, remarks = #{remarks},
          modified = now(), modified_by = #{modifiedBy}, version = version + 1
      WHERE project_id = #{projectId} AND id = #{id} AND version = #{version}
      """)
  int update(NameUsage u);
}
