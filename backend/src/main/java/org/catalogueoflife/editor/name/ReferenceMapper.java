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
public interface ReferenceMapper {

  // id is app-allocated (see IdSeqMapper) and set on `r` before calling insert -- the compound
  // (project_id, id) primary key means there is no DB identity/generated key to read back.
  // version and modified are left out of the insert column list so the DB defaults
  // (0 / now()) apply -- setting them explicitly to a null POJO value would insert
  // an explicit NULL and violate the NOT NULL constraint instead of using the default.
  @Insert("""
      INSERT INTO reference (project_id, id, alternative_id, citation, type, author, editor,
                              title, container_title, issued, volume, issue, page, publisher,
                              doi, isbn, issn, link, accessed, remarks, modified_by)
      VALUES (#{projectId}, #{id}, #{alternativeId,typeHandler=org.catalogueoflife.editor.name.StringArrayTypeHandler},
              #{citation}, #{type}, #{author}, #{editor}, #{title}, #{containerTitle}, #{issued},
              #{volume}, #{issue}, #{page}, #{publisher}, #{doi}, #{isbn}, #{issn}, #{link},
              #{accessed}, #{remarks}, #{modifiedBy})
      """)
  void insert(Reference r);

  @Select("SELECT * FROM reference WHERE project_id = #{projectId} AND id = #{id}")
  @Results(id = "referenceResult", value = {
      @Result(property = "id", column = "id", id = true),
      @Result(property = "projectId", column = "project_id", id = true),
      @Result(property = "alternativeId", column = "alternative_id",
          typeHandler = StringArrayTypeHandler.class)
  })
  Reference findByIdInProject(@Param("projectId") int projectId, @Param("id") int id);

  @Select("""
      SELECT * FROM reference WHERE project_id = #{projectId}
      ORDER BY id LIMIT #{limit} OFFSET #{offset}
      """)
  @ResultMap("referenceResult")
  List<Reference> findByProject(@Param("projectId") int projectId, @Param("limit") int limit,
      @Param("offset") int offset);

  // Unpaginated: all of a project's references in one go, ORDER BY id -- for the ColDP export
  // (ReferenceColdpWriter), which needs every row rather than a UI page. Don't reuse
  // findByProject/LIMIT for this: a project with more references than any reasonable page size
  // would silently truncate the export.
  @Select("SELECT * FROM reference WHERE project_id = #{projectId} ORDER BY id")
  @ResultMap("referenceResult")
  List<Reference> findAllByProject(@Param("projectId") int projectId);

  @Select("""
      SELECT * FROM reference
      WHERE project_id = #{projectId} AND citation % #{q}
      ORDER BY similarity(citation, #{q}) DESC
      LIMIT #{limit} OFFSET #{offset}
      """)
  @ResultMap("referenceResult")
  List<Reference> search(@Param("projectId") int projectId, @Param("q") String q,
      @Param("limit") int limit, @Param("offset") int offset);

  @Delete("DELETE FROM reference WHERE project_id = #{projectId} AND id = #{id}")
  int delete(@Param("projectId") int projectId, @Param("id") int id);

  @Update("""
      UPDATE reference
      SET alternative_id = #{alternativeId,typeHandler=org.catalogueoflife.editor.name.StringArrayTypeHandler},
          citation = #{citation}, type = #{type}, author = #{author}, editor = #{editor},
          title = #{title}, container_title = #{containerTitle}, issued = #{issued},
          volume = #{volume}, issue = #{issue}, page = #{page}, publisher = #{publisher},
          doi = #{doi}, isbn = #{isbn}, issn = #{issn}, link = #{link}, accessed = #{accessed},
          remarks = #{remarks},
          modified = now(), modified_by = #{modifiedBy}, version = version + 1
      WHERE project_id = #{projectId} AND id = #{id} AND version = #{version}
      """)
  int update(Reference r);
}
