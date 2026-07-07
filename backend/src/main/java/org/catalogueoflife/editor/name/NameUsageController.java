package org.catalogueoflife.editor.name;

import jakarta.validation.Valid;
import java.util.List;
import org.catalogueoflife.editor.auth.CurrentUser;
import org.catalogueoflife.editor.name.dto.CreateNameUsageRequest;
import org.catalogueoflife.editor.name.dto.NameUsageResponse;
import org.catalogueoflife.editor.name.dto.UpdateNameUsageRequest;
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
@RequestMapping("/api/projects/{pid}/usages")
public class NameUsageController {

  private final NameUsageService service;
  private final CurrentUser currentUser;

  public NameUsageController(NameUsageService service, CurrentUser currentUser) {
    this.service = service;
    this.currentUser = currentUser;
  }

  @GetMapping
  public List<NameUsageResponse> list(@PathVariable long pid,
      @RequestParam(required = false) String q,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    long uid = currentUser.require().getId();
    return (q == null || q.isBlank())
        ? service.list(uid, pid, limit, offset)
        : service.search(uid, pid, q, limit, offset);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public NameUsageResponse create(@PathVariable long pid, @Valid @RequestBody CreateNameUsageRequest req) {
    long uid = currentUser.require().getId();
    return service.create(uid, pid, req);
  }

  @GetMapping("/{id}")
  public NameUsageResponse get(@PathVariable long pid, @PathVariable long id) {
    long uid = currentUser.require().getId();
    return service.get(uid, pid, id);
  }

  @PutMapping("/{id}")
  public NameUsageResponse update(@PathVariable long pid, @PathVariable long id,
      @Valid @RequestBody UpdateNameUsageRequest req) {
    long uid = currentUser.require().getId();
    return service.update(uid, pid, id, req);
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable long pid, @PathVariable long id) {
    long uid = currentUser.require().getId();
    service.delete(uid, pid, id);
  }

  @PutMapping("/{id}/synonym-of/{acceptedId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void linkSynonym(@PathVariable long pid, @PathVariable long id, @PathVariable long acceptedId) {
    long uid = currentUser.require().getId();
    service.linkSynonym(uid, pid, id, acceptedId);
  }

  @DeleteMapping("/{id}/synonym-of/{acceptedId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void unlinkSynonym(@PathVariable long pid, @PathVariable long id, @PathVariable long acceptedId) {
    long uid = currentUser.require().getId();
    service.unlinkSynonym(uid, pid, id, acceptedId);
  }
}
