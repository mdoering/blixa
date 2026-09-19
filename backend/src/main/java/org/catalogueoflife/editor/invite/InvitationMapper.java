package org.catalogueoflife.editor.invite;

import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface InvitationMapper {

  // Every read joins the project title and the inviter's display name (falling back to username),
  // which the owner list, the public preview and the invitation email all need.
  String SELECT = """
      SELECT i.*, p.title AS project_title,
             COALESCE(NULLIF(u.display_name, ''), u.username) AS invited_by_name
      FROM project_invitation i
      JOIN project p ON p.id = i.project_id
      LEFT JOIN app_user u ON u.id = i.invited_by
      """;

  @Insert("""
      INSERT INTO project_invitation (project_id, email, role, message, token, invited_by, expires_at)
      VALUES (#{projectId}, #{email}, #{role}, #{message}, #{token}, #{invitedBy}, #{expiresAt})
      """)
  @Options(useGeneratedKeys = true, keyProperty = "id")
  void insert(ProjectInvitation inv);

  @Select(SELECT + "WHERE i.id = #{id}")
  ProjectInvitation findById(int id);

  @Select(SELECT + "WHERE i.token = #{token}")
  ProjectInvitation findByToken(String token);

  // Pending = not yet accepted. Expired ones are included (the owner UI flags them) until resent or
  // revoked.
  @Select(SELECT + "WHERE i.project_id = #{projectId} AND i.accepted_at IS NULL ORDER BY i.created_at DESC, i.id DESC")
  List<ProjectInvitation> findPendingByProject(int projectId);

  // A live (pending + unexpired) invitation for this address already exists -> the owner should
  // resend it rather than create a second one. An expired one doesn't block a fresh invite.
  @Select("""
      SELECT EXISTS (
        SELECT 1 FROM project_invitation
        WHERE project_id = #{projectId} AND lower(email) = lower(#{email})
          AND accepted_at IS NULL AND expires_at > now())
      """)
  boolean hasActiveInvite(@Param("projectId") int projectId, @Param("email") String email);

  @Update("UPDATE project_invitation SET token = #{token}, expires_at = #{expiresAt} WHERE id = #{id}")
  void updateToken(@Param("id") int id, @Param("token") String token,
      @Param("expiresAt") OffsetDateTime expiresAt);

  // Single-use: the accepted_at guard makes a concurrent second accept update 0 rows.
  @Update("""
      UPDATE project_invitation SET accepted_at = now(), accepted_by = #{userId}
      WHERE id = #{id} AND accepted_at IS NULL
      """)
  int markAccepted(@Param("id") int id, @Param("userId") int userId);

  @Delete("DELETE FROM project_invitation WHERE project_id = #{projectId} AND id = #{id} AND accepted_at IS NULL")
  int deletePending(@Param("projectId") int projectId, @Param("id") int id);
}
