package org.catalogueoflife.editor.discussion;

import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DiscussionFollowMapper {

  @Insert("""
      INSERT INTO discussion_follow (project_id, discussion_id, user_id)
      VALUES (#{projectId}, #{discussionId}, #{userId})
      ON CONFLICT DO NOTHING
      """)
  void follow(@Param("projectId") int projectId, @Param("discussionId") int discussionId,
      @Param("userId") int userId);

  @Delete("""
      DELETE FROM discussion_follow
      WHERE project_id = #{projectId} AND discussion_id = #{discussionId} AND user_id = #{userId}
      """)
  void unfollow(@Param("projectId") int projectId, @Param("discussionId") int discussionId,
      @Param("userId") int userId);

  @Select("""
      SELECT count(*) > 0 FROM discussion_follow
      WHERE project_id = #{projectId} AND discussion_id = #{discussionId} AND user_id = #{userId}
      """)
  boolean isFollowing(@Param("projectId") int projectId, @Param("discussionId") int discussionId,
      @Param("userId") int userId);

  @Select("SELECT count(*) FROM discussion_follow WHERE project_id = #{projectId} AND discussion_id = #{discussionId}")
  int countFollowers(@Param("projectId") int projectId, @Param("discussionId") int discussionId);

  @Select("SELECT user_id FROM discussion_follow WHERE project_id = #{projectId} AND discussion_id = #{discussionId}")
  List<Integer> followerIds(@Param("projectId") int projectId, @Param("discussionId") int discussionId);
}
