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

// Task 4 Step 7's proof: when the archive has separate Name.tsv + Taxon.tsv + Synonym.tsv files
// instead of a combined NameUsage.tsv, ImportRunService.readSplitFormRows joins each Taxon/Synonym
// row to its Name (via nameID) into the SAME synthetic combined-row shape readCombinedRows
// produces from a real NameUsage.tsv, so the rest of loadNameUsages (Pass 1/2, status inverse,
// pro-parte re-merge) runs unmodified either way. Fixture: 3 Name rows, 2 Taxon rows (an accepted
// parent/child pair) and 2 Synonym rows that BOTH reuse the SAME Name (n3, "Felis leo") --
// proving a Name shared by N usages correctly yields N separate name_usage rows, one per
// Taxon/Synonym row referencing it (not one, deduplicated).
@AutoConfigureMockMvc
class ImportSplitFormIT extends AbstractPostgresIT {

  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ObjectMapper json;
  @Autowired NameUsageMapper usages;
  @Autowired SynonymAcceptedMapper synonymAccepted;
  @Autowired ReferenceMapper references;
  @Autowired org.catalogueoflife.editor.child.NameRelationMapper nameRelations;

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

  // Name.tsv: n1 (Animalia), n2 (Panthera leo, carrying its own publishedIn reference/year),
  // n3 (Felis leo -- reused by BOTH synonym rows below). Taxon.tsv: t1 (root) / t2 (child of t1,
  // nameID=n2). Synonym.tsv: s1 (nameID=n3, taxonID=t2) and s2 (nameID=n3, taxonID=t1) -- same
  // Name, two different accepted targets, so this must NOT collapse into one usage (that pro-parte
  // shape only applies to the "<n>-<m>" derived-id convention, which split-form archives don't use
  // at all).
  private byte[] buildArchive(Path dir) throws IOException {
    ColdpMetadata.write(dir, new ColdpMetadataDto("Split Form Checklist", null, null, null, null, null));

    Map<ColdpTerm, String> ref1 = row(ColdpTerm.ID, "1", ColdpTerm.citation, "Linnaeus 1758");
    ColdpTsv.writeFile(dir, ColdpTerm.Reference, List.of(ref1));

    Map<ColdpTerm, String> n1 = row(ColdpTerm.ID, "n1", ColdpTerm.scientificName, "Animalia",
        ColdpTerm.rank, "kingdom");
    Map<ColdpTerm, String> n2 = row(ColdpTerm.ID, "n2", ColdpTerm.scientificName, "Panthera leo",
        ColdpTerm.authorship, "(Linnaeus, 1758)", ColdpTerm.rank, "species",
        ColdpTerm.referenceID, "1", ColdpTerm.publishedInYear, "1758");
    Map<ColdpTerm, String> n3 = row(ColdpTerm.ID, "n3", ColdpTerm.scientificName, "Felis leo",
        ColdpTerm.authorship, "Linnaeus, 1758", ColdpTerm.rank, "species");
    ColdpTsv.writeFile(dir, ColdpTerm.Name, List.of(n1, n2, n3));

    Map<ColdpTerm, String> t1 = row(ColdpTerm.ID, "t1", ColdpTerm.nameID, "n1");
    Map<ColdpTerm, String> t2 = row(ColdpTerm.ID, "t2", ColdpTerm.nameID, "n2", ColdpTerm.parentID, "t1");
    ColdpTsv.writeFile(dir, ColdpTerm.Taxon, List.of(t1, t2));

    Map<ColdpTerm, String> s1 = row(ColdpTerm.ID, "s1", ColdpTerm.nameID, "n3", ColdpTerm.taxonID, "t2");
    Map<ColdpTerm, String> s2 = row(ColdpTerm.ID, "s2", ColdpTerm.nameID, "n3", ColdpTerm.taxonID, "t1");
    ColdpTsv.writeFile(dir, ColdpTerm.Synonym, List.of(s1, s2));

    Path zip = dir.resolveSibling(dir.getFileName() + ".zip");
    ColdpZip.zipFolder(dir, zip);
    return Files.readAllBytes(zip);
  }

