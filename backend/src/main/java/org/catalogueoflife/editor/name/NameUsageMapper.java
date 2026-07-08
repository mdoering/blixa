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
          status, name_phrase, reference_id, extinct, environment,
          temporal_range_start, temporal_range_end,
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
          #{extinct},
          #{environment,typeHandler=org.catalogueoflife.editor.name.EnvironmentArrayTypeHandler},
          #{temporalRangeStart}, #{temporalRangeEnd},
          #{scientificName}, #{authorship}, #{rank}, #{uninomial}, #{genus}, #{infragenericEpithet},
          #{specificEpithet}, #{infraspecificEpithet}, #{cultivarEpithet}, #{notho},
          #{combinationAuthorship}, #{combinationExAuthorship}, #{combinationAuthorshipYear},
          #{basionymAuthorship}, #{basionymExAuthorship}, #{basionymAuthorshipYear},
          #{sanctioningAuthor}, #{nomStatus}, #{publishedInReferenceId}, #{publishedInYear},
          #{publishedInPage}, #{publishedInPageLink}, #{gender}, #{etymology}, #{nameType},
          #{parseState}, #{link}, #{remarks}, #{modifiedBy})
      """)
  void insert(NameUsage u);

  @Select("SELECT * FROM name_usage WHERE project_id = #{projectId} AND id = #{id}")
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

  @Select("""
      SELECT * FROM name_usage
      WHERE project_id = #{projectId}
      ORDER BY id
      LIMIT #{limit} OFFSET #{offset}
      """)
  @ResultMap("nameUsageResult")
  List<NameUsage> findByProject(@Param("projectId") int projectId, @Param("limit") int limit,
      @Param("offset") int offset);

  @Select("""
      SELECT * FROM name_usage
      WHERE project_id = #{projectId} AND scientific_name % #{q}
      ORDER BY similarity(scientific_name, #{q}) DESC
      LIMIT #{limit} OFFSET #{offset}
      """)
  @ResultMap("nameUsageResult")
  List<NameUsage> search(@Param("projectId") int projectId, @Param("q") String q,
      @Param("limit") int limit, @Param("offset") int offset);

  @Delete("DELETE FROM name_usage WHERE project_id = #{projectId} AND id = #{id}")
  int delete(@Param("projectId") int projectId, @Param("id") int id);

  @Update("""
      UPDATE name_usage
      SET coldp_id = #{coldpId},
          alternative_id = #{alternativeId,typeHandler=org.catalogueoflife.editor.name.StringArrayTypeHandler},
          parent_id = #{parentId}, basionym_id = #{basionymId}, ordinal = #{ordinal},
          status = #{status}, name_phrase = #{namePhrase},
          reference_id = #{referenceId,typeHandler=org.catalogueoflife.editor.name.IntegerArrayTypeHandler},
          extinct = #{extinct},
          environment = #{environment,typeHandler=org.catalogueoflife.editor.name.EnvironmentArrayTypeHandler},
          temporal_range_start = #{temporalRangeStart}, temporal_range_end = #{temporalRangeEnd},
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
