package org.catalogueoflife.editor.discussion;

import jakarta.validation.Valid;
import org.catalogueoflife.editor.auth.CurrentUser;
import org.catalogueoflife.editor.discussion.dto.CreateDiscussionRequest;
import org.catalogueoflife.editor.discussion.dto.DiscussionPage;
import org.catalogueoflife.editor.discussion.dto.DiscussionResponse;
import org.catalogueoflife.editor.discussion.dto.StatusRequest;
import org.catalogueoflife.editor.discussion.dto.UpdateDiscussionRequest;
import org.catalogueoflife.editor.discussion.dto.VisibilityRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{pid}/discussions")
public class DiscussionController {

  private final DiscussionService service;
  private final CurrentUser currentUser;

  public DiscussionController(DiscussionService service, CurrentUser currentUser) {
    this.service = service;
    this.currentUser = currentUser;
  }

  @GetMapping
  public DiscussionPage list(@PathVariable int pid,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Integer authorId,
      @RequestParam(defaultValue = "created") String sort,
      @RequestParam(defaultValue = "desc") String order,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    int uid = currentUser.require().getId();
    return service.search(uid, pid, q, status, authorId, sort, order, limit, offset);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public DiscussionResponse create(@PathVariable int pid,
      @Valid @RequestBody CreateDiscussionRequest req) {
    int uid = currentUser.require().getId();
    return DiscussionResponse.of(service.create(uid, pid, req));
  }

  @GetMapping("/{id}")
  public DiscussionResponse get(@PathVariable int pid, @PathVariable int id) {
    int uid = currentUser.require().getId();
    return service.getDetail(uid, pid, id);
  }

  @PutMapping("/{id}")
  public DiscussionResponse update(@PathVariable int pid, @PathVariable int id,
      @Valid @RequestBody UpdateDiscussionRequest req) {
    int uid = currentUser.require().getId();
    return DiscussionResponse.of(service.update(uid, pid, id, req));
  }

  @PostMapping("/{id}/status")
  public DiscussionResponse setStatus(@PathVariable int pid, @PathVariable int id,
      @RequestBody StatusRequest req) {
    int uid = currentUser.require().getId();
    return DiscussionResponse.of(service.setStatus(uid, pid, id, req.status()));
  }

  // Editor-only: mark a discussion INTERNAL or PUBLIC (PUBLIC = readable via the public route).
  @PostMapping("/{id}/visibility")
  public DiscussionResponse setVisibility(@PathVariable int pid, @PathVariable int id,
      @RequestBody VisibilityRequest req) {
    int uid = currentUser.require().getId();
    return DiscussionResponse.of(service.setVisibility(uid, pid, id, req.visibility()));
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable int pid, @PathVariable int id) {
    int uid = currentUser.require().getId();
    service.delete(uid, pid, id);
  }
}
