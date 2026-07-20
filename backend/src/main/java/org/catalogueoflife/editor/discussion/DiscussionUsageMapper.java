package org.catalogueoflife.editor.discussion;

import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

// Reverse links between a name_usage and the discussions that mention it (#nameID). Reconciled
// wholesale per discussion by DiscussionLinkService.
@Mapper
public interface DiscussionUsageMapper {

  @Delete("DELETE FROM discussion_usage WHERE project_id = #{projectId} AND discussion_id = #{discussionId}")
  void clear(@Param("projectId") int projectId, @Param("discussionId") int discussionId);

  @Insert("""
      INSERT INTO discussion_usage (project_id, discussion_id, usage_id)
      VALUES (#{projectId}, #{discussionId}, #{usageId})
      ON CONFLICT DO NOTHING
      """)
  void link(@Param("projectId") int projectId, @Param("discussionId") int discussionId,
      @Param("usageId") int usageId);

  // Discussions that link to a usage (for the name -> discussions view), newest activity first.
  @Select("""
      SELECT d.*, COALESCE(u.display_name, u.username) AS author_name
      FROM discussion_usage du
      JOIN discussion d ON d.project_id = du.project_id AND d.id = du.discussion_id
      LEFT JOIN app_user u ON u.id = d.author_id
      WHERE du.project_id = #{projectId} AND du.usage_id = #{usageId}
      ORDER BY d.updated_at DESC, d.id DESC
      """)
  List<Discussion> findDiscussionsByUsage(@Param("projectId") int projectId,
      @Param("usageId") int usageId);
}
