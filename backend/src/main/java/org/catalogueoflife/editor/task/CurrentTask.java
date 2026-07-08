package org.catalogueoflife.editor.task;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;
import org.springframework.web.server.ResponseStatusException;

// Resolves the optional X-Task-Id request header into a validated task id for the current HTTP
// request. Request-scoped (one instance per request, backed by a CGLIB proxy injected into
// singletons like AuditService -- see @RequestScope's default proxyMode) so the header is read
// exactly once and the TaskMapper.findById lookup result is memoized for the lifetime of the
// request: AuditService.record calls resolve(projectId) on every audited write, and a request can
// contain several writes (e.g. create-then-update in one handler), so re-parsing/re-querying on
// every call would be wasteful and -- since findById is a simple point lookup -- pointless.
//
// Tasks are optional/soft (see plan's Global Constraints): an absent or blank header resolves to
// null (ungrouped change), but a *present* header that doesn't parse, or doesn't name an OPEN task
// in this project, is a client error -> 400, surfacing the bug rather than silently dropping the
// grouping. Since AuditService.record runs inside the caller's write transaction, a 400 thrown
// here rolls the whole write back -- a write attributed to a bogus task must not persist.
@Component
@RequestScope
public class CurrentTask {

  public static final String HEADER = "X-Task-Id";

  private final HttpServletRequest request;
  private final TaskMapper tasks;

  private boolean resolved;
  private Integer taskId;

  public CurrentTask(HttpServletRequest request, TaskMapper tasks) {
    this.request = request;
    this.tasks = tasks;
  }

  public Integer resolve(int projectId) {
    if (resolved) {
      return taskId;
    }
    resolved = true;
    taskId = resolveHeader(projectId);
    return taskId;
  }

  private Integer resolveHeader(int projectId) {
    String header = request.getHeader(HEADER);
    if (header == null || header.isBlank()) {
      return null;
    }
    int id;
    try {
      id = Integer.parseInt(header.trim());
    } catch (NumberFormatException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid " + HEADER + " header: " + header);
    }
    Task t = tasks.findById(projectId, id);
    if (t == null || !TaskStatus.OPEN.name().equals(t.getStatus())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown or closed task: " + id);
    }
    return id;
  }
}
