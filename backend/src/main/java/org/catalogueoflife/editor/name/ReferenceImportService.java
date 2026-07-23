package org.catalogueoflife.editor.name;

import java.util.ArrayList;
import java.util.List;
import org.catalogueoflife.editor.name.dto.CreateReferenceRequest;
import org.catalogueoflife.editor.project.ProjectService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// DOI (Crossref, with DataCite fallback) resolution + BibTeX/RIS/CSL-JSON import, mapping all into
// the normal ReferenceService.create path (which enforces owner/editor + auditing + validation).
@Service
public class ReferenceImportService {

  private final CrossrefClient crossref;
  private final DataciteClient datacite;
  private final ReferenceService references;
  private final ProjectService projects;
  private final ObjectMapper json;

  public ReferenceImportService(CrossrefClient crossref, DataciteClient datacite,
      ReferenceService references, ProjectService projects, ObjectMapper json) {
    this.crossref = crossref;
    this.datacite = datacite;
    this.references = references;
    this.projects = projects;
    this.json = json;
  }

  // Resolve a DOI to an UNSAVED CreateReferenceRequest preview (the UI reviews it before saving).
  // Accepts a bare DOI, a "doi:"-prefixed DOI, or a doi.org resolver URL (RefMapping.normalizeDoi).
  // Tries Crossref first; if Crossref genuinely doesn't have the DOI (404), falls back to DataCite
  // (datasets, software, and other DataCite-only DOIs commonly aren't on Crossref). A Crossref
  // outage (BAD_GATEWAY) is NOT treated as "not found" -- it propagates as-is rather than silently
  // falling through to DataCite.
  public CreateReferenceRequest resolveDoi(int userId, int projectId, String doi) {
    projects.requireRole(userId, projectId); // any member may look up a DOI
    if (doi == null || doi.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "doi required");
    }
    String d = RefMapping.normalizeDoi(doi);
    try {
      return RefMapping.fromCrossref(crossref.fetchWork(d));
    } catch (ResponseStatusException e) {
      if (e.getStatusCode() != HttpStatus.NOT_FOUND) {
        throw e;
      }
      try {
        return RefMapping.fromDatacite(datacite.fetchDoi(d));
      } catch (ResponseStatusException e2) {
        if (e2.getStatusCode() == HttpStatus.NOT_FOUND) {
          throw new ResponseStatusException(HttpStatus.NOT_FOUND,
              "DOI not found on Crossref or DataCite");
        }
        throw e2;
      }
    }
  }

  // Parse a BibTeX blob and create every entry atomically (all-or-nothing). ReferenceService.create
  // enforces owner/editor on the first entry, so a viewer is rejected before any write.
  @Transactional
  public List<Reference> importBibtex(int userId, int projectId, String bibtex) {
    List<CreateReferenceRequest> parsed = RefMapping.fromBibtex(bibtex);
    List<Reference> created = new ArrayList<>();
    for (CreateReferenceRequest req : parsed) {
      created.add(references.create(userId, projectId, req));
    }
    return created;
  }

  // Parse a RIS blob (Zotero/EndNote/Mendeley export format) and create every record atomically
  // (all-or-nothing), same shape as importBibtex above.
  @Transactional
  public List<Reference> importRis(int userId, int projectId, String ris) {
    List<CreateReferenceRequest> parsed = RefMapping.fromRis(ris);
    List<Reference> created = new ArrayList<>();
    for (CreateReferenceRequest req : parsed) {
      created.add(references.create(userId, projectId, req));
    }
    return created;
  }

  // Parse a CSL-JSON blob (an array of items, or a single item object -- the citeproc format
  // Zotero/CrossRef "Get citation"/pandoc emit) and create every item atomically, same shape as the
  // BibTeX/RIS imports. Malformed JSON or a document with no mappable item -> 400.
  @Transactional
  public List<Reference> importCslJson(int userId, int projectId, String cslJson) {
    if (cslJson == null || cslJson.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "no CSL-JSON provided");
    }
    JsonNode root;
    try {
      root = json.readTree(cslJson);
    } catch (JacksonException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "could not parse CSL-JSON");
    }
    List<CreateReferenceRequest> parsed = RefMapping.fromCslJson(root);
    if (parsed.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "no CSL-JSON items found");
    }
    List<Reference> created = new ArrayList<>();
    for (CreateReferenceRequest req : parsed) {
      created.add(references.create(userId, projectId, req));
    }
    return created;
  }
}
