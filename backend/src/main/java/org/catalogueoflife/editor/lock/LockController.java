package org.catalogueoflife.editor.lock;

import jakarta.validation.Valid;
import java.util.List;
import org.catalogueoflife.editor.auth.CurrentUser;
import org.catalogueoflife.editor.lock.dto.AcquireLockRequest;
import org.catalogueoflife.editor.lock.dto.LockResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{pid}/locks")
public class LockController {

  private final LockService service;
  private final CurrentUser currentUser;

  public LockController(LockService service, CurrentUser currentUser) {
    this.service = service;
    this.currentUser = currentUser;
  }

  // 200 when the caller ends up holding the lock (fresh acquire, re-acquire, or takeover of an
  // expired one); 409 -- still carrying the LockResponse body so the UI can show who -- when
  // another user's still-active lock blocked the takeover. This is advisory only: the 409 is a
  // signal, not a hard block, and the client may proceed to edit anyway.
  @PostMapping
  public ResponseEntity<LockResponse> acquire(@PathVariable int pid, @Valid @RequestBody AcquireLockRequest req) {
    int uid = currentUser.require().getId();
    LockResponse resp = service.acquire(uid, pid, req);
    return ResponseEntity.status(resp.heldByMe() ? HttpStatus.OK : HttpStatus.CONFLICT).body(resp);
  }

  @GetMapping
  public List<LockResponse> list(@PathVariable int pid) {
    int uid = currentUser.require().getId();
    return service.listActive(uid, pid);
  }

  @PostMapping("/{lockId}/refresh")
  public LockResponse refresh(@PathVariable int pid, @PathVariable int lockId,
      @RequestParam(required = false) Integer ttlSeconds) {
    int uid = currentUser.require().getId();
    return service.refresh(uid, pid, lockId, ttlSeconds);
  }

  @DeleteMapping("/{lockId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void release(@PathVariable int pid, @PathVariable int lockId) {
    int uid = currentUser.require().getId();
    service.release(uid, pid, lockId);
  }
}
