package org.catalogueoflife.editor.child;

import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.catalogueoflife.editor.child.dto.MediaRequest;
import org.catalogueoflife.editor.child.dto.MediaResponse;

@Mapper
public interface MediaMapper {

  String SELECT = """
      SELECT id, usage_id, url, type, title, creator, license, link, remarks, version
      FROM media
      """;

  @Select(SELECT + " WHERE project_id = #{projectId} AND usage_id = #{usageId} ORDER BY id")
  List<MediaResponse> findByUsage(@Param("projectId") int projectId, @Param("usageId") int usageId);

  @Select(SELECT + " WHERE project_id = #{projectId} ORDER BY id")
  List<MediaResponse> findByProject(@Param("projectId") int projectId);

  @Select(SELECT + " WHERE project_id = #{projectId} AND id = #{id}")
  MediaResponse findById(@Param("projectId") int projectId, @Param("id") int id);

  @Insert("""
      INSERT INTO media (project_id, id, usage_id, url, type, title, creator, license, link,
          remarks, modified_by)
      VALUES (#{projectId}, #{id}, #{usageId}, #{r.url}, #{r.type}, #{r.title}, #{r.creator},
          #{r.license}, #{r.link}, #{r.remarks}, #{modifiedBy})
      """)
  void insert(@Param("projectId") int projectId, @Param("id") int id, @Param("usageId") int usageId,
      @Param("r") MediaRequest r, @Param("modifiedBy") int modifiedBy);

  @Update("""
      UPDATE media SET url = #{r.url}, type = #{r.type}, title = #{r.title}, creator = #{r.creator},
          license = #{r.license}, link = #{r.link}, remarks = #{r.remarks},
          modified = now(), modified_by = #{modifiedBy}, version = version + 1
      WHERE project_id = #{projectId} AND id = #{id} AND version = #{r.version}
      """)
  int update(@Param("projectId") int projectId, @Param("id") int id,
      @Param("r") MediaRequest r, @Param("modifiedBy") int modifiedBy);

  @Delete("DELETE FROM media WHERE project_id = #{projectId} AND id = #{id}")
  int delete(@Param("projectId") int projectId, @Param("id") int id);
}
