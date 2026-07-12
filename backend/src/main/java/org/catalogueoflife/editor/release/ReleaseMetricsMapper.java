package org.catalogueoflife.editor.release;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ReleaseMetricsMapper {

  // rank -> count for a given status set. Returns rows {rank, cnt}; the service folds to a map.
  @Select({"<script>",
      "SELECT rank AS rank, count(*) AS cnt FROM name_usage",
      "WHERE project_id = #{projectId} AND status IN",
      "<foreach item='s' collection='statuses' open='(' separator=',' close=')'>#{s}</foreach>",
      "GROUP BY rank",
      "</script>"})
  List<Map<String, Object>> countByRank(@Param("projectId") int projectId,
      @Param("statuses") List<String> statuses);

  @Select("SELECT count(*) FROM vernacular   WHERE project_id = #{projectId}") int vernacular(@Param("projectId") int p);
  @Select("SELECT count(*) FROM distribution WHERE project_id = #{projectId}") int distribution(@Param("projectId") int p);
  @Select("SELECT count(*) FROM media        WHERE project_id = #{projectId}") int media(@Param("projectId") int p);
  @Select("SELECT count(*) FROM type_material WHERE project_id = #{projectId}") int typeMaterial(@Param("projectId") int p);
  @Select("SELECT count(*) FROM name_relation WHERE project_id = #{projectId}") int nameRelation(@Param("projectId") int p);
  @Select("SELECT count(*) FROM property     WHERE project_id = #{projectId}") int property(@Param("projectId") int p);
  @Select("SELECT count(*) FROM estimate     WHERE project_id = #{projectId}") int estimate(@Param("projectId") int p);
  @Select("SELECT count(*) FROM reference    WHERE project_id = #{projectId}") int reference(@Param("projectId") int p);

  // The explicit ::timestamptz cast on #{since} is required: without it, a null parameter (the
  // first-release case) leaves Postgres unable to infer the placeholder's type ("could not
  // determine data type of parameter $2") because MyBatis binds an untyped null (JdbcType.OTHER)
  // for OffsetDateTime -- the cast tells Postgres the type regardless of nullness.
  @Select("SELECT count(*) FROM change WHERE project_id = #{projectId} "
      + "AND (#{since}::timestamptz IS NULL OR at > #{since}::timestamptz)")
  int changesSince(@Param("projectId") int projectId, @Param("since") OffsetDateTime since);

  // user_id, name (display_name -> username fallback), orcid, count — since the boundary.
  @Select("SELECT c.user_id AS \"userId\", coalesce(u.display_name, u.username) AS name, u.orcid AS orcid, "
      + "count(*) AS cnt FROM change c LEFT JOIN app_user u ON u.id = c.user_id "
      + "WHERE c.project_id = #{projectId} AND (#{since}::timestamptz IS NULL OR c.at > #{since}::timestamptz) "
      + "GROUP BY c.user_id, u.display_name, u.username, u.orcid ORDER BY count(*) DESC")
  List<Map<String, Object>> contributionsSince(@Param("projectId") int projectId,
      @Param("since") OffsetDateTime since);
}
