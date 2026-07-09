package org.catalogueoflife.editor.name;

import jakarta.validation.Valid;
import java.util.List;
import org.catalogueoflife.editor.auth.CurrentUser;
import org.catalogueoflife.editor.name.dto.BibtexRequest;
import org.catalogueoflife.editor.name.dto.CreateReferenceRequest;
import org.catalogueoflife.editor.name.dto.DoiRequest;
import org.catalogueoflife.editor.name.dto.ReferenceResponse;
import org.catalogueoflife.editor.name.dto.UpdateReferenceRequest;
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
@RequestMapping("/api/projects/{pid}/references")
public class ReferenceController {

  private final ReferenceService service;
  private final ReferenceImportService importService;
  private final CurrentUser currentUser;

  public ReferenceController(ReferenceService service, ReferenceImportService importService,
      CurrentUser currentUser) {
    this.service = service;
    this.importService = importService;
    this.currentUser = currentUser;
  }

  // DOI -> an UNSAVED CreateReferenceRequest preview (the UI reviews then creates it normally).
  @PostMapping("/resolve-doi")
  public CreateReferenceRequest resolveDoi(@PathVariable int pid, @RequestBody DoiRequest req) {
    int uid = currentUser.require().getId();
    return importService.resolveDoi(uid, pid, req.doi());
  }

  // Parse + create every entry in a BibTeX blob.
  @PostMapping("/import-bibtex")
  @ResponseStatus(HttpStatus.CREATED)
  public List<ReferenceResponse> importBibtex(@PathVariable int pid, @RequestBody BibtexRequest req) {
    int uid = currentUser.require().getId();
    return importService.importBibtex(uid, pid, req.bibtex()).stream().map(ReferenceResponse::of).toList();
  }

  @GetMapping
  public List<ReferenceResponse> list(@PathVariable int pid,
      @RequestParam(required = false) String q,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    int uid = currentUser.require().getId();
    List<Reference> result = (q == null || q.isBlank())
        ? service.list(uid, pid, limit, offset)
        : service.search(uid, pid, q, limit, offset);
    return result.stream().map(ReferenceResponse::of).toList();
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ReferenceResponse create(@PathVariable int pid, @Valid @RequestBody CreateReferenceRequest req) {
    int uid = currentUser.require().getId();
    Reference r = service.create(uid, pid, req);
    return ReferenceResponse.of(r);
  }

  @GetMapping("/{id}")
  public ReferenceResponse get(@PathVariable int pid, @PathVariable int id) {
    int uid = currentUser.require().getId();
    return ReferenceResponse.of(service.get(uid, pid, id));
  }

  @PutMapping("/{id}")
  public ReferenceResponse update(@PathVariable int pid, @PathVariable int id,
      @Valid @RequestBody UpdateReferenceRequest req) {
    int uid = currentUser.require().getId();
    return ReferenceResponse.of(service.update(uid, pid, id, req));
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable int pid, @PathVariable int id) {
    int uid = currentUser.require().getId();
    service.delete(uid, pid, id);
  }
}
