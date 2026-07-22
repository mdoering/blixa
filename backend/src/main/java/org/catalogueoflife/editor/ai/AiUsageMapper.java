package org.catalogueoflife.editor.ai;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AiUsageMapper {

  @Insert("""
      INSERT INTO ai_usage (project_id, usage_id, user_id, provider, model, input_tokens, output_tokens)
      VALUES (#{projectId}, #{usageId}, #{userId}, #{provider}, #{model}, #{inputTokens}, #{outputTokens})
      """)
  void insert(@Param("projectId") int projectId, @Param("usageId") Integer usageId,
      @Param("userId") Integer userId, @Param("provider") String provider,
      @Param("model") String model, @Param("inputTokens") int inputTokens,
      @Param("outputTokens") int outputTokens);

  // Per-project run count (drives the "AI usage per project" total; the IT asserts a run is recorded).
  @Select("SELECT count(*) FROM ai_usage WHERE project_id = #{projectId}")
  int countForProject(@Param("projectId") int projectId);
}
