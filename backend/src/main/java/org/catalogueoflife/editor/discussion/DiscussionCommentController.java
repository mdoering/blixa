package org.catalogueoflife.editor.discussion;

import jakarta.validation.Valid;
import java.util.List;
import org.catalogueoflife.editor.auth.CurrentUser;
import org.catalogueoflife.editor.discussion.dto.CommentResponse;
import org.catalogueoflife.editor.discussion.dto.CreateCommentRequest;
import org.catalogueoflife.editor.discussion.dto.UpdateCommentRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{pid}/discussions/{did}/comments")
public class DiscussionCommentController {

  private final DiscussionCommentService service;
  private final CurrentUser currentUser;

  public DiscussionCommentController(DiscussionCommentService service, CurrentUser currentUser) {
    this.service = service;
    this.currentUser = currentUser;
  }

  @GetMapping
  public List<CommentResponse> list(@PathVariable int pid, @PathVariable int did) {
    int uid = currentUser.require().getId();
    return service.list(uid, pid, did);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public CommentResponse create(@PathVariable int pid, @PathVariable int did,
      @Valid @RequestBody CreateCommentRequest req) {
    int uid = currentUser.require().getId();
    return service.create(uid, pid, did, req.body());
  }

  @PutMapping("/{cid}")
  public CommentResponse update(@PathVariable int pid, @PathVariable int did, @PathVariable int cid,
      @Valid @RequestBody UpdateCommentRequest req) {
    int uid = currentUser.require().getId();
    return service.update(uid, pid, did, cid, req.body(), req.version());
  }

  @DeleteMapping("/{cid}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable int pid, @PathVariable int did, @PathVariable int cid) {
    int uid = currentUser.require().getId();
    service.delete(uid, pid, did, cid);
  }
}
