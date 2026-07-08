package org.catalogueoflife.editor.task;

import java.time.OffsetDateTime;
import java.util.List;
import org.catalogueoflife.editor.name.Pagination;
import org.catalogueoflife.editor.project.ProjectService;
import org.catalogueoflife.editor.project.Role;
import org.catalogueoflife.editor.task.dto.CreateTaskRequest;
import org.catalogueoflife.editor.task.dto.TaskResponse;
import org.catalogueoflife.editor.task.dto.UpdateTaskRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

// Tasks (work-sessions) are a titled unit of work a user undertakes. Any project member may
// create/hold one -- declaring intent is not a data write, mirroring LockService's any-member
// policy for acquiring a lock -- but closing/editing a task requires being either that task's
// owner or a project OWNER (the same role-string check ProjectService.updateMetadata/requireOwner
// use against ProjectService.requireRole's return value), else 403.
@Service
public class TaskService {

  private final TaskMapper tasks;
  private final ProjectService projects;

  public TaskService(TaskMapper tasks, ProjectService projects) {
    this.tasks = tasks;
    this.projects = projects;
  }

  @Transactional
  public TaskResponse create(int actorId, int projectId, CreateTaskRequest req) {
    projects.requireRole(actorId, projectId); // any member -- 404 if not a member
    if (req.title() == null || req.title().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title is required");
    }
    Task t = new Task();
    t.setProjectId(projectId);
    t.setUserId(actorId);
    t.setTitle(req.title());
    t.setDescription(req.description());
    t.setStatus(TaskStatus.OPEN.name());
    tasks.insert(t);
    return tasks.findResponseById(projectId, t.getId());
  }

  public List<TaskResponse> list(int actorId, int projectId, String status, int limit, int offset) {
    projects.requireRole(actorId, projectId); // any member may read
    int clampedLimit = Pagination.clampLimit(limit);
    int clampedOffset = Pagination.clampOffset(offset);
    return tasks.findByProject(projectId, normalizeStatusFilter(status), clampedLimit, clampedOffset);
  }

  public TaskResponse get(int actorId, int projectId, int id) {
    projects.requireRole(actorId, projectId); // 404 if not a member
    TaskResponse resp = tasks.findResponseById(projectId, id);
    if (resp == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "task not found");
    }
    return resp;
  }

  @Transactional
  public TaskResponse update(int actorId, int projectId, int id, UpdateTaskRequest req) {
    String role = projects.requireRole(actorId, projectId);
    Task t = tasks.findById(projectId, id);
    if (t == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "task not found");
    }
    boolean isTaskOwner = t.getUserId() != null && t.getUserId() == actorId;
    boolean isProjectOwner = Role.OWNER.dbValue().equals(role);
    if (!isTaskOwner && !isProjectOwner) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "task owner or project owner required");
    }
    if (req.title() != null) {
      if (req.title().isBlank()) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title must not be blank");
      }
      t.setTitle(req.title());
    }
    if (req.description() != null) {
      t.setDescription(req.description());
    }
    if (req.status() != null) {
      TaskStatus newStatus = TaskStatus.fromApi(req.status());
      t.setStatus(newStatus.name());
      // closing stamps closed_at now(); reopening clears it since it no longer applies.
      t.setClosedAt(newStatus == TaskStatus.CLOSED ? OffsetDateTime.now() : null);
    }
    tasks.update(t);
    return tasks.findResponseById(projectId, id);
  }

  // null/blank/"all" -> no filter (every status), matching ChangeController's "absent filter"
  // convention; otherwise validated + normalized to the stored upper-case enum name, 400 on
  // anything unrecognized.
  private static String normalizeStatusFilter(String status) {
    if (status == null || status.isBlank() || status.equalsIgnoreCase("all")) {
      return null;
    }
    return TaskStatus.fromApi(status).name();
  }
}
