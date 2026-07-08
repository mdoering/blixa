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

  @Insert("""
      INSERT INTO synonym_accepted (project_id, synonym_id, accepted_id, ordinal)
      VALUES (#{projectId}, #{s}, #{a}, #{o})
      ON CONFLICT DO NOTHING
      """)
  void link(@Param("projectId") int projectId, @Param("s") int synonymId, @Param("a") int acceptedId,
      @Param("o") Integer ordinal);

  @Delete("""
      DELETE FROM synonym_accepted
      WHERE project_id = #{projectId} AND synonym_id = #{s} AND accepted_id = #{a}
      """)
  int unlink(@Param("projectId") int projectId, @Param("s") int synonymId, @Param("a") int acceptedId);

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
}
