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
import org.catalogueoflife.editor.name.dto.RankName;

@Mapper
public interface NameUsageMapper {

  // id is app-allocated (see IdSeqMapper) and set on `u` before calling insert -- the compound
  // (project_id, id) primary key means there is no DB identity/generated key to read back.
  // version and modified are left out of the insert column list so the DB defaults
  // (0 / now()) apply -- setting them explicitly to a null POJO value would insert
  // an explicit NULL and violate the NOT NULL constraint instead of using the default.
  @Insert("""
      INSERT INTO name_usage (
          project_id, id, alternative_id, parent_id, basionym_id, ordinal,
          status, name_phrase, reference_id,
          scientific_name, authorship, rank, uninomial, genus, infrageneric_epithet,
          specific_epithet, infraspecific_epithet, cultivar_epithet, notho,
          combination_authorship, combination_ex_authorship, combination_authorship_year,
          basionym_authorship, basionym_ex_authorship, basionym_authorship_year,
          sanctioning_author, nom_status, published_in_reference_id, published_in_year,
          published_in_page, published_in_page_link, gender, etymology, name_type,
          parse_state, remarks, modified_by)
      VALUES (
          #{projectId}, #{id},
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
          #{parseState}, #{remarks}, #{modifiedBy})
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

  // The same full-row projection findByIdInProject uses (including the taxon_info LEFT JOIN), but
  // for every usage in the project rather than a single id, id-ordered for a stable/deterministic
  // file -- ColDP export's NameUsage.tsv source (coldp/export/NameUsageColdpWriter.write).
  @Select("""
      SELECT nu.*, ti.extinct, ti.environment,
             ti.temporal_range_start, ti.temporal_range_end
      FROM name_usage nu
      LEFT JOIN taxon_info ti ON ti.project_id = nu.project_id AND ti.usage_id = nu.id
      WHERE nu.project_id = #{projectId}
      ORDER BY nu.id
      """)
  @ResultMap("nameUsageResult")
  List<NameUsage> findAllByProject(@Param("projectId") int projectId);

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

  // Same query as findIdsByProject, kept as its own named entry point for col/ColMatchJobService.
  // runSync (the bulk COL-match job's per-usage loop) so the two call sites -- revalidation vs.
  // COL-matching -- can evolve independently (e.g. a later col-match pass excluding bare/unranked
  // names) without one accidentally changing the other's scope.
  @Select("SELECT id FROM name_usage WHERE project_id = #{projectId} ORDER BY id")
  List<Integer> findAllIds(@Param("projectId") int projectId);

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

  // Full higher classification of a usage: every STRICT ancestor (depth > 0, self excluded),
  // root-first (ORDER BY depth DESC), skipping unranked ancestors -- fed to the COL name matcher
  // as higher-classification query params (see Task 4 / the bulk-match plan). Same
  // `anc.depth < 10000` cycle guard as the other ancestor CTEs in this file (and
  // TreeMapper.findPath): a defensive statement-timeout/DoS bound in case a cycle ever slips past
  // the write-time guards (TreeMapper.isDescendant) -- it does not change behavior for any valid
  // (acyclic) tree.
  @Select("""
      WITH RECURSIVE anc AS (
        SELECT parent_id, rank, scientific_name, 0 AS depth
          FROM name_usage WHERE project_id = #{projectId} AND id = #{usageId}
        UNION ALL
        SELECT p.parent_id, p.rank, p.scientific_name, anc.depth + 1
          FROM name_usage p JOIN anc ON p.project_id = #{projectId} AND p.id = anc.parent_id
        WHERE anc.depth < 10000
      )
      SELECT rank, scientific_name AS name FROM anc
      WHERE depth > 0 AND rank IS NOT NULL
      ORDER BY depth DESC
      """)
  List<RankName> findClassification(@Param("projectId") int projectId, @Param("usageId") int usageId);

  // The immediate parent's rank (RankVsParentRule), or null if the id has no parent.
  @Select("""
      SELECT p.rank FROM name_usage c JOIN name_usage p
        ON p.project_id = c.project_id AND p.id = c.parent_id
      WHERE c.project_id = #{projectId} AND c.id = #{id}
      """)
  String findParentRank(@Param("projectId") int projectId, @Param("id") int id);

  // publishedInYear of the nearest STRICT genus ancestor (GenusYearAfterSpeciesRule), or null.
  @Select("""
      WITH RECURSIVE anc AS (
        SELECT project_id, id, parent_id, rank, published_in_year, 0 AS depth
        FROM name_usage WHERE project_id = #{projectId} AND id = #{id}
        UNION ALL
        SELECT n.project_id, n.id, n.parent_id, n.rank, n.published_in_year, anc.depth + 1
        FROM name_usage n JOIN anc ON n.project_id = anc.project_id AND n.id = anc.parent_id
        WHERE anc.depth < 10000
      )
      SELECT published_in_year FROM anc WHERE depth > 0 AND rank = 'genus' ORDER BY depth LIMIT 1
      """)
  Integer findAncestorGenusYear(@Param("projectId") int projectId, @Param("id") int id);

  // specific_epithet of the nearest STRICT species ancestor (SpeciesEpithetMismatchRule), or null.
  @Select("""
      WITH RECURSIVE anc AS (
        SELECT project_id, id, parent_id, rank, specific_epithet, 0 AS depth
        FROM name_usage WHERE project_id = #{projectId} AND id = #{id}
        UNION ALL
        SELECT n.project_id, n.id, n.parent_id, n.rank, n.specific_epithet, anc.depth + 1
        FROM name_usage n JOIN anc ON n.project_id = anc.project_id AND n.id = anc.parent_id
        WHERE anc.depth < 10000
      )
      SELECT specific_epithet FROM anc WHERE depth > 0 AND rank = 'species' ORDER BY depth LIMIT 1
      """)
  String findAncestorSpeciesEpithet(@Param("projectId") int projectId, @Param("id") int id);

  // How many of a synonym's accepted targets are NOT actually accepted (SynonymOfNonAcceptedRule).
  @Select("""
      SELECT COUNT(*) FROM synonym_accepted sa JOIN name_usage a
        ON a.project_id = sa.project_id AND a.id = sa.accepted_id
      WHERE sa.project_id = #{projectId} AND sa.synonym_id = #{synonymId} AND a.status <> 'ACCEPTED'
      """)
  int countNonAcceptedSynonymTargets(@Param("projectId") int projectId, @Param("synonymId") int synonymId);

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
      SET alternative_id = #{alternativeId,typeHandler=org.catalogueoflife.editor.name.StringArrayTypeHandler},
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
          parse_state = #{parseState}, remarks = #{remarks},
          modified = now(), modified_by = #{modifiedBy}, version = version + 1
      WHERE project_id = #{projectId} AND id = #{id} AND version = #{version}
      """)
  int update(NameUsage u);

  // Narrow CAS write of just alternative_id (PUT /usages/{id}/identifiers): the "match to COL"
  // write path stores col:<id> here (see NameUsageService.mergeColId) without touching any of the
  // name/nomenclatural fields the full update() above rewrites. Bumps version/modified like every
  // other name_usage write; 0 rows updated -> caller treats as a stale-version 409.
  @Update("""
      UPDATE name_usage SET alternative_id = #{alternativeId,typeHandler=org.catalogueoflife.editor.name.StringArrayTypeHandler},
          modified = now(), modified_by = #{modifiedBy}, version = version + 1
      WHERE project_id = #{projectId} AND id = #{id} AND version = #{version}
      """)
  int updateAlternativeId(@Param("projectId") int projectId, @Param("id") int id,
      @Param("alternativeId") List<String> alternativeId,
      @Param("modifiedBy") int modifiedBy, @Param("version") Integer version);
}
