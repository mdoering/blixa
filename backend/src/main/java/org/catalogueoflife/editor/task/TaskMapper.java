package org.catalogueoflife.editor.task;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.catalogueoflife.editor.task.dto.TaskResponse;

// map-underscore-to-camel-case (application.yml) auto-maps every selected column onto Task's
// properties for the plain-POJO read (findById), the same way LockMapper/ChangeMapper's simple
// selects do. TaskResponse is a record, so its reads alias every column to the exact camelCase
// constructor-parameter name -- MyBatis's automatic constructor-based result mapping (enabled by
// the -parameters compiler flag, see pom.xml) instantiates it directly from those aliases, the
// same way TreeMapper's TreeNode/PathNode reads do. status is stored as TaskStatus.name()
// (upper-case TEXT); the TaskResponse projections LOWER() it since the API speaks lowercase.
@Mapper
public interface TaskMapper {

  @Insert("""
      INSERT INTO task (project_id, user_id, title, description, status)
      VALUES (#{projectId}, #{userId}, #{title}, #{description}, #{status})
      """)
  @Options(useGeneratedKeys = true, keyProperty = "id")
  void insert(Task t);

  @Select("""
      SELECT id, project_id, user_id, title, description, status, created_at, closed_at
      FROM task
      WHERE project_id = #{projectId} AND id = #{id}
      """)
  Task findById(@Param("projectId") int projectId, @Param("id") int id);

  // Same projection as findByProject but scoped to a single id -- lets TaskService.create/get/
  // update return the identical (username + changeCount) shape as the list endpoint without
  // duplicating the join/subquery logic in Java.
  @Select("""
      SELECT t.id AS id, t.title AS title, t.description AS description,
             LOWER(t.status) AS status, t.user_id AS userId, u.username AS username,
             t.created_at AS createdAt, t.closed_at AS closedAt,
             (SELECT COUNT(*) FROM change c WHERE c.task_id = t.id AND c.project_id = t.project_id) AS changeCount
      FROM task t
      LEFT JOIN app_user u ON u.id = t.user_id
      WHERE t.project_id = #{projectId} AND t.id = #{id}
      """)
  TaskResponse findResponseById(@Param("projectId") int projectId, @Param("id") int id);

  // status null -> no filter (list "all" tasks); TaskService translates the API's null/"all"/
  // "open"/"closed" into either null or the stored upper-case enum name before calling this.
  @Select("""
      <script>
      SELECT t.id AS id, t.title AS title, t.description AS description,
             LOWER(t.status) AS status, t.user_id AS userId, u.username AS username,
             t.created_at AS createdAt, t.closed_at AS closedAt,
             (SELECT COUNT(*) FROM change c WHERE c.task_id = t.id AND c.project_id = t.project_id) AS changeCount
      FROM task t
      LEFT JOIN app_user u ON u.id = t.user_id
      WHERE t.project_id = #{projectId}
      <if test="status != null">AND t.status = #{status}</if>
      ORDER BY t.created_at DESC, t.id DESC
      LIMIT #{limit} OFFSET #{offset}
      </script>
      """)
  List<TaskResponse> findByProject(@Param("projectId") int projectId, @Param("status") String status,
      @Param("limit") int limit, @Param("offset") int offset);

  @Update("""
      UPDATE task
      SET title = #{title}, description = #{description}, status = #{status}, closed_at = #{closedAt}
      WHERE id = #{id} AND project_id = #{projectId}
      """)
  int update(Task t);
}