  @Test
  @WithMockUser(username = "importSplitFormOwner")
  void flattensNamePlusTaxonPlusSynonymIntoCombinedUsagesAndLinks(@TempDir Path tmp) throws Exception {
    ensureUser("importSplitFormOwner");

    Path dir = tmp.resolve("archive");
    Files.createDirectories(dir);
    byte[] zipBytes = buildArchive(dir);
    MockMultipartFile file = new MockMultipartFile("file", "splitform.zip", "application/zip", zipBytes);

    String startBody = mvc.perform(multipart("/api/projects/import").file(file).with(csrf()))
        .andExpect(status().isAccepted())
        .andReturn().getResponse().getContentAsString();
    long runId = json.readTree(startBody).get("id").asLong();

    JsonNode done = pollUntilTerminal(runId);
    assertThat(done.get("status").asString()).isEqualTo("DONE");
    assertThat(done.get("error").isNull()).isTrue();
    int projectId = done.get("projectId").asInt();

    // t1, t2, s1, s2 -- 4 Taxon/Synonym rows, 4 name_usage rows (Felis leo is NOT deduplicated
    // even though both s1 and s2 share the same Name row n3).
    assertThat(done.get("nameUsageCount").asInt()).isEqualTo(4);
    List<NameUsage> all = usages.findAllByProject(projectId);
    assertThat(all).hasSize(4);

    List<NameUsage> felisLeoUsages = all.stream()
        .filter(u -> "Felis leo".equals(u.getScientificName())).toList();
    assertThat(felisLeoUsages).hasSize(2);

    NameUsage animalia = byName(all, "Animalia");
    NameUsage pantheraLeo = byName(all, "Panthera leo");

    // Taxon.parentID -> classification parent_id.
    assertThat(animalia.getStatus()).isEqualTo(Status.ACCEPTED);
    assertThat(animalia.getParentId()).isNull();
    assertThat(pantheraLeo.getStatus()).isEqualTo(Status.ACCEPTED);
    assertThat(pantheraLeo.getParentId()).isEqualTo(animalia.getId());

    // Name.referenceID/publishedInYear (Name.tsv's OWN column names) correctly renamed into the
    // combined nameReferenceID/namePublishedInYear columns and remapped to the new reference id.
    List<Reference> refs = references.findAllByProject(projectId);
    Reference ref1 = refs.stream().filter(r -> "Linnaeus 1758".equals(r.getCitation())).findFirst().orElseThrow();
    assertThat(pantheraLeo.getPublishedInReferenceId()).isEqualTo(ref1.getId());
    assertThat(pantheraLeo.getPublishedInYear()).isEqualTo(1758);

    // Synonym.taxonID -> the combined row's parentID -> a synonym_accepted link (status defaults
    // to "synonym" since neither Synonym row sets its own status column), one per Felis leo usage,
    // each pointing at ITS OWN taxonID target.
    for (NameUsage felisLeo : felisLeoUsages) {
      assertThat(felisLeo.getStatus()).isEqualTo(Status.SYNONYM);
      assertThat(felisLeo.getParentId()).isNull();
    }
    NameUsage synOfPantheraLeo = felisLeoUsages.stream()
        .filter(u -> synonymAccepted.findAcceptedFor(projectId, u.getId()).contains(pantheraLeo.getId()))
        .findFirst().orElseThrow(() -> new AssertionError("no Felis leo usage linked to Panthera leo"));
    NameUsage synOfAnimalia = felisLeoUsages.stream()
        .filter(u -> synonymAccepted.findAcceptedFor(projectId, u.getId()).contains(animalia.getId()))
        .findFirst().orElseThrow(() -> new AssertionError("no Felis leo usage linked to Animalia"));
    assertThat(synOfPantheraLeo.getId()).isNotEqualTo(synOfAnimalia.getId());
  }

  private static NameUsage byName(List<NameUsage> all, String scientificName) {
    return all.stream().filter(u -> scientificName.equals(u.getScientificName())).findFirst()
        .orElseThrow(() -> new AssertionError("no usage found for scientificName=" + scientificName));
  }

