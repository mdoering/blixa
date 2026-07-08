package org.catalogueoflife.editor.name;

import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AuthorMapper {

  // id is app-allocated (see IdSeqMapper) and set on `a` before calling insert -- the compound
  // (project_id, id) primary key means there is no DB identity/generated key to read back.
  // version and modified are left out of the insert column list so the DB defaults
  // (0 / now()) apply -- setting them explicitly to a null POJO value would insert
  // an explicit NULL and violate the NOT NULL constraint instead of using the default.
  @Insert("""
      INSERT INTO author (project_id, id, coldp_id, alternative_id, given, family, suffix,
                           abbreviation_botany, affiliation, birth, death, birth_place, country,
                           link, remarks, modified_by)
      VALUES (#{projectId}, #{id}, #{coldpId}, #{alternativeId,typeHandler=org.catalogueoflife.editor.name.StringArrayTypeHandler},
              #{given}, #{family}, #{suffix}, #{abbreviationBotany}, #{affiliation}, #{birth},
              #{death}, #{birthPlace}, #{country}, #{link}, #{remarks}, #{modifiedBy})
      """)
  void insert(Author a);

  @Select("SELECT * FROM author WHERE project_id = #{projectId} AND id = #{id}")
  @Results(id = "authorResult", value = {
      @Result(property = "id", column = "id", id = true),
      @Result(property = "projectId", column = "project_id", id = true),
      @Result(property = "alternativeId", column = "alternative_id",
          typeHandler = StringArrayTypeHandler.class)
  })
  Author findByIdInProject(@Param("projectId") int projectId, @Param("id") int id);

  @Select("SELECT * FROM author WHERE project_id = #{projectId} ORDER BY id")
  @ResultMap("authorResult")
  List<Author> findByProject(@Param("projectId") int projectId);

  @Delete("DELETE FROM author WHERE project_id = #{projectId} AND id = #{id}")
  int delete(@Param("projectId") int projectId, @Param("id") int id);

  @Update("""
      UPDATE author
      SET coldp_id = #{coldpId},
          alternative_id = #{alternativeId,typeHandler=org.catalogueoflife.editor.name.StringArrayTypeHandler},
          given = #{given}, family = #{family}, suffix = #{suffix},
          abbreviation_botany = #{abbreviationBotany}, affiliation = #{affiliation},
          birth = #{birth}, death = #{death}, birth_place = #{birthPlace}, country = #{country},
          link = #{link}, remarks = #{remarks}, modified = now(), modified_by = #{modifiedBy},
          version = version + 1
      WHERE project_id = #{projectId} AND id = #{id} AND version = #{version}
      """)
  int update(Author a);
}
