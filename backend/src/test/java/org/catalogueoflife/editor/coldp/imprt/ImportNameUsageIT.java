package org.catalogueoflife.editor.coldp.imprt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import life.catalogue.coldp.ColdpTerm;
import org.catalogueoflife.editor.coldp.io.ColdpMetadata;
import org.catalogueoflife.editor.coldp.io.ColdpMetadata.ColdpMetadataDto;
import org.catalogueoflife.editor.coldp.io.ColdpTsv;
import org.catalogueoflife.editor.coldp.io.ColdpZip;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.name.NameUsageMapper;
import org.catalogueoflife.editor.name.Reference;
import org.catalogueoflife.editor.name.ReferenceMapper;
import org.catalogueoflife.editor.name.Status;
import org.catalogueoflife.editor.name.SynonymAcceptedMapper;
import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// Task 4's end-to-end proof: ImportRunService.loadNameUsages (via self.run -> loadTransactional)
// loads a combined NameUsage.tsv, inverting NameUsageColdpWriter field-for-field, through the
// two-phase parent_id/basionym_id remap and the status inverse (a non-accepted row's parentID
// becomes a synonym_accepted link, never a classification parent_id). The fixture is a single
// archive exercising every subtle point called out in the design brief at once:
//  - an accepted chain: Animalia(1,kingdom) -> Felidae(2,family) -> Panthera(3,genus)
//    -> Panthera leo(4,species), with Panthera leo's parentID/basionymID BOTH forward references
//    (basionymID=10 lists AFTER row 4 in the file) -- proof the two-phase insert tolerates any
//    row order, not just a topologically sorted one.
//  - a plain synonym, Felis leo(10), synonym of Panthera leo(4) -- and simultaneously Panthera
//    leo's basionym.
//  - a pro-parte synonym, Leo pardus: primary row ID=42 (parentID=3, the LOWER of its two accepted
//    targets, mirroring NameUsageColdpWriter.synonymRows) plus a derived row ID="42-4"
//    (parentID=4) -- must collapse to ONE name_usage row carrying TWO synonym_accepted links.
//  - an UNASSESSED-with-parentID row, Felis obscura(50), status "provisionally accepted",
//    parentID=4 -- must become a synonym_accepted link (parent_id stays null), never a
//    classification parent.
//  - a dangling parentID, Nonexistens vagus(70) -> parentID "9999" (never defined) -- must
//    surface as an ImportIssue in the run's issues, not fail the whole import.
//  - Panthera leo(4) also carries nameReferenceID="1" (single) and referenceID="1,2" (list),
//    remapped through the References already loaded earlier in the same transaction (Task 3).
@AutoConfigureMockMvc
class ImportNameUsageIT extends AbstractPostgresIT {

  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ObjectMapper json;
  @Autowired NameUsageMapper usages;
  @Autowired SynonymAcceptedMapper synonymAccepted;
  @Autowired ReferenceMapper references;

  private void ensureUser(String username) {
    AppUser existing = users.requireByUsernameOrNull(username);
    if (existing == null) users.createLocal(username, "pw", username);
  }

  private JsonNode getRun(long runId) throws Exception {
    String body = mvc.perform(get("/api/projects/import/" + runId))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    return json.readTree(body);
  }

  private JsonNode pollUntilTerminal(long runId) throws Exception {
    Instant deadline = Instant.now().plus(TIMEOUT);
    JsonNode last;
    do {
      last = getRun(runId);
      if (!"RUNNING".equals(last.get("status").asString())) {
        return last;
      }
      Thread.sleep(POLL_INTERVAL.toMillis());
    } while (Instant.now().isBefore(deadline));
    throw new AssertionError("run did not finish within " + TIMEOUT + "; last GET = " + last);
  }

