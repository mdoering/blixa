package org.catalogueoflife.editor.name;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import life.catalogue.api.model.VerbatimRecord;
import life.catalogue.coldp.ColdpTerm;
import life.catalogue.csv.ColdpReader;
import org.catalogueoflife.editor.coldp.export.ColdpWriter;
import org.catalogueoflife.editor.coldp.io.ColdpZip;
import org.catalogueoflife.editor.name.dto.CreateReferenceRequest;
import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// Task 2 of the Reference PDF+RIS plan: reference PDF hosting. Two facets covered here --
// (1) the full HTTP round trip: authenticated attach -> unauthenticated public download -> delete
// -> download now 404 (attachGetDeletePdf), and (2) ReferenceColdpWriter's export `link` fallback
// rule: a hosted pdf only ever FILLS a blank link, never overrides one the user set (exportLink...).
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReferencePdfIT extends AbstractPostgresIT {

  private static final byte[] VALID_PDF =
      "%PDF-1.4\n1 0 obj\n<<>>\nendobj\n%%EOF".getBytes(StandardCharsets.US_ASCII);

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ObjectMapper json;
  @Autowired ReferenceService referenceService;
  @Autowired ColdpWriter writer;

  private void ensureUser(String username) {
    AppUser existing = users.requireByUsernameOrNull(username);
    if (existing == null) users.createLocal(username, "pw", username);
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
  @WithMockUser(username = "pdfOwner")
  void attachGetDeletePdf() throws Exception {
    ensureUser("pdfOwner");
    long pid = createProject("pdfproj");

    String createBody = mvc.perform(post("/api/projects/" + pid + "/references").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"citation\":\"Reprint, A. 2024. A reprinted paper.\"}"))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    JsonNode created = json.readTree(createBody);
    long refId = created.get("id").asLong();
    assertThat(created.get("pdfUrl").isNull()).as("no pdf yet").isTrue();

    MockMultipartFile file =
        new MockMultipartFile("file", "reprint.pdf", "application/pdf", VALID_PDF);
    String attachBody = mvc.perform(
            multipart("/api/projects/" + pid + "/references/" + refId + "/pdf").file(file).with(csrf()))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    JsonNode attached = json.readTree(attachBody);
    assertThat(attached.get("pdfUrl").isNull()).as("attach sets pdfUrl").isFalse();
    String pdfUrl = attached.get("pdfUrl").asString();
    assertThat(pdfUrl).startsWith("/pdf/").endsWith(".pdf");
    String filename = pdfUrl.substring("/pdf/".length());

    // Public download: the whole test method runs as @WithMockUser("pdfOwner"), so a plain
    // mvc.perform(get(...)) here would silently inherit that principal and pass whether /pdf/** is
    // permitAll OR authenticated -- it would prove nothing about SecurityConfig. .with(anonymous())
    // forces THIS request's SecurityContext to an AnonymousAuthenticationToken, overriding the
    // method-level @WithMockUser for just this call, so a 200 here is a genuine proof that
    // SecurityConfig's /pdf/** permitAll applies: were it authenticated() instead, this request
    // would 401.
    mvc.perform(get("/pdf/" + filename).with(anonymous()))
        .andExpect(status().isOk())
        .andExpect(content().contentType("application/pdf"))
        .andExpect(content().bytes(VALID_PDF))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"));

    String removeBody = mvc.perform(
            delete("/api/projects/" + pid + "/references/" + refId + "/pdf").with(csrf()))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    assertThat(json.readTree(removeBody).get("pdfUrl").isNull()).as("delete clears pdfUrl").isTrue();

    // The file itself is gone once removed from the reference -- same anonymous proof as above.
    mvc.perform(get("/pdf/" + filename).with(anonymous())).andExpect(status().isNotFound());
  }

  @Test
  @WithMockUser(username = "pdfExportOwner")
  void exportLinkFallsBackToPdfUrlOnlyWhenLinkIsBlank(@TempDir Path tmp) throws Exception {
    ensureUser("pdfExportOwner");
    int userId = users.requireByUsernameOrNull("pdfExportOwner").getId();
    long pidLong = createProject("pdfexportproj");
    int pid = (int) pidLong;

    // Blank link -> the hosted pdf's URL fills it in on export.
    Reference blankLink = referenceService.create(userId, pid, new CreateReferenceRequest(
        "Blank, A. 2024. No link set.", null, null, null, null, null, null, null, null,
        null, null, null, null, null, null, null, null));
    // A user-set link -> export must keep it untouched even though a pdf is also attached.
    Reference ownLink = referenceService.create(userId, pid, new CreateReferenceRequest(
        "Owned, B. 2024. Has its own link.", null, null, null, null, null, null, null, null,
        null, null, null, null, null, "https://example.org/own-link", null, null));

    MockMultipartFile file =
        new MockMultipartFile("file", "reprint.pdf", "application/pdf", VALID_PDF);
    referenceService.attachPdf(userId, pid, blankLink.getId(), file);
    referenceService.attachPdf(userId, pid, ownLink.getId(), file);

    Path targetZip = tmp.resolve("export.zip");
    writer.write(pid, targetZip);
    Path extractDir = tmp.resolve("extracted");
    try (InputStream in = Files.newInputStream(targetZip)) {
      ColdpZip.extractToTemp(in, extractDir);
    }
    ColdpReader reader = ColdpReader.from(extractDir);
    List<VerbatimRecord> recs = reader.stream(ColdpTerm.Reference).toList();

    VerbatimRecord blankRec = findOneById(recs, blankLink.getId());
    assertThat(blankRec.get(ColdpTerm.link)).startsWith("/pdf/").endsWith(".pdf");

    VerbatimRecord ownRec = findOneById(recs, ownLink.getId());
    assertThat(ownRec.get(ColdpTerm.link)).isEqualTo("https://example.org/own-link");
  }

  private static VerbatimRecord findOneById(List<VerbatimRecord> recs, int id) {
    List<VerbatimRecord> matches =
        recs.stream().filter(r -> String.valueOf(id).equals(r.get(ColdpTerm.ID))).toList();
    assertThat(matches).as("rows for ID=" + id).hasSize(1);
    return matches.get(0);
  }
}
