package org.catalogueoflife.editor.release;

import java.util.List;
import java.util.Map;
import org.catalogueoflife.editor.auth.CurrentUser;
import org.catalogueoflife.editor.release.dto.ReleaseResponse;
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
@RequestMapping("/api/projects/{pid}/releases")
public class ReleaseController {

  private final ReleaseService service;
  private final CurrentUser currentUser;

  public ReleaseController(ReleaseService service, CurrentUser currentUser) {
    this.service = service;
    this.currentUser = currentUser;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.ACCEPTED)
  public ReleaseResponse publish(@PathVariable int pid, @RequestBody Map<String, String> body) {
    int uid = currentUser.require().getId();
    return service.publish(uid, pid, body.get("version"), body.get("notes"));
  }

  @GetMapping
  public List<ReleaseResponse> list(@PathVariable int pid) {
    int uid = currentUser.require().getId();
    return service.list(uid, pid);
  }

  @DeleteMapping("/{rid}")
  public void delete(@PathVariable int pid, @PathVariable int rid) {
    int uid = currentUser.require().getId();
    service.delete(uid, pid, rid);
  }
}
