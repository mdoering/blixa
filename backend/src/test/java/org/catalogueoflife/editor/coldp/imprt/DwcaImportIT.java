package org.catalogueoflife.editor.coldp.imprt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// End-to-end proof that a Darwin Core Archive upload runs through the SAME import pipeline as ColDP
// (ImportRunService.start -> meta.xml sniff -> DwcaAdapter.materialize -> DwcaToColdp -> self.run),
// covering the interesting cases in one archive: normalized links (parentNameUsageID), a synonym
// (acceptedNameUsageID), the flat-classification fallback (synthesized higher taxa from the Linnaean
// columns), and the VernacularName / Distribution / SpeciesProfile extensions. Bounded poll like
// TxtTreeImportIT (no Awaitility dependency).
@AutoConfigureMockMvc
class DwcaImportIT extends AbstractPostgresIT {

  private static final Duration TIMEOUT = Duration.ofSeconds(30);
  private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

  private static final String TAXON = "http://rs.tdwg.org/dwc/terms/Taxon";
  private static final String DWC = "http://rs.tdwg.org/dwc/terms/";
  private static final String GBIF = "http://rs.gbif.org/terms/1.0/";
  private static final String DC = "http://purl.org/dc/terms/";

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ObjectMapper json;

  private void ensureUser(String username) {
    AppUser existing = users.requireByUsernameOrNull(username);
    if (existing == null) users.createLocal(username, "pw", username);
  }

