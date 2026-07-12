package org.catalogueoflife.editor.name;

import java.util.ArrayList;
import java.util.List;
import org.catalogueoflife.editor.name.dto.CreateReferenceRequest;
import org.catalogueoflife.editor.project.ProjectService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

// DOI (Crossref, with DataCite fallback) resolution + BibTeX import, mapping both into the normal
// ReferenceService.create path (which enforces owner/editor + auditing + validation).
@Service
public class ReferenceImportService {

  private final CrossrefClient crossref;
  private final DataciteClient datacite;
  private final ReferenceService references;
  private final ProjectService projects;

  public ReferenceImportService(CrossrefClient crossref, DataciteClient datacite,
      ReferenceService references, ProjectService projects) {
    this.crossref = crossref;
    this.datacite = datacite;
    this.references = references;
    this.projects = projects;
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
}
