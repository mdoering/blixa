package org.catalogueoflife.editor.name;

import jakarta.validation.Valid;
import java.util.List;
import org.catalogueoflife.editor.auth.CurrentUser;
import org.catalogueoflife.editor.name.dto.CreateNameUsageRequest;
import org.catalogueoflife.editor.name.dto.DemoteRequest;
import org.catalogueoflife.editor.name.dto.NameUsageResponse;
import org.catalogueoflife.editor.name.dto.PromoteRequest;
import org.catalogueoflife.editor.name.dto.UpdateNameUsageRequest;
import org.catalogueoflife.editor.name.dto.UsagePage;
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
  public UsagePage list(@PathVariable int pid,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String rank,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    int uid = currentUser.require().getId();
    return service.searchPage(uid, pid, q, rank, status, limit, offset);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public NameUsageResponse create(@PathVariable int pid, @Valid @RequestBody CreateNameUsageRequest req) {
    int uid = currentUser.require().getId();
    return service.create(uid, pid, req);
  }

  @GetMapping("/{id}")
  public NameUsageResponse get(@PathVariable int pid, @PathVariable int id) {
    int uid = currentUser.require().getId();
    return service.get(uid, pid, id);
  }

  @PutMapping("/{id}")
  public NameUsageResponse update(@PathVariable int pid, @PathVariable int id,
      @Valid @RequestBody UpdateNameUsageRequest req) {
    int uid = currentUser.require().getId();
    return service.update(uid, pid, id, req);
  }

  @GetMapping("/{id}/synonyms")
  public List<NameUsageResponse> listSynonyms(@PathVariable int pid, @PathVariable int id) {
    int uid = currentUser.require().getId();
    return service.listSynonyms(uid, pid, id);
  }

  @GetMapping("/{id}/accepted")
  public List<NameUsageResponse> listAccepted(@PathVariable int pid, @PathVariable int id) {
    int uid = currentUser.require().getId();
    return service.listAccepted(uid, pid, id);
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable int pid, @PathVariable int id) {
    int uid = currentUser.require().getId();
    service.delete(uid, pid, id);
  }

  @PutMapping("/{id}/synonym-of/{acceptedId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void linkSynonym(@PathVariable int pid, @PathVariable int id, @PathVariable int acceptedId) {
    int uid = currentUser.require().getId();
    service.linkSynonym(uid, pid, id, acceptedId);
  }

  @DeleteMapping("/{id}/synonym-of/{acceptedId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void unlinkSynonym(@PathVariable int pid, @PathVariable int id, @PathVariable int acceptedId) {
    int uid = currentUser.require().getId();
    service.unlinkSynonym(uid, pid, id, acceptedId);
  }

  // acc -> syn (see NameUsageService.demote): returns the updated (now-synonym) usage.
  @PostMapping("/{id}/demote")
  public NameUsageResponse demote(@PathVariable int pid, @PathVariable int id,
      @Valid @RequestBody DemoteRequest req) {
    int uid = currentUser.require().getId();
    return service.demote(uid, pid, id, req);
  }

  // syn -> acc (see NameUsageService.promote): returns the updated (now-accepted) usage.
  @PostMapping("/{id}/promote")
  public NameUsageResponse promote(@PathVariable int pid, @PathVariable int id,
      @RequestBody PromoteRequest req) {
    int uid = currentUser.require().getId();
    return service.promote(uid, pid, id, req);
  }
}