  private JsonNode getRun(long runId) throws Exception {
    return json.readTree(mvc.perform(get("/api/projects/import/" + runId))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
  }

  private JsonNode pollUntilTerminal(long runId) throws Exception {
    Instant deadline = Instant.now().plus(TIMEOUT);
    JsonNode last;
    do {
      last = getRun(runId);
      if (!"RUNNING".equals(last.get("status").asString())) return last;
      Thread.sleep(POLL_INTERVAL.toMillis());
    } while (Instant.now().isBefore(deadline));
    throw new AssertionError("run did not finish within " + TIMEOUT + "; last GET = " + last);
  }

  @Test
  @WithMockUser(username = "dwcaImp")
  void importsDarwinCoreArchive() throws Exception {
    ensureUser("dwcaImp");
    byte[] zip = buildArchive();
    MockMultipartFile file = new MockMultipartFile("file", "checklist.zip",
        "application/zip", zip);

    String started = mvc.perform(multipart("/api/projects/import").file(file)
            .param("title", "DwcaChecklist").with(csrf()))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("RUNNING"))
        .andReturn().getResponse().getContentAsString();
    long runId = json.readTree(started).get("id").asLong();

    JsonNode done = pollUntilTerminal(runId);
    assertThat(done.get("status").asString()).as("import result: %s", done).isEqualTo("DONE");
    assertThat(done.get("error").isNull()).isTrue();
    // 4 core rows + 6 synthesized higher taxa (Animalia,Chordata,Mammalia,Carnivora,Felidae,Aus)
    assertThat(done.get("nameUsageCount").asInt()).isEqualTo(10);
    int pid = done.get("projectId").asInt();

    Map<String, JsonNode> byName = usagesByName(pid);
    // normalized: Panthera leo accepted under Panthera
    assertThat(byName).containsKey("Panthera");
    assertThat(byName).containsKey("Panthera leo");
    assertThat(byName.get("Panthera leo").get("status").asString()).isEqualTo("ACCEPTED");
    assertThat(byName.get("Panthera leo").get("parentId").asInt())
        .isEqualTo(byName.get("Panthera").get("id").asInt());

    // synonym: Felis leo -> Panthera leo (via acceptedNameUsageID)
    int leoId = byName.get("Panthera leo").get("id").asInt();
    JsonNode syns = json.readTree(mvc.perform(get("/api/projects/" + pid + "/usages/" + leoId + "/synonyms"))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    assertThat(syns.size()).isEqualTo(1);
    assertThat(syns.get(0).get("scientificName").asString()).isEqualTo("Felis leo");

    // flat fallback: Aus bus hangs under a synthesized genus Aus, itself under Felidae, up to Animalia
    assertThat(byName).containsKeys("Aus bus", "Aus", "Felidae", "Animalia");
    assertThat(byName.get("Aus").get("rank").asString()).isEqualTo("genus");
    assertThat(byName.get("Aus bus").get("parentId").asInt())
        .isEqualTo(byName.get("Aus").get("id").asInt());
    assertThat(byName.get("Aus").get("parentId").asInt())
        .isEqualTo(byName.get("Felidae").get("id").asInt());
    assertThat(byName.get("Animalia").get("parentId").isNull()).isTrue();

    // SpeciesProfile folded onto the core rows
    assertThat(byName.get("Aus bus").get("extinct").asBoolean()).isTrue();
    JsonNode leoEnv = byName.get("Panthera leo").get("environment");
    assertThat(leoEnv).isNotNull();
    assertThat(leoEnv.toString()).contains("TERRESTRIAL");

    // extensions attached to Panthera leo
    JsonNode verns = json.readTree(mvc.perform(get("/api/projects/" + pid + "/usages/" + leoId + "/vernaculars"))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    assertThat(verns.size()).isEqualTo(1);
    assertThat(verns.get(0).get("name").asString()).isEqualTo("Lion");
    JsonNode dists = json.readTree(mvc.perform(get("/api/projects/" + pid + "/usages/" + leoId + "/distributions"))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    assertThat(dists.size()).isEqualTo(1);
    assertThat(dists.get(0).get("area").asString()).isEqualTo("Africa");
  }

  private Map<String, JsonNode> usagesByName(int pid) throws Exception {
    JsonNode items = json.readTree(mvc.perform(get("/api/projects/" + pid + "/usages").param("limit", "100"))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("items");
    Map<String, JsonNode> byName = new LinkedHashMap<>();
    for (JsonNode item : items) {
      byName.put(item.get("scientificName").asString(), item);
    }
    return byName;
  }

  // ---- archive fixture ----

  private byte[] buildArchive() throws Exception {
    String meta = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        + "<archive xmlns=\"http://rs.tdwg.org/dwc/text/\">\n"
        + core()
        + ext(GBIF + "VernacularName", "vernacular.txt",
            DWC + "vernacularName", DC + "language")
        + ext(GBIF + "Distribution", "distribution.txt",
            DWC + "locality", DWC + "establishmentMeans")
        + ext(GBIF + "SpeciesProfile", "speciesprofile.txt",
            GBIF + "isMarine", GBIF + "isTerrestrial", GBIF + "isExtinct")
        + "</archive>\n";

    // taxa.txt: taxonID, scientificName, taxonRank, taxonomicStatus, parentNameUsageID,
    //           acceptedNameUsageID, kingdom, phylum, class, order, family, genus
    String taxa = "taxonID\tscientificName\ttaxonRank\ttaxonomicStatus\tparentNameUsageID\t"
        + "acceptedNameUsageID\tkingdom\tphylum\tclass\torder\tfamily\tgenus\n"
        + "1\tPanthera\tgenus\taccepted\t\t\t\t\t\t\t\t\n"
        + "2\tPanthera leo\tspecies\taccepted\t1\t\t\t\t\t\t\t\n"
        + "3\tFelis leo\tspecies\tsynonym\t\t2\t\t\t\t\t\t\n"
        + "4\tAus bus\tspecies\taccepted\t\t\tAnimalia\tChordata\tMammalia\tCarnivora\tFelidae\tAus\n";
    String vernacular = "taxonID\tvernacularName\tlanguage\n2\tLion\ten\n";
    String distribution = "taxonID\tlocality\testablishmentMeans\n2\tAfrica\tnative\n";
    String speciesprofile = "taxonID\tisMarine\tisTerrestrial\tisExtinct\n"
        + "2\tfalse\ttrue\tfalse\n4\tfalse\ttrue\ttrue\n";

    Map<String, String> files = new LinkedHashMap<>();
    files.put("meta.xml", meta);
    files.put("taxa.txt", taxa);
    files.put("vernacular.txt", vernacular);
    files.put("distribution.txt", distribution);
    files.put("speciesprofile.txt", speciesprofile);
    return zip(files);
  }

  private String core() {
    StringBuilder sb = new StringBuilder();
    sb.append("  <core encoding=\"UTF-8\" fieldsTerminatedBy=\"\\t\" linesTerminatedBy=\"\\n\" "
        + "ignoreHeaderLines=\"1\" rowType=\"" + TAXON + "\">\n");
    sb.append("    <files><location>taxa.txt</location></files>\n");
    sb.append("    <id index=\"0\"/>\n");
    String[] terms = {"taxonID", "scientificName", "taxonRank", "taxonomicStatus", "parentNameUsageID",
        "acceptedNameUsageID", "kingdom", "phylum", "class", "order", "family", "genus"};
    for (int i = 0; i < terms.length; i++) {
      sb.append("    <field index=\"" + i + "\" term=\"" + DWC + terms[i] + "\"/>\n");
    }
    sb.append("  </core>\n");
    return sb.toString();
  }

  private String ext(String rowType, String location, String... termUris) {
    StringBuilder sb = new StringBuilder();
    sb.append("  <extension encoding=\"UTF-8\" fieldsTerminatedBy=\"\\t\" linesTerminatedBy=\"\\n\" "
        + "ignoreHeaderLines=\"1\" rowType=\"" + rowType + "\">\n");
    sb.append("    <files><location>" + location + "</location></files>\n");
    sb.append("    <coreid index=\"0\"/>\n");
    for (int i = 0; i < termUris.length; i++) {
      sb.append("    <field index=\"" + (i + 1) + "\" term=\"" + termUris[i] + "\"/>\n");
    }
    sb.append("  </extension>\n");
    return sb.toString();
  }

  private byte[] zip(Map<String, String> files) throws Exception {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try (ZipOutputStream zos = new ZipOutputStream(bos)) {
      for (Map.Entry<String, String> e : files.entrySet()) {
        zos.putNextEntry(new ZipEntry(e.getKey()));
        zos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
      }
    }
    return bos.toByteArray();
  }
}
