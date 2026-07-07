package org.catalogueoflife.editor.name;

import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SynonymAcceptedMapper {

  @Insert("""
      INSERT INTO synonym_accepted (synonym_usage_id, accepted_usage_id, ordinal)
      VALUES (#{s}, #{a}, #{o})
      ON CONFLICT DO NOTHING
      """)
  void link(@Param("s") long synonymUsageId, @Param("a") long acceptedUsageId, @Param("o") Integer ordinal);

  @Delete("""
      DELETE FROM synonym_accepted
      WHERE synonym_usage_id = #{s} AND accepted_usage_id = #{a}
      """)
  int unlink(@Param("s") long synonymUsageId, @Param("a") long acceptedUsageId);

  @Select("""
      SELECT accepted_usage_id FROM synonym_accepted
      WHERE synonym_usage_id = #{synonymUsageId}
      ORDER BY ordinal
      """)
  List<Long> findAcceptedFor(long synonymUsageId);

  @Select("""
      SELECT synonym_usage_id FROM synonym_accepted
      WHERE accepted_usage_id = #{acceptedUsageId}
      ORDER BY ordinal
      """)
  List<Long> findSynonymsOf(long acceptedUsageId);
}