  private static Map<ColdpTerm, String> row(Object... kv) {
    Map<ColdpTerm, String> row = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      row.put((ColdpTerm) kv[i], (String) kv[i + 1]);
    }
    return row;
  }

  private byte[] buildArchive(Path dir) throws IOException {
    ColdpMetadata.write(dir, new ColdpMetadataDto("NameUsage Checklist", null, null, null, null, null));

    Map<ColdpTerm, String> ref1 = row(ColdpTerm.ID, "1", ColdpTerm.citation, "Linnaeus 1758");
    Map<ColdpTerm, String> ref2 = row(ColdpTerm.ID, "2", ColdpTerm.citation, "Extra Reference 2020");
    ColdpTsv.writeFile(dir, ColdpTerm.Reference, List.of(ref1, ref2));

    Map<ColdpTerm, String> animalia = row(
        ColdpTerm.ID, "1", ColdpTerm.scientificName, "Animalia", ColdpTerm.rank, "kingdom",
        ColdpTerm.status, "accepted", ColdpTerm.code, "zoological");
    Map<ColdpTerm, String> felidae = row(
        ColdpTerm.ID, "2", ColdpTerm.scientificName, "Felidae", ColdpTerm.rank, "family",
        ColdpTerm.status, "accepted", ColdpTerm.parentID, "1", ColdpTerm.code, "zoological");
    Map<ColdpTerm, String> panthera = row(
        ColdpTerm.ID, "3", ColdpTerm.scientificName, "Panthera", ColdpTerm.rank, "genus",
        ColdpTerm.status, "accepted", ColdpTerm.parentID, "2", ColdpTerm.code, "zoological");
    Map<ColdpTerm, String> pantheraLeo = row(
        ColdpTerm.ID, "4", ColdpTerm.scientificName, "Panthera leo", ColdpTerm.authorship, "(Linnaeus, 1758)",
        ColdpTerm.rank, "species", ColdpTerm.status, "accepted", ColdpTerm.parentID, "3",
        ColdpTerm.basionymID, "10", ColdpTerm.nameReferenceID, "1", ColdpTerm.namePublishedInYear, "1758",
        ColdpTerm.referenceID, "1,2", ColdpTerm.code, "zoological");
    Map<ColdpTerm, String> felisLeo = row(
        ColdpTerm.ID, "10", ColdpTerm.scientificName, "Felis leo", ColdpTerm.authorship, "Linnaeus, 1758",
        ColdpTerm.rank, "species", ColdpTerm.status, "synonym", ColdpTerm.parentID, "4",
        ColdpTerm.code, "zoological");
    Map<ColdpTerm, String> leoPardusPrimary = row(
        ColdpTerm.ID, "42", ColdpTerm.scientificName, "Leo pardus", ColdpTerm.authorship, "Someone, 1900",
        ColdpTerm.rank, "species", ColdpTerm.status, "synonym", ColdpTerm.parentID, "3",
        ColdpTerm.code, "zoological");
    Map<ColdpTerm, String> leoPardusDerived = row(
        ColdpTerm.ID, "42-4", ColdpTerm.scientificName, "Leo pardus", ColdpTerm.authorship, "Someone, 1900",
        ColdpTerm.rank, "species", ColdpTerm.status, "synonym", ColdpTerm.parentID, "4",
        ColdpTerm.code, "zoological");
    Map<ColdpTerm, String> felisObscura = row(
        ColdpTerm.ID, "50", ColdpTerm.scientificName, "Felis obscura", ColdpTerm.authorship, "Doe, 1850",
        ColdpTerm.rank, "species", ColdpTerm.status, "provisionally accepted", ColdpTerm.parentID, "4",
        ColdpTerm.code, "zoological");
    Map<ColdpTerm, String> danglingParent = row(
        ColdpTerm.ID, "70", ColdpTerm.scientificName, "Nonexistens vagus", ColdpTerm.rank, "species",
        ColdpTerm.status, "synonym", ColdpTerm.parentID, "9999", ColdpTerm.code, "zoological");

    ColdpTsv.writeFile(dir, ColdpTerm.NameUsage, List.of(
        animalia, felidae, panthera, pantheraLeo, felisLeo,
        leoPardusPrimary, leoPardusDerived, felisObscura, danglingParent));

    Path zip = dir.resolveSibling(dir.getFileName() + ".zip");
    ColdpZip.zipFolder(dir, zip);
    return Files.readAllBytes(zip);
  }

  @Test
  @WithMockUser(username = "importNameUsageOwner")
  void importsNameUsagesWithTwoPhaseRemapAndStatusInverse(@TempDir Path tmp) throws Exception {
    ensureUser("importNameUsageOwner");

    Path dir = tmp.resolve("archive");
    Files.createDirectories(dir);
    byte[] zipBytes = buildArchive(dir);
    MockMultipartFile file = new MockMultipartFile("file", "nameusage.zip", "application/zip", zipBytes);

    String startBody = mvc.perform(multipart("/api/projects/import").file(file).with(csrf()))
        .andExpect(status().isAccepted())
        .andReturn().getResponse().getContentAsString();
    long runId = json.readTree(startBody).get("id").asLong();

    JsonNode done = pollUntilTerminal(runId);
    assertThat(done.get("status").asString()).isEqualTo("DONE");
    assertThat(done.get("error").isNull()).isTrue();
    int projectId = done.get("projectId").asInt();

    // 9 archive rows minus the 1 pro-parte derived row ("42-4") that collapses into its primary.
    assertThat(done.get("nameUsageCount").asInt()).isEqualTo(8);
    List<NameUsage> all = usages.findAllByProject(projectId);
    assertThat(all).hasSize(8);

    // The dangling parentID (row 70 -> "9999") surfaced as an issue, not a failure.
    JsonNode issues = done.get("issues");
    assertThat(issues).isNotNull();
    assertThat(issues.isArray()).isTrue();
    boolean danglingIssueFound = false;
    for (JsonNode issue : issues) {
      if ("70".equals(issue.get("sourceId").asString())) {
        danglingIssueFound = true;
        assertThat(issue.get("message").asString()).contains("9999");
      }
    }
    assertThat(danglingIssueFound).as("dangling parentID issue for row 70").isTrue();

    NameUsage animalia = byName(all, "Animalia");
    NameUsage felidae = byName(all, "Felidae");
    NameUsage panthera = byName(all, "Panthera");
    NameUsage pantheraLeo = byName(all, "Panthera leo");
    NameUsage felisLeo = byName(all, "Felis leo");
    NameUsage leoPardus = byName(all, "Leo pardus");
    NameUsage felisObscura = byName(all, "Felis obscura");
    NameUsage nonexistens = byName(all, "Nonexistens vagus");

    // Accepted classification chain resolved through Pass 2.
    assertThat(animalia.getStatus()).isEqualTo(Status.ACCEPTED);
    assertThat(animalia.getParentId()).isNull();
    assertThat(felidae.getParentId()).isEqualTo(animalia.getId());
    assertThat(panthera.getParentId()).isEqualTo(felidae.getId());
    assertThat(pantheraLeo.getParentId()).isEqualTo(panthera.getId());

    // Forward basionym reference (row 4 -> row 10, which appears LATER in the file) resolved.
    assertThat(pantheraLeo.getBasionymId()).isEqualTo(felisLeo.getId());

    // Published-in single reference + taxonomic reference_id[] remapped to the new reference ids.
    List<Reference> refs = references.findAllByProject(projectId);
    Reference ref1 = refs.stream().filter(r -> "Linnaeus 1758".equals(r.getCitation())).findFirst().orElseThrow();
    Reference ref2 = refs.stream().filter(r -> "Extra Reference 2020".equals(r.getCitation())).findFirst().orElseThrow();
    assertThat(pantheraLeo.getPublishedInReferenceId()).isEqualTo(ref1.getId());
    assertThat(pantheraLeo.getReferenceId()).containsExactlyInAnyOrder(ref1.getId(), ref2.getId());

    // Plain synonym: status flips to SYNONYM, parent_id stays null, and its parentID became a
    // synonym_accepted link instead (the status-inverse point).
    assertThat(felisLeo.getStatus()).isEqualTo(Status.SYNONYM);
    assertThat(felisLeo.getParentId()).isNull();
    assertThat(synonymAccepted.findAcceptedFor(projectId, felisLeo.getId())).containsExactly(pantheraLeo.getId());

    // Pro-parte re-merge: ONE usage for "Leo pardus" (the primary row's id), carrying BOTH
    // accepted links -- the derived "42-4" row never became its own name_usage.
    assertThat(leoPardus.getStatus()).isEqualTo(Status.SYNONYM);
    assertThat(leoPardus.getParentId()).isNull();
    assertThat(synonymAccepted.findAcceptedFor(projectId, leoPardus.getId()))
        .containsExactlyInAnyOrder(panthera.getId(), pantheraLeo.getId());
    assertThat(all.stream().filter(u -> "Leo pardus".equals(u.getScientificName())).toList()).hasSize(1);

    // UNASSESSED-with-parentID: exported as parentID=<accepted> + "provisionally accepted", so on
    // import that parentID must become a synonym link too, NOT a classification parent_id.
    assertThat(felisObscura.getStatus()).isEqualTo(Status.UNASSESSED);
    assertThat(felisObscura.getParentId()).isNull();
    assertThat(synonymAccepted.findAcceptedFor(projectId, felisObscura.getId()))
        .containsExactly(pantheraLeo.getId());

    // Dangling parentID: no crash, no link, just the issue asserted above.
    assertThat(nonexistens.getParentId()).isNull();
    assertThat(synonymAccepted.findAcceptedFor(projectId, nonexistens.getId())).isEmpty();
  }

  private static NameUsage byName(List<NameUsage> all, String scientificName) {
    return all.stream().filter(u -> scientificName.equals(u.getScientificName())).findFirst()
        .orElseThrow(() -> new AssertionError("no usage found for scientificName=" + scientificName));
  }
}
