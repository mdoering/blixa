package org.catalogueoflife.editor.discussion;

import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface DiscussionCommentMapper {

  @Insert("""
      INSERT INTO discussion_comment (project_id, id, discussion_id, body, author_id, author_orcid)
      VALUES (#{projectId}, #{id}, #{discussionId}, #{body}, #{authorId}, #{authorOrcid})
      """)
  void insert(DiscussionComment c);

  @Select("""
      SELECT c.*, COALESCE(u.display_name, u.username) AS author_name
      FROM discussion_comment c LEFT JOIN app_user u ON u.id = c.author_id
      WHERE c.project_id = #{projectId} AND c.id = #{id}
      """)
  DiscussionComment findByIdInProject(@Param("projectId") int projectId, @Param("id") int id);

  @Select("""
      SELECT c.*, COALESCE(u.display_name, u.username) AS author_name
      FROM discussion_comment c LEFT JOIN app_user u ON u.id = c.author_id
      WHERE c.project_id = #{projectId} AND c.discussion_id = #{discussionId}
      ORDER BY c.id
      """)
  List<DiscussionComment> findByDiscussion(@Param("projectId") int projectId,
      @Param("discussionId") int discussionId);

  // Just the bodies of a discussion's comments -- for DiscussionLinkService reconcile (which unions
  // the #nameID refs across the discussion body + every comment).
  @Select("""
      SELECT body FROM discussion_comment
      WHERE project_id = #{projectId} AND discussion_id = #{discussionId}
      """)
  List<String> findBodiesByDiscussion(@Param("projectId") int projectId,
      @Param("discussionId") int discussionId);

  @Update("""
      UPDATE discussion_comment SET body = #{body}, updated_at = now(), version = version + 1
      WHERE project_id = #{projectId} AND id = #{id} AND version = #{version}
      """)
  int update(@Param("projectId") int projectId, @Param("id") int id, @Param("body") String body,
      @Param("version") Integer version);

  @Delete("DELETE FROM discussion_comment WHERE project_id = #{projectId} AND id = #{id}")
  int delete(@Param("projectId") int projectId, @Param("id") int id);
}
