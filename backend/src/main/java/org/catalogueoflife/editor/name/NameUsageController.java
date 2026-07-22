package org.catalogueoflife.editor.name;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.catalogueoflife.editor.auth.CurrentUser;
import org.catalogueoflife.editor.name.export.SearchTsv;
import org.catalogueoflife.editor.name.dto.BulkStatusRequest;
import org.catalogueoflife.editor.name.dto.CreateNameUsageRequest;
import org.catalogueoflife.editor.name.dto.DemoteRequest;
import org.catalogueoflife.editor.name.dto.IdentifiersRequest;
import org.catalogueoflife.editor.name.dto.NameUsageResponse;
import org.catalogueoflife.editor.name.dto.PromoteRequest;
import org.catalogueoflife.editor.name.dto.ReferenceIdsRequest;
import org.catalogueoflife.editor.name.dto.UpdateNameUsageRequest;
import org.catalogueoflife.editor.name.dto.UsagePage;
import org.catalogueoflife.editor.name.dto.WebReferenceRequest;
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

  // Streams ALL name usages matching the current q/rank/status filters (no pagination) as a TSV
  // attachment -- the "Download TSV" action on the Names search page. Any project member (read).
  @GetMapping("/export.tsv")
  public void exportTsv(@PathVariable int pid,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String rank,
      @RequestParam(required = false) String status,
      HttpServletResponse response) throws IOException {
    int uid = currentUser.require().getId();
    List<NameUsage> rows = service.exportRows(uid, pid, q, rank, status);
    response.setContentType("text/tab-separated-values;charset=UTF-8");
    response.setHeader("Content-Disposition",
        "attachment; filename=\"project-" + pid + "-names.tsv\"");
    SearchTsv.writeUsages(response.getOutputStream(), rows);
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

  // Full replace of alternative_id, optimistic-locked (NameUsageService.setIdentifiers) -- the
  // write path a later "match to COL" feature uses to persist col:<id>.
  @PutMapping("/{id}/identifiers")
  public NameUsageResponse setIdentifiers(@PathVariable int pid, @PathVariable int id,
      @Valid @RequestBody IdentifiersRequest req) {
    int uid = currentUser.require().getId();
    return service.setIdentifiers(uid, pid, id, req);
  }

  // Full replace of reference_id (the usage's taxonomic references), optimistic-locked
  // (NameUsageService.setReferences) -- the References tab's "add/remove existing reference"
  // write path.
  @PutMapping("/{id}/references")
  public NameUsageResponse setReferences(@PathVariable int pid, @PathVariable int id,
      @Valid @RequestBody ReferenceIdsRequest req) {
    int uid = currentUser.require().getId();
    return service.setReferences(uid, pid, id, req);
  }

  // Creates a type=webpage Reference from a URL (server-side title fetch, SSRF-guarded -- see
  // WebPageClient) and appends it to the usage's reference_id[] (NameUsageService.addWebReference).
  // Returns the updated usage.
  @PostMapping("/{id}/web-reference")
  public NameUsageResponse addWebReference(@PathVariable int pid, @PathVariable int id,
      @Valid @RequestBody WebReferenceRequest req) {
    int uid = currentUser.require().getId();
    return service.addWebReference(uid, pid, id, req.url());
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

  // mode = FOCAL_ONLY (default) | WITH_SYNONYMS | SUBTREE; reparentTo optionally overrides where the
  // focal's accepted children move on the non-subtree modes (default = the grandparent).
  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable int pid, @PathVariable int id,
      @RequestParam(defaultValue = "FOCAL_ONLY") String mode,
      @RequestParam(required = false) Integer reparentTo) {
    int uid = currentUser.require().getId();
    DeleteMode deleteMode;
    try {
      deleteMode = DeleteMode.valueOf(mode.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST,
          "invalid delete mode: " + mode);
    }
    service.delete(uid, pid, id, deleteMode, reparentTo);
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

  // Bulk status change for several usages at once (see NameUsageService.bulkChangeStatus): only
  // parent-preserving transitions (accepted<->unassessed, synonym<->misapplied); returns {changed:N}.
  @PostMapping("/bulk-status")
  public Map<String, Integer> bulkStatus(@PathVariable int pid, @Valid @RequestBody BulkStatusRequest req) {
    int uid = currentUser.require().getId();
    return Map.of("changed", service.bulkChangeStatus(uid, pid, req.ids(), req.status()));
  }
}
