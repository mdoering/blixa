package org.catalogueoflife.editor.name;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.catalogueoflife.editor.auth.CurrentUser;
import org.catalogueoflife.editor.name.dto.BibtexRequest;
import org.catalogueoflife.editor.name.dto.ContainerTitleFacet;
import org.catalogueoflife.editor.name.dto.ContainerTitleMergeRequest;
import org.catalogueoflife.editor.name.dto.CreateReferenceRequest;
import org.catalogueoflife.editor.name.dto.DoiRequest;
import org.catalogueoflife.editor.name.dto.ReferenceResponse;
import org.catalogueoflife.editor.name.dto.RisRequest;
import org.catalogueoflife.editor.name.dto.UpdateReferenceRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/projects/{pid}/references")
public class ReferenceController {

  private final ReferenceService service;
  private final ReferenceImportService importService;
  private final CurrentUser currentUser;
  // coldp.pdf.base-url, threaded into every ReferenceResponse.of call below so pdfUrl is built the
  // same way here as in ReferenceColdpWriter's export `link` (see its own @Value of the same key).
  private final String pdfBaseUrl;

  public ReferenceController(ReferenceService service, ReferenceImportService importService,
      CurrentUser currentUser, @Value("${coldp.pdf.base-url}") String pdfBaseUrl) {
    this.service = service;
    this.importService = importService;
    this.currentUser = currentUser;
    this.pdfBaseUrl = pdfBaseUrl;
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
    return importService.importBibtex(uid, pid, req.bibtex()).stream()
        .map(r -> ReferenceResponse.of(r, pdfBaseUrl)).toList();
  }

  // Parse + create every record in a RIS blob (Zotero/EndNote/Mendeley export format).
  @PostMapping("/import-ris")
  @ResponseStatus(HttpStatus.CREATED)
  public List<ReferenceResponse> importRis(@PathVariable int pid, @RequestBody RisRequest req) {
    int uid = currentUser.require().getId();
    return importService.importRis(uid, pid, req.ris()).stream()
        .map(r -> ReferenceResponse.of(r, pdfBaseUrl)).toList();
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
    return result.stream().map(r -> ReferenceResponse.of(r, pdfBaseUrl)).toList();
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ReferenceResponse create(@PathVariable int pid, @Valid @RequestBody CreateReferenceRequest req) {
    int uid = currentUser.require().getId();
    Reference r = service.create(uid, pid, req);
    return ReferenceResponse.of(r, pdfBaseUrl);
  }

  // Distinct container_title (journal name) values + counts, for ReconcileJournalsModal's facet.
  @GetMapping("/facets/container-title")
  public List<ContainerTitleFacet> containerTitleFacet(@PathVariable int pid) {
    int uid = currentUser.require().getId();
    return service.containerTitleFacet(uid, pid);
  }

  // Normalizes every reference whose container_title is one of req.variants() to req.canonical().
  @PostMapping("/facets/container-title/merge")
  public Map<String, Integer> mergeContainerTitle(@PathVariable int pid,
      @RequestBody ContainerTitleMergeRequest req) {
    int uid = currentUser.require().getId();
    int updated = service.mergeContainerTitle(uid, pid, req.canonical(), req.variants());
    return Map.of("updated", updated);
  }

  @GetMapping("/{id}")
  public ReferenceResponse get(@PathVariable int pid, @PathVariable int id) {
    int uid = currentUser.require().getId();
    return ReferenceResponse.of(service.get(uid, pid, id), pdfBaseUrl);
  }

  @PutMapping("/{id}")
  public ReferenceResponse update(@PathVariable int pid, @PathVariable int id,
      @Valid @RequestBody UpdateReferenceRequest req) {
    int uid = currentUser.require().getId();
    return ReferenceResponse.of(service.update(uid, pid, id, req), pdfBaseUrl);
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable int pid, @PathVariable int id) {
    int uid = currentUser.require().getId();
    service.delete(uid, pid, id);
  }

  // Uploads (or replaces) this reference's hosted PDF; the file is streamed back publicly at
  // GET /pdf/{filename} (PdfController) -- see PdfService.store for the validation/size gating.
  @PostMapping(value = "/{id}/pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ReferenceResponse attachPdf(@PathVariable int pid, @PathVariable int id,
      @RequestPart("file") MultipartFile file) {
    int uid = currentUser.require().getId();
    return ReferenceResponse.of(service.attachPdf(uid, pid, id, file), pdfBaseUrl);
  }

  @DeleteMapping("/{id}/pdf")
  public ReferenceResponse removePdf(@PathVariable int pid, @PathVariable int id) {
    int uid = currentUser.require().getId();
    return ReferenceResponse.of(service.removePdf(uid, pid, id), pdfBaseUrl);
  }
}
