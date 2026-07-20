package org.catalogueoflife.editor.discussion;

import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.catalogueoflife.editor.audit.Change;

@Mapper
public interface DiscussionChangeMapper {

  @Select("SELECT count(*) > 0 FROM change WHERE id = #{changeId} AND project_id = #{projectId}")
  boolean changeInProject(@Param("projectId") int projectId, @Param("changeId") int changeId);

  @Insert("""
      INSERT INTO discussion_change (project_id, discussion_id, change_id)
      VALUES (#{projectId}, #{discussionId}, #{changeId})
      ON CONFLICT DO NOTHING
      """)
  void link(@Param("projectId") int projectId, @Param("discussionId") int discussionId,
      @Param("changeId") int changeId);

  @Delete("""
      DELETE FROM discussion_change
      WHERE project_id = #{projectId} AND discussion_id = #{discussionId} AND change_id = #{changeId}
      """)
  void unlink(@Param("projectId") int projectId, @Param("discussionId") int discussionId,
      @Param("changeId") int changeId);

  // The full changelog rows linked to a discussion (newest first). map-underscore-to-camel-case maps
  // change columns onto Change; the join adds the acting user's name.
  @Select("""
      SELECT c.*, u.username
      FROM discussion_change dc
      JOIN change c ON c.id = dc.change_id
      LEFT JOIN app_user u ON u.id = c.user_id
      WHERE dc.project_id = #{projectId} AND dc.discussion_id = #{discussionId}
      ORDER BY c.at DESC, c.id DESC
      """)
  List<Change> findChanges(@Param("projectId") int projectId, @Param("discussionId") int discussionId);
}