  // Review-fix coverage: in a split-form archive, Name.basionymID is a Name-id (n1 below), but
  // ctx.usageIds is keyed by Taxon/Synonym ids (t1) -- so a plain ctx.usageIds lookup for it always
  // misses even though the archive is perfectly valid. n1's own Taxon row is t1, so the fix's
  // nameIdToUsage fallback (built from the Name-id -> Taxon/Synonym-id side index readSplitFormRows
  // records) must bridge n2's basionymID="n1" to t1's resulting usage id, WITHOUT emitting a
  // false-positive "basionym not found" issue.
  private byte[] buildBasionymArchive(Path dir) throws IOException {
    ColdpMetadata.write(dir, new ColdpMetadataDto("Split Form Basionym Checklist", null, null, null, null, null));

    Map<ColdpTerm, String> n1 = row(ColdpTerm.ID, "n1", ColdpTerm.scientificName, "Panthera leo",
        ColdpTerm.authorship, "(Linnaeus, 1758)", ColdpTerm.rank, "species");
    Map<ColdpTerm, String> n2 = row(ColdpTerm.ID, "n2", ColdpTerm.scientificName, "Felis leo",
        ColdpTerm.authorship, "Linnaeus, 1758", ColdpTerm.rank, "species",
        ColdpTerm.basionymID, "n1");
    ColdpTsv.writeFile(dir, ColdpTerm.Name, List.of(n1, n2));

    Map<ColdpTerm, String> t1 = row(ColdpTerm.ID, "t1", ColdpTerm.nameID, "n1");
    Map<ColdpTerm, String> t2 = row(ColdpTerm.ID, "t2", ColdpTerm.nameID, "n2", ColdpTerm.parentID, "t1");
    ColdpTsv.writeFile(dir, ColdpTerm.Taxon, List.of(t1, t2));

    Path zip = dir.resolveSibling(dir.getFileName() + ".zip");
    ColdpZip.zipFolder(dir, zip);
    return Files.readAllBytes(zip);
  }

  @Test
  @WithMockUser(username = "importSplitFormBasionymOwner")
  void resolvesSplitFormBasionymFromNameIdToItsOwnUsage(@TempDir Path tmp) throws Exception {
    ensureUser("importSplitFormBasionymOwner");

    Path dir = tmp.resolve("archive");
    Files.createDirectories(dir);
    byte[] zipBytes = buildBasionymArchive(dir);
    MockMultipartFile file = new MockMultipartFile("file", "splitformbasionym.zip", "application/zip", zipBytes);

    String startBody = mvc.perform(multipart("/api/projects/import").file(file).with(csrf()))
        .andExpect(status().isAccepted())
        .andReturn().getResponse().getContentAsString();
    long runId = json.readTree(startBody).get("id").asLong();

    JsonNode done = pollUntilTerminal(runId);
    assertThat(done.get("status").asString()).isEqualTo("DONE");
    assertThat(done.get("error").isNull()).isTrue();
    int projectId = done.get("projectId").asInt();

    assertThat(done.get("nameUsageCount").asInt()).isEqualTo(2);
    List<NameUsage> all = usages.findAllByProject(projectId);
    NameUsage pantheraLeo = byName(all, "Panthera leo");
    NameUsage felisLeo = byName(all, "Felis leo");

    // The whole point of the fix: basionym resolves to Panthera leo's usage id, even though
    // Felis leo's Name.basionymID="n1" is a Name-id, not a Taxon-id.
    assertThat(nameRelations.findByUsage(felisLeo.getProjectId(), felisLeo.getId()))
        .anySatisfy(r -> {
          assertThat(r.type()).isEqualToIgnoringCase("basionym");
          assertThat(r.relatedUsageId()).isEqualTo(pantheraLeo.getId());
        });

    // ...and no false-positive "basionym not found" issue was raised for it.
    JsonNode issues = done.get("issues");
    assertThat(issues).isNotNull();
    for (JsonNode issue : issues) {
      assertThat(issue.get("message").asString())
          .as("no basionym-not-found issue expected, got: " + issue)
          .doesNotContain("basionym");
    }
  }
}
