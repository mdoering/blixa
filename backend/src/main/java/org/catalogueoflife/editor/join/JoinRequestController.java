package org.catalogueoflife.editor.join;

import java.util.List;
import org.catalogueoflife.editor.auth.CurrentUser;
import org.catalogueoflife.editor.join.dto.JoinRequestCount;
import org.catalogueoflife.editor.join.dto.JoinRequestResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// Owner-only: pending join requests for a project. All three endpoints gate through
// ProjectService.requireOwner (via JoinRequestService), so a non-owner member gets 403 and a
// non-member gets 404, same as the other project-scoped owner actions in ProjectController.
@RestController
@RequestMapping("/api/projects/{pid}/join-requests")
public class JoinRequestController {

  private final JoinRequestService service;
  private final CurrentUser currentUser;

  public JoinRequestController(JoinRequestService service, CurrentUser currentUser) {
    this.service = service;
    this.currentUser = currentUser;
  }

  @GetMapping
  public List<JoinRequestResponse> list(@PathVariable int pid) {
    int uid = currentUser.require().getId();
    return service.list(uid, pid).stream().map(JoinRequestResponse::of).toList();
  }

  @GetMapping("/count")
  public JoinRequestCount count(@PathVariable int pid) {
    int uid = currentUser.require().getId();
    return new JoinRequestCount(service.count(uid, pid));
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void dismiss(@PathVariable int pid, @PathVariable int id) {
    int uid = currentUser.require().getId();
    service.dismiss(uid, pid, id);
  }
}
