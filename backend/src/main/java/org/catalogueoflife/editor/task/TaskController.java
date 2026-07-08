package org.catalogueoflife.editor.task;

import java.util.List;
import org.catalogueoflife.editor.auth.CurrentUser;
import org.catalogueoflife.editor.task.dto.CreateTaskRequest;
import org.catalogueoflife.editor.task.dto.TaskResponse;
import org.catalogueoflife.editor.task.dto.UpdateTaskRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{pid}/tasks")
public class TaskController {

  private final TaskService service;
  private final CurrentUser currentUser;

  public TaskController(TaskService service, CurrentUser currentUser) {
    this.service = service;
    this.currentUser = currentUser;
  }

  // 200 (not 201): the response carries the task's full derived shape (changeCount, username)
  // the same way LockController.acquire returns 200 rather than 201.
  @PostMapping
  public TaskResponse create(@PathVariable int pid, @RequestBody CreateTaskRequest req) {
    int uid = currentUser.require().getId();
    return service.create(uid, pid, req);
  }

  @GetMapping
  public List<TaskResponse> list(@PathVariable int pid,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    int uid = currentUser.require().getId();
    return service.list(uid, pid, status, limit, offset);
  }

  @GetMapping("/{id}")
  public TaskResponse get(@PathVariable int pid, @PathVariable int id) {
    int uid = currentUser.require().getId();
    return service.get(uid, pid, id);
  }

  @PatchMapping("/{id}")
  public TaskResponse update(@PathVariable int pid, @PathVariable int id,
      @RequestBody UpdateTaskRequest req) {
    int uid = currentUser.require().getId();
    return service.update(uid, pid, id, req);
  }
}
