package org.catalogueoflife.editor.lock;

import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

// map-underscore-to-camel-case (application.yml) auto-maps every selected column onto Lock's
// properties without needing @Results boilerplate, the same way ChangeMapper's selects do.
@Mapper
public interface LockMapper {

  // Insert, or take over the existing row in place, if it's either already ours (a refresh via
  // re-acquire) or expired (the previous holder's claim has lapsed); otherwise the WHERE clause
  // makes the ON CONFLICT branch a no-op and the still-active other user's row is left untouched.
  // Callers read the row back afterwards (findByEntity) to learn who ended up holding it.
  @Insert("""
      INSERT INTO lock (project_id, entity_type, entity_id, user_id, acquired_at, expires_at)
      VALUES (#{projectId}, #{entityType}, #{entityId}, #{userId}, now(), now() + make_interval(secs => #{ttl}))
      ON CONFLICT (project_id, entity_type, entity_id) DO UPDATE
        SET user_id = EXCLUDED.user_id, acquired_at = now(), expires_at = EXCLUDED.expires_at
        WHERE lock.user_id = EXCLUDED.user_id OR lock.expires_at <= now()
      """)
  void upsertTakeover(@Param("projectId") int projectId, @Param("entityType") String entityType,
      @Param("entityId") int entityId, @Param("userId") int userId, @Param("ttl") int ttl);

  @Select("""
      SELECT l.id, l.project_id, l.entity_type, l.entity_id, l.user_id, u.username,
             l.acquired_at, l.expires_at
      FROM lock l
      LEFT JOIN app_user u ON u.id = l.user_id
      WHERE l.project_id = #{projectId} AND l.entity_type = #{entityType} AND l.entity_id = #{entityId}
      """)
  Lock findByEntity(@Param("projectId") int projectId, @Param("entityType") String entityType,
      @Param("entityId") int entityId);

  // Only unexpired rows count as "active" -- an expired row is treated as absent everywhere, not
  // just at acquire time.
  @Select("""
      SELECT l.id, l.project_id, l.entity_type, l.entity_id, l.user_id, u.username,
             l.acquired_at, l.expires_at
      FROM lock l
      LEFT JOIN app_user u ON u.id = l.user_id
      WHERE l.project_id = #{projectId} AND l.expires_at > now()
      ORDER BY l.acquired_at DESC
      """)
  List<Lock> findActive(@Param("projectId") int projectId);

  @Select("""
      SELECT l.id, l.project_id, l.entity_type, l.entity_id, l.user_id, u.username,
             l.acquired_at, l.expires_at
      FROM lock l
      LEFT JOIN app_user u ON u.id = l.user_id
      WHERE l.project_id = #{projectId} AND l.id = #{id}
      """)
  Lock findById(@Param("projectId") int projectId, @Param("id") int id);

  // Only extends a lock the caller currently, and still-actively, holds -- 0 rows means it's
  // someone else's, already expired, or doesn't exist; the caller (LockService) tells those apart
  // with a follow-up findById.
  @Update("""
      UPDATE lock SET expires_at = now() + make_interval(secs => #{ttl})
      WHERE id = #{id} AND project_id = #{projectId} AND user_id = #{userId} AND expires_at > now()
      """)
  int refresh(@Param("projectId") int projectId, @Param("id") int id, @Param("userId") int userId,
      @Param("ttl") int ttl);

  @Delete("DELETE FROM lock WHERE id = #{id} AND project_id = #{projectId} AND user_id = #{userId}")
  int delete(@Param("projectId") int projectId, @Param("id") int id, @Param("userId") int userId);
}
