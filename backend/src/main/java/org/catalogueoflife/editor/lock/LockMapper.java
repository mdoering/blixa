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
  // taskId is nullable (Integer, not int) -- an absent declared intent inserts/overwrites task_id
  // as NULL, same as every other advisory, optional field here.
  @Insert("""
      INSERT INTO lock (project_id, entity_type, entity_id, user_id, task_id, acquired_at, expires_at)
      VALUES (#{projectId}, #{entityType}, #{entityId}, #{userId}, #{taskId}, now(), now() + make_interval(secs => #{ttl}))
      ON CONFLICT (project_id, entity_type, entity_id) DO UPDATE
        SET user_id = EXCLUDED.user_id, task_id = EXCLUDED.task_id, acquired_at = now(), expires_at = EXCLUDED.expires_at
        WHERE lock.user_id = EXCLUDED.user_id OR lock.expires_at <= now()
      """)
  void upsertTakeover(@Param("projectId") int projectId, @Param("entityType") String entityType,
      @Param("entityId") int entityId, @Param("userId") int userId, @Param("taskId") Integer taskId,
      @Param("ttl") int ttl);

  // LEFT JOIN task so an absent/foreign task_id (never possible once validated by LockService, but
  // also true of a lock predating this feature) still returns the lock row with a null taskTitle
  // rather than dropping it.
  @Select("""
      SELECT l.id, l.project_id, l.entity_type, l.entity_id, l.user_id, u.username,
             l.acquired_at, l.expires_at, l.task_id, t.title AS task_title
      FROM lock l
      LEFT JOIN app_user u ON u.id = l.user_id
      LEFT JOIN task t ON t.id = l.task_id
      WHERE l.project_id = #{projectId} AND l.entity_type = #{entityType} AND l.entity_id = #{entityId}
      """)
  Lock findByEntity(@Param("projectId") int projectId, @Param("entityType") String entityType,
      @Param("entityId") int entityId);

  // Only unexpired rows count as "active" -- an expired row is treated as absent everywhere, not
  // just at acquire time.
  @Select("""
      SELECT l.id, l.project_id, l.entity_type, l.entity_id, l.user_id, u.username,
             l.acquired_at, l.expires_at, l.task_id, t.title AS task_title
      FROM lock l
      LEFT JOIN app_user u ON u.id = l.user_id
      LEFT JOIN task t ON t.id = l.task_id
      WHERE l.project_id = #{projectId} AND l.expires_at > now()
      ORDER BY l.acquired_at DESC
      """)
  List<Lock> findActive(@Param("projectId") int projectId);

  @Select("""
      SELECT l.id, l.project_id, l.entity_type, l.entity_id, l.user_id, u.username,
             l.acquired_at, l.expires_at, l.task_id, t.title AS task_title
      FROM lock l
      LEFT JOIN app_user u ON u.id = l.user_id
      LEFT JOIN task t ON t.id = l.task_id
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

  // Retention sweep (see LockRetentionSweep): every findActive/upsertTakeover call already treats
  // an expired row as absent, but nothing physically removes it -- this is that cleanup, run
  // periodically rather than on every read.
  @Delete("DELETE FROM lock WHERE expires_at <= now()")
  int deleteExpired();

  // Advisory locks are meaningless once their target entity is gone: called from
  // NameUsageService.delete and MergeRecordsService.mergeUsages right after the entity's own
  // polymorphic issue rows are cleaned up, for the same reason (no cascade FK, since entity_id is
  // polymorphic across entity_type).
  @Delete("DELETE FROM lock WHERE project_id = #{projectId} AND entity_type = #{entityType} AND entity_id = #{entityId}")
  int deleteByEntity(@Param("projectId") int projectId, @Param("entityType") String entityType,
      @Param("entityId") int entityId);

  // Test-only: upsertTakeover's ttl is a positive seconds-from-now offset, so a past expires_at
  // can't be seeded through the public API -- this lets LockSweepIT force a row into the expired
  // state deleteExpired() is meant to sweep.
  @Update("UPDATE lock SET expires_at = now() - interval '1 hour' WHERE id = #{id}")
  void expireForTest(@Param("id") int id);
}
