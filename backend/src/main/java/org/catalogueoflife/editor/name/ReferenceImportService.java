package org.catalogueoflife.editor.name;

import java.util.ArrayList;
import java.util.List;
import life.catalogue.api.model.CslName;
import org.catalogueoflife.editor.name.dto.CreateReferenceRequest;
import org.catalogueoflife.editor.name.dto.DoiCandidate;
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

  // How many Crossref candidates to fetch for DOI consolidation (top-N by relevance).
  private static final int DOI_CANDIDATE_ROWS = 5;

  // DOI consolidation: find candidate DOIs for an EXISTING reference by searching Crossref over its
  // structured fields (the inverse of resolveDoi). Read-only -- any project member; the user reviews
  // the candidates (each with a relevance score) and applies one via the normal reference update, so
  // this never writes. A reference that already has a DOI can still be checked. Empty query (nothing
  // to search on) -> no candidates without a network call (CrossrefClient.searchWorks).
  public List<DoiCandidate> findDoiCandidates(int userId, int projectId, int refId) {
    Reference r = references.get(userId, projectId, refId); // requireRole + 404 if missing
    return RefMapping.doiCandidates(
        crossref.searchWorks(bibliographicQuery(r), authorQuery(r.getAuthor()), DOI_CANDIDATE_ROWS));
  }

  // Crossref query.bibliographic: the reference's title + container + year when structured, else the
  // free-text citation. Crossref matches this loosely and ranks by score.
  private static String bibliographicQuery(Reference r) {
    String title = blankToNull(r.getTitle());
    if (title == null) {
      return blankToNull(r.getCitation());
    }
    StringBuilder sb = new StringBuilder(title);
    String container = blankToNull(r.getContainerTitle());
    if (container != null) {
      sb.append(' ').append(container);
    }
    String year = blankToNull(r.getIssued());
    if (year != null) {
      sb.append(' ').append(year);
    }
    return sb.toString();
  }

  // Crossref query.author: the reference's author family (or literal) names, space-joined.
  private static String authorQuery(List<CslName> authors) {
    if (authors == null || authors.isEmpty()) {
      return null;
    }
    List<String> parts = new ArrayList<>();
    for (CslName a : authors) {
      String name = a.getFamily() != null ? a.getFamily() : a.getLiteral();
      if (name != null && !name.isBlank()) {
        parts.add(name.trim());
      }
    }
    return parts.isEmpty() ? null : String.join(" ", parts);
  }

  private static String blankToNull(String s) {
    return (s == null || s.isBlank()) ? null : s.trim();
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
