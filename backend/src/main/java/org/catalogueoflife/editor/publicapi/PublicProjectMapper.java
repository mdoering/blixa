package org.catalogueoflife.editor.publicapi;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.catalogueoflife.editor.project.Project;

@Mapper
public interface PublicProjectMapper {

  @Select("SELECT * FROM project WHERE is_public = true ORDER BY title")
  @Results(id = "pubProject", value = {
      @Result(property = "identifierScopes", column = "identifier_scopes",
          typeHandler = org.catalogueoflife.editor.project.IdentifierScopeListTypeHandler.class)
  })
  List<Project> findPublic();

  @Select("SELECT * FROM project WHERE id = #{id} AND is_public = true")
  @org.apache.ibatis.annotations.ResultMap("pubProject")
  Project findPublicById(@Param("id") int id);

  @Select("SELECT * FROM project WHERE alias = #{alias} AND is_public = true ORDER BY id LIMIT 1")
  @org.apache.ibatis.annotations.ResultMap("pubProject")
  Project findPublicByAlias(@Param("alias") String alias);
}
