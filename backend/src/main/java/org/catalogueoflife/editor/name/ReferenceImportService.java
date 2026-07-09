package org.catalogueoflife.editor.name;

import java.util.ArrayList;
import java.util.List;
import org.catalogueoflife.editor.name.dto.CreateReferenceRequest;
import org.catalogueoflife.editor.project.ProjectService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

// DOI (Crossref) resolution + BibTeX import, mapping both into the normal ReferenceService.create
// path (which enforces owner/editor + auditing + validation).
@Service
public class ReferenceImportService {

  private final CrossrefClient crossref;
  private final ReferenceService references;
  private final ProjectService projects;

  public ReferenceImportService(CrossrefClient crossref, ReferenceService references,
      ProjectService projects) {
    this.crossref = crossref;
    this.references = references;
    this.projects = projects;
  }

  // Resolve a DOI to an UNSAVED CreateReferenceRequest preview (the UI reviews it before saving).
  public CreateReferenceRequest resolveDoi(int userId, int projectId, String doi) {
    projects.requireRole(userId, projectId); // any member may look up a DOI
    if (doi == null || doi.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "doi required");
    }
    return RefMapping.fromCrossref(crossref.fetchWork(doi.trim()));
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
}
