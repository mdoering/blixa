package org.catalogueoflife.editor.release;

import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ReleaseMapper {

  @Insert("INSERT INTO release (project_id, version, notes, status, created_by) "
      + "VALUES (#{projectId}, #{version}, #{notes}, 'BUILDING', #{createdBy})")
  @Options(useGeneratedKeys = true, keyProperty = "id")
  void insertBuilding(Release r);

  @Update("UPDATE release SET status = 'READY', name_usage_count = #{nameUsageCount}, "
      + "metrics = #{metrics}::jsonb, file_path = #{filePath}, file_name = #{fileName}, "
      + "file_size = #{fileSize} WHERE id = #{id} AND status = 'BUILDING'")
  int ready(@Param("id") int id, @Param("nameUsageCount") int nameUsageCount,
      @Param("metrics") String metrics, @Param("filePath") String filePath,
      @Param("fileName") String fileName, @Param("fileSize") long fileSize);

  @Update("UPDATE release SET status = 'FAILED', error = #{error} WHERE id = #{id} AND status = 'BUILDING'")
  int fail(@Param("id") int id, @Param("error") String error);

  @Select("SELECT * FROM release WHERE id = #{id}")
  Release findById(@Param("id") int id);

  @Select("SELECT * FROM release WHERE project_id = #{projectId} ORDER BY created_at DESC, id DESC")
  List<Release> findByProject(@Param("projectId") int projectId);

  // Latest READY release of a project (for the previous-release metrics boundary + public list/page).
  @Select("SELECT * FROM release WHERE project_id = #{projectId} AND status = 'READY' "
      + "ORDER BY created_at DESC, id DESC LIMIT 1")
  Release findLatestReady(@Param("projectId") int projectId);

  // The created_at of the previous READY release (metrics boundary); null if none yet.
  @Select("SELECT max(created_at) FROM release WHERE project_id = #{projectId} AND status = 'READY'")
  java.time.OffsetDateTime latestReadyCreatedAt(@Param("projectId") int projectId);

  @Select("SELECT * FROM release WHERE project_id = #{projectId} AND status = 'READY' "
      + "ORDER BY created_at DESC, id DESC")
  List<Release> findReadyByProject(@Param("projectId") int projectId);

  @Delete("DELETE FROM release WHERE id = #{id}")
  void delete(@Param("id") int id);

  @Update("UPDATE release SET status = 'FAILED', error = 'interrupted by restart' WHERE status = 'BUILDING'")
  int failStaleBuilding();
}
