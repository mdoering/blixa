package org.catalogueoflife.editor.name;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import life.catalogue.common.csl.CslFormatter;
import life.catalogue.common.csl.CslFormatter.FORMAT;
import life.catalogue.common.csl.CslFormatter.STYLE;
import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// Task 3 of the reference-model-overhaul plan (docs/superpowers/plans/2026-07-12-reference-model-
// csl.md): a structured reference's `citation` is now GENERATED via CLB's citeproc engine
// (ReferenceCitationService) in the project's csl_style (default "apa", see
// V25__project_csl_style.sql), instead of trusted verbatim from the caller -- unless
// citationManual=true, in which case the caller's string is authoritative and never touched
// (ReferenceService.applyCitation).
@AutoConfigureMockMvc
class ReferenceCitationIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ReferenceMapper references;
  @Autowired IdSeqMapper idSeq;
  @Autowired ObjectMapper json;

  private void ensureUser(String username) {
    if (users.requireByUsernameOrNull(username) == null) users.createLocal(username, "pw", username);
  }

  private long createProject(String title) throws Exception {
    String body = mvc.perform(post("/api/projects").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"" + title + "\",\"nomCode\":\"zoological\"}"))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    return json.readTree(body).get("id").asLong();
  }

  @Test
  @WithMockUser(username = "citeOwner")
  void generatesApaCitationAndRegeneratesOnStyleChange() throws Exception {
    ensureUser("citeOwner");
    long pid = createProject("citeproj");

    // default project style is "apa" (V25's DB default).
    mvc.perform(get("/api/projects/" + pid))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cslStyle").value("apa"));

    String createPayload = """
        {
          "type": "article-journal",
          "author": [{"family": "Bánki", "given": "Olaf"}],
          "title": "Catalogue of Life",
          "containerTitle": "Biodiversity Data Journal",
          "issued": "2026"
        }
        """;
    String createBody = mvc.perform(post("/api/projects/" + pid + "/references").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(createPayload))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.citationManual").value(false))
        .andReturn().getResponse().getContentAsString();
    JsonNode created = json.readTree(createBody);
    long refId = created.get("id").asLong();
    String apaCitation = created.get("citation").asString();
    assertThat(apaCitation).isNotBlank().contains("Bánki").contains("2026");

    // exact match against CLB's own APA renderer, fed the very same CslData the service built --
    // toCslData is package-private specifically so this test can do that (see its javadoc).
    Reference stored = references.findByIdInProject((int) pid, (int) refId);
    String expectedApa = new CslFormatter(STYLE.APA, FORMAT.TEXT).cite(ReferenceCitationService.toCslData(stored));
    assertThat(apaCitation).isEqualTo(expectedApa);

    // switching the project's csl_style regenerates every non-manual reference's citation
    mvc.perform(put("/api/projects/" + pid + "/metadata").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"citeproj\",\"cslStyle\":\"harvard\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cslStyle").value("harvard"));

    Reference afterStyleChange = references.findByIdInProject((int) pid, (int) refId);
    assertThat(afterStyleChange.getCitation()).isNotBlank().isNotEqualTo(apaCitation);
    String expectedHarvard = new CslFormatter(STYLE.HARVARD, FORMAT.TEXT)
        .cite(ReferenceCitationService.toCslData(afterStyleChange));
    assertThat(afterStyleChange.getCitation()).isEqualTo(expectedHarvard);
  }

  @Test
  @WithMockUser(username = "citeManualOwner")
  void manualCitationIsNeverRegenerated() throws Exception {
    ensureUser("citeManualOwner");
    long pid = createProject("citemanualproj");

    String createPayload = """
        {
          "citation": "Hand-typed citation, kept exactly",
          "citationManual": true,
          "type": "article-journal",
          "author": [{"family": "Someone"}],
          "title": "Ignored for citation purposes",
          "issued": "2020"
        }
        """;
    String createBody = mvc.perform(post("/api/projects/" + pid + "/references").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(createPayload))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.citationManual").value(true))
        .andExpect(jsonPath("$.citation").value("Hand-typed citation, kept exactly"))
        .andReturn().getResponse().getContentAsString();
    long refId = json.readTree(createBody).get("id").asLong();

    // update: still manual, still unchanged, even though structured fields (that COULD generate a
    // citation) are present in the payload -- citationManual=true wins.
    mvc.perform(put("/api/projects/" + pid + "/references/" + refId).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "citation": "Hand-typed citation, kept exactly",
                  "citationManual": true,
                  "type": "article-journal",
                  "author": [{"family": "Someone"}],
                  "title": "Ignored for citation purposes",
                  "issued": "2020",
                  "version": 0
                }
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.citationManual").value(true))
        .andExpect(jsonPath("$.citation").value("Hand-typed citation, kept exactly"));

    // a project-wide style change does not touch it either.
    mvc.perform(put("/api/projects/" + pid + "/metadata").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"citemanualproj\",\"cslStyle\":\"harvard\"}"))
        .andExpect(status().isOk());
    Reference stored = references.findByIdInProject((int) pid, (int) refId);
    assertThat(stored.getCitation()).isEqualTo("Hand-typed citation, kept exactly");
    assertThat(stored.isCitationManual()).isTrue();
  }

  @Test
  @WithMockUser(username = "citeStyleOwner")
  void unknownCslStyleIsRejected() throws Exception {
    ensureUser("citeStyleOwner");
    long pid = createProject("citestyleproj");

    mvc.perform(put("/api/projects/" + pid + "/metadata").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"citestyleproj\",\"cslStyle\":\"not-a-style\"}"))
        .andExpect(status().isBadRequest());
  }

  // Regression test for the data-loss bug fixed alongside this test: ProjectService.
  // regenerateCitations used to regenerate EVERY non-manual reference on a project cslStyle change,
  // with no structured-content guard. Import paths (ImportRunService.loadReferences,
  // ClbImportService/ClbUsageMapper.toReference) insert references DIRECTLY via ReferenceMapper.
  // insert, bypassing ReferenceService.applyCitation entirely -- so a citation-only imported
  // reference (only `citation` set, everything structured left null) keeps citationManual=false
  // (the primitive default), NOT true. Before the fix, a style change would call render() on that
  // reference's all-null CslData, get nothing back from citeproc, fall through to the minimal
  // "author (year) title" fallback (empty, since there's no author/year/title to build one from),
  // and persist that "" straight over the original citation via updateCitation -- silently
  // destroying the only citation text the reference ever had. The fix adds an isStructured guard to
  // regenerateCitations (mirroring applyCitation's own gate) AND, as defense in depth, makes
  // ReferenceCitationService.render prefer the existing ref.getCitation() over the blank fallback.
  @Test
  @WithMockUser(username = "citeImportOwner")
  void styleChangeDoesNotBlankCitationOnlyImportedReference() throws Exception {
    ensureUser("citeImportOwner");
    long pid = createProject("citeimportproj");
    int userId = users.requireByUsernameOrNull("citeImportOwner").getId();

    // Mirrors ImportRunService.loadReferences' insert exactly: only `citation` is populated, every
    // structured field (author/title/containerTitle/type) stays null, and citationManual is never
    // touched -- so it keeps its primitive default of false. Deliberately NOT built via POST
    // /references, which would route through ReferenceService.applyCitation and flip
    // citationManual to true for this exact shape (see applyCitation's javadoc, case 3) -- that's
    // the normal API path, not the import path this test reproduces.
    String originalCitation = "Smith, J. (1999). Some old citation.";
    Reference imported = new Reference();
    imported.setProjectId((int) pid);
    imported.setId(idSeq.allocate((int) pid, "reference"));
    imported.setCitation(originalCitation);
    imported.setModifiedBy(userId);
    references.insert(imported);
    assertThat(imported.isCitationManual()).isFalse();

    // A second, genuinely-structured non-manual reference in the SAME project, created through the
    // normal API path -- this one SHOULD be regenerated on the style change below, proving the new
    // guard doesn't over-suppress legitimate regeneration.
    String structuredPayload = """
        {
          "type": "article-journal",
          "author": [{"family": "Jones", "given": "A."}],
          "title": "A Structured Reference",
          "containerTitle": "Journal of Testing",
          "issued": "2010"
        }
        """;
    String structuredBody = mvc.perform(post("/api/projects/" + pid + "/references").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(structuredPayload))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.citationManual").value(false))
        .andReturn().getResponse().getContentAsString();
    long structuredRefId = json.readTree(structuredBody).get("id").asLong();
    String apaStructuredCitation = json.readTree(structuredBody).get("citation").asString();
    assertThat(apaStructuredCitation).isNotBlank();

    // default project style is "apa" -- switch to "harvard".
    mvc.perform(put("/api/projects/" + pid + "/metadata").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"citeimportproj\",\"cslStyle\":\"harvard\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cslStyle").value("harvard"));

    // the citation-only imported reference must be COMPLETELY UNCHANGED -- not blanked, not
    // regenerated (there's nothing structured to regenerate it from).
    Reference afterStyleChange = references.findByIdInProject((int) pid, imported.getId());
    assertThat(afterStyleChange.getCitation()).isEqualTo(originalCitation);
    assertThat(afterStyleChange.isCitationManual()).isFalse();

    // the structured reference DOES get regenerated in the new style -- the guard didn't
    // over-suppress legitimate regeneration.
    Reference structuredAfter = references.findByIdInProject((int) pid, (int) structuredRefId);
    assertThat(structuredAfter.getCitation()).isNotBlank().isNotEqualTo(apaStructuredCitation);
  }
}
