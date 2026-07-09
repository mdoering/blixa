package org.catalogueoflife.editor.child;

import java.util.List;
import org.catalogueoflife.editor.auth.CurrentUser;
import org.catalogueoflife.editor.child.dto.MediaRequest;
import org.catalogueoflife.editor.child.dto.MediaResponse;
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
@RequestMapping("/api/projects/{pid}/usages/{uid}/media")
public class MediaController {

  private final MediaService service;
  private final CurrentUser currentUser;

  public MediaController(MediaService service, CurrentUser currentUser) {
    this.service = service;
    this.currentUser = currentUser;
  }

  @GetMapping
  public List<MediaResponse> list(@PathVariable int pid, @PathVariable int uid) {
    return service.list(currentUser.require().getId(), pid, uid);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public MediaResponse create(@PathVariable int pid, @PathVariable int uid,
      @RequestBody MediaRequest req) {
    return service.create(currentUser.require().getId(), pid, uid, req);
  }

  @PutMapping("/{id}")
  public MediaResponse update(@PathVariable int pid, @PathVariable int uid,
      @PathVariable int id, @RequestBody MediaRequest req) {
    return service.update(currentUser.require().getId(), pid, uid, id, req);
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable int pid, @PathVariable int uid, @PathVariable int id) {
    service.delete(currentUser.require().getId(), pid, uid, id);
  }
}
