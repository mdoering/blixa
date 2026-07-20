package org.catalogueoflife.editor.discussion;

import java.util.List;
import org.catalogueoflife.editor.audit.Change;
import org.catalogueoflife.editor.auth.CurrentUser;
import org.catalogueoflife.editor.discussion.dto.LinkChangeRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{pid}/discussions/{did}/changes")
public class DiscussionChangeController {

  private final DiscussionChangeService service;
  private final CurrentUser currentUser;

  public DiscussionChangeController(DiscussionChangeService service, CurrentUser currentUser) {
    this.service = service;
    this.currentUser = currentUser;
  }

  @GetMapping
  public List<Change> list(@PathVariable int pid, @PathVariable int did) {
    return service.list(currentUser.require().getId(), pid, did);
  }

  @PostMapping
  public void link(@PathVariable int pid, @PathVariable int did, @RequestBody LinkChangeRequest req) {
    service.link(currentUser.require().getId(), pid, did, req.changeId());
  }

  @DeleteMapping("/{changeId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void unlink(@PathVariable int pid, @PathVariable int did, @PathVariable int changeId) {
    service.unlink(currentUser.require().getId(), pid, did, changeId);
  }
}
