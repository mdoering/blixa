package org.catalogueoflife.editor.coldp.imprt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import life.catalogue.api.vocab.Environment;
import org.catalogueoflife.editor.child.DistributionMapper;
import org.catalogueoflife.editor.child.DistributionService;
import org.catalogueoflife.editor.child.TypeMaterialMapper;
import org.catalogueoflife.editor.child.TypeMaterialService;
import org.catalogueoflife.editor.child.dto.DistributionRequest;
import org.catalogueoflife.editor.child.dto.DistributionResponse;
import org.catalogueoflife.editor.child.dto.TypeMaterialRequest;
import org.catalogueoflife.editor.child.dto.TypeMaterialResponse;
import org.catalogueoflife.editor.coldp.export.ColdpWriter;
import org.catalogueoflife.editor.name.Author;
import org.catalogueoflife.editor.name.AuthorMapper;
import org.catalogueoflife.editor.name.IdSeqMapper;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.name.NameUsageMapper;
import org.catalogueoflife.editor.name.Reference;
import org.catalogueoflife.editor.name.RefMapping;
import org.catalogueoflife.editor.name.ReferenceMapper;
import org.catalogueoflife.editor.name.ReferenceService;
import org.catalogueoflife.editor.name.Status;
import org.catalogueoflife.editor.name.SynonymAcceptedMapper;
import org.catalogueoflife.editor.name.TaxonInfoMapper;
import org.catalogueoflife.editor.name.dto.CreateReferenceRequest;
import org.catalogueoflife.editor.project.Project;
import org.catalogueoflife.editor.project.ProjectMapper;
import org.catalogueoflife.editor.project.ProjectMember;
import org.catalogueoflife.editor.project.ProjectMemberMapper;
import org.catalogueoflife.editor.project.Role;
import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserMapper;
import org.gbif.nameparser.api.NomCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// Task 6's key integration test: the WHOLE import job (Tasks 1-5) proven against the WHOLE export
// job (coldpexp Tasks 1-5) in one pass -- seed a comprehensive source project directly via the
// app's own mappers/services (the pattern ExportRoundTripIT establishes), export it for real
// (ColdpWriter.write, the same entry point ExportRunService drives), then import that exact zip
// back through the real HTTP job (POST /api/projects/import, the same entry point ImportApiIT
// drives) with preserveIds=true/idScope="orig", and assert the resulting NEW project matches the
// source field-for-field, id-for-id (after remapping).
//
// The fixture is deliberately built to hit every subtle point Task 4's two-phase remap / status
// inverse / pro-parte re-merge logic (see ImportRunService.loadNameUsages' javadoc) has to get
// right, ALL at once, ACROSS a real export+reimport round trip rather than a hand-crafted archive
// (ImportNameUsageIT already covers the hand-crafted-archive version of each of these):
//  - a 3-level accepted classification chain: Animalia(kingdom) -> Felidae(family) ->
//    {Felis catus, Felis silvestris}(species) -- proves the parent_id chain resolves end-to-end
//    to the NEW project's own ids, not just one level deep.
//  - a plain synonym, Felis domesticus, of Felis catus -- and simultaneously Felis catus's
//    BASIONYM (set via NameUsageMapper.updateHierarchy once the synonym exists, since
//    basionym_id/parent_id are non-deferrable self-referencing FKs -- see that mapper method's
//    own javadoc) -- proves basionym_id round-trips through the export->reimport cycle.
//  - a pro-parte synonym, Felis vulgaris, linked to BOTH accepted species -- must collapse back to
//    ONE new_usage row carrying TWO synonym_accepted links, not two rows.
//  - an UNASSESSED usage WITH a parent link, Felis dubia (synonym_accepted -> Felis catus) --
//    exported as parentID=<Felis catus> + status "provisionally accepted" (see
//    NameUsageColdpWriter.coldpStatus), which on reimport must become a synonym_accepted link
//    again, NEVER a classification parent_id -- the status-inverse critical path. (Distinct from
//    ExportRoundTripIT's own UNASSESSED-with-NO-link fixture, which exercises a different branch.)
//  - Felis catus also carries publishedInReferenceId=ref1 AND referenceID=[ref1, ref2] -- the SAME
//    reference cited both ways -- proving both remap paths land on the same new reference id.
//  - taxon_info (extinct/environment/temporalRange) on the accepted Felis catus.
//  - one Distribution + one TypeMaterial child row on Felis catus.
//  - one Author (Carl Linnaeus).
//  - Felis catus's remarks carries deliberately irregular whitespace ("  a   double  spaced
//    remark  ") -- TabWriter (ColdpTsv) does not touch it at write time (only tab/newline/CR are
//    escaped), so the written TSV cell is byte-for-byte what was seeded; CsvReader.clean() (the
//    CLB reader library, invoked while ImportRunService reads each row back) is what collapses
//    the whitespace runs and trims -- see ColdpTsvIT's javadoc for the authoritative explanation.
//    The imported project's copy must therefore be the NORMALIZED string, not the raw seeded one.
@AutoConfigureMockMvc
class ImportExportRoundTripIT extends AbstractPostgresIT {

  private static final String USAGE_ENTITY = "name_usage";
  private static final String AUTHOR_ENTITY = "author";
  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired ProjectMapper projects;
  @Autowired ProjectMemberMapper members;
  @Autowired AppUserMapper users;
  @Autowired NameUsageMapper usages;
  @Autowired SynonymAcceptedMapper synonymAccepted;
  @Autowired TaxonInfoMapper taxonInfo;
  @Autowired IdSeqMapper idSeq;
  @Autowired ReferenceService referenceService;
  @Autowired ReferenceMapper references;
  @Autowired AuthorMapper authors;
  @Autowired DistributionService distributionService;
  @Autowired DistributionMapper distributions;
  @Autowired TypeMaterialService typeMaterialService;
  @Autowired TypeMaterialMapper typeMaterials;
  @Autowired ColdpWriter writer;

  private int createUser(String username) {
    AppUser u = new AppUser();
    u.setUsername(username);
    users.insert(u);
    return u.getId();
  }

  // Mirrors ExportRoundTripIT.createProject: the child/reference services authorize via
  // ProjectService.requireRole, which needs an actual project_member row, not just a project row.
  private int createProject(String title, int userId) {
    Project p = new Project();
    p.setTitle(title);
    p.setNomCode(NomCode.ZOOLOGICAL);
    projects.insert(p);
    members.upsert(new ProjectMember(p.getId(), userId, Role.OWNER.dbValue()));
    return p.getId();
  }

  private NameUsage createUsage(int projectId, String scientificName, String rank, Status status,
      Integer parentId, int userId) {
    NameUsage u = new NameUsage();
    u.setProjectId(projectId);
    u.setId(idSeq.allocate(projectId, USAGE_ENTITY));
    u.setStatus(status);
    u.setScientificName(scientificName);
    u.setRank(rank);
    u.setParentId(parentId);
    u.setModifiedBy(userId);
    usages.insert(u);
    return u;
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

  private static NameUsage byName(List<NameUsage> all, String scientificName) {
    return all.stream().filter(u -> scientificName.equals(u.getScientificName())).findFirst()
        .orElseThrow(() -> new AssertionError("no usage found for scientificName=" + scientificName));
  }

  @Test
  @WithMockUser(username = "roundtrip-owner")
  void exportedProjectReimportsWithPreservedIdsAndResolvedLinks(@TempDir Path tmp) throws Exception {
    int userId = createUser("roundtrip-owner");
    int pid = createProject("roundtrip-source", userId);

    Reference ref1 = referenceService.create(userId, pid, new CreateReferenceRequest(
        "Linnaeus, C. 1758. Systema Naturae.", false, "book", RefMapping.parseNames("C. Linnaeus"),
        null, "Systema Naturae", null, null, "1758", null, null, null, null, null, null, null, null,
        null, null));
    Reference ref2 = referenceService.create(userId, pid, new CreateReferenceRequest(
        "Pocock, R.I. 1917. On the external characters of the Felidae.", false, "article-journal",
        RefMapping.parseNames("R.I. Pocock"), null, "On the external characters of the Felidae",
        "Annals and Magazine of Natural History", null, "1917", "20", null, "329-350",
        null, null, null, null, null, null, null));

    Author author = new Author();
    author.setProjectId(pid);
    author.setId(idSeq.allocate(pid, AUTHOR_ENTITY));
    author.setGiven("Carl");
    author.setFamily("Linnaeus");
    author.setModifiedBy(userId);
    authors.insert(author);

    // Accepted classification chain: Animalia -> Felidae -> {Felis catus, Felis silvestris}.
    NameUsage animalia = createUsage(pid, "Animalia", "kingdom", Status.ACCEPTED, null, userId);
    NameUsage felidae = createUsage(pid, "Felidae", "family", Status.ACCEPTED, animalia.getId(), userId);

    NameUsage felisCatus = new NameUsage();
    felisCatus.setProjectId(pid);
    felisCatus.setId(idSeq.allocate(pid, USAGE_ENTITY));
    felisCatus.setStatus(Status.ACCEPTED);
    felisCatus.setScientificName("Felis catus");
    felisCatus.setRank("species");
    felisCatus.setParentId(felidae.getId());
    felisCatus.setPublishedInReferenceId(ref1.getId());
    felisCatus.setReferenceId(List.of(ref1.getId(), ref2.getId()));
    // Deliberately irregular whitespace -- see class javadoc for why the round-tripped value must
    // be the reader-normalized ("a double spaced remark"), not this raw string.
    felisCatus.setRemarks("  a   double  spaced   remark  ");
    felisCatus.setModifiedBy(userId);
    usages.insert(felisCatus);
    taxonInfo.upsert(pid, felisCatus.getId(), true,
        List.of(Environment.TERRESTRIAL, Environment.FRESHWATER), "Pleistocene", "Holocene");

    NameUsage felisSilvestris =
        createUsage(pid, "Felis silvestris", "species", Status.ACCEPTED, felidae.getId(), userId);

    // Plain synonym, and Felis catus's basionym -- set via updateHierarchy AFTER the synonym
    // exists (basionym_id is a non-deferrable self-referencing FK; see that mapper method's
    // javadoc), matching how Task 4's own Pass 2 resolves a forward basionym reference.
    NameUsage plainSynonym =
        createUsage(pid, "Felis domesticus", "species", Status.SYNONYM, null, userId);
    synonymAccepted.link(pid, plainSynonym.getId(), felisCatus.getId(), 0);
    usages.updateHierarchy(pid, felisCatus.getId(), felidae.getId(), plainSynonym.getId(), userId);

    // Pro-parte synonym: ONE usage linked to BOTH accepted species.
    NameUsage proParteSynonym =
        createUsage(pid, "Felis vulgaris", "species", Status.SYNONYM, null, userId);
    synonymAccepted.link(pid, proParteSynonym.getId(), felisCatus.getId(), 0);
    synonymAccepted.link(pid, proParteSynonym.getId(), felisSilvestris.getId(), 1);

    // UNASSESSED usage WITH a parent (accepted) link -- exports as parentID=<Felis catus> +
    // status "provisionally accepted"; on reimport this MUST become a synonym_accepted link
    // again, never a classification parent_id (the status-inverse critical path).
    NameUsage unassessedWithParent =
        createUsage(pid, "Felis dubia", "species", Status.UNASSESSED, null, userId);
    synonymAccepted.link(pid, unassessedWithParent.getId(), felisCatus.getId(), 0);

    distributionService.create(userId, pid, felisCatus.getId(),
        new DistributionRequest(null, "tdwg:AB", "tdwg", "native", null, ref1.getId(),
            "dist remark", null));
    typeMaterialService.create(userId, pid, felisCatus.getId(),
        new TypeMaterialRequest("Holotype citation", "holotype", "NHM", "NHM12345", null,
            "Somewhere", "DE", "M. Doering", "2020-01-01", "male", ref2.getId(), null,
            "tm remark", 52.5, 13.4, null));

    // --- Export the source project for real (the same ColdpWriter.write ExportRunService drives). ---
    Path zip = tmp.resolve("roundtrip-export.zip");
    ColdpWriter.Counts counts = writer.write(pid, zip);
    assertThat(counts.referenceCount()).isEqualTo(2);
    // 7 name_usage rows (animalia, felidae, felisCatus, felisSilvestris, plainSynonym,
    // proParteSynonym, unassessedWithParent) + 1 extra NameUsage.tsv row for the pro-parte
    // synonym's second accepted link (see NameUsageColdpWriter.synonymRows).
    assertThat(counts.nameUsageCount()).isEqualTo(8);

    // --- Reimport that exact zip through the real HTTP job, preserving source ids. ---
    byte[] zipBytes = Files.readAllBytes(zip);
    MockMultipartFile file =
        new MockMultipartFile("file", "roundtrip-export.zip", "application/zip", zipBytes);

    String startBody = mvc.perform(multipart("/api/projects/import").file(file)
            .param("preserveIds", "true").param("idScope", "orig").with(csrf()))
        .andExpect(status().isAccepted())
        .andReturn().getResponse().getContentAsString();
    long runId = json.readTree(startBody).get("id").asLong();

    JsonNode done = pollUntilTerminal(runId);
    assertThat(done.get("status").asString()).isEqualTo("DONE");
    assertThat(done.get("error").isNull()).isTrue();
    // A clean fixture with no dangling references must surface zero issues.
    JsonNode issuesNode = done.get("issues");
    assertThat(issuesNode).isNotNull();
    assertThat(issuesNode.isEmpty()).as("clean fixture -> no import issues").isTrue();

    long pid2 = done.get("projectId").asLong();
    // ctx.nameUsageCount counts PRIMARY rows only (Pass 1) -- the pro-parte derived row never
    // reaches insertPrimaryUsage, so this is 7, not 8 (see ImportNameUsageIT's identical count logic).
    assertThat(done.get("nameUsageCount").asInt()).isEqualTo(7);
    assertThat(done.get("referenceCount").asInt()).isEqualTo(2);
    assertThat(done.get("authorCount").asInt()).isEqualTo(1);

    // --- Entity counts in the NEW project. ---
    List<NameUsage> allNew = usages.findAllByProject((int) pid2);
    assertThat(allNew).hasSize(7);
    List<Reference> newRefs = references.findAllByProject((int) pid2);
    assertThat(newRefs).hasSize(2);
    List<Author> newAuthors = authors.findByProject((int) pid2);
    assertThat(newAuthors).hasSize(1);
    assertThat(newAuthors.get(0).getGiven()).isEqualTo("Carl");
    assertThat(newAuthors.get(0).getFamily()).isEqualTo("Linnaeus");
    List<DistributionResponse> newDist = distributions.findByProject((int) pid2);
    assertThat(newDist).hasSize(1);
    List<TypeMaterialResponse> newTm = typeMaterials.findByProject((int) pid2);
    assertThat(newTm).hasSize(1);

    NameUsage newAnimalia = byName(allNew, "Animalia");
    NameUsage newFelidae = byName(allNew, "Felidae");
    NameUsage newFelisCatus = byName(allNew, "Felis catus");
    NameUsage newFelisSilvestris = byName(allNew, "Felis silvestris");
    NameUsage newPlainSynonym = byName(allNew, "Felis domesticus");
    NameUsage newUnassessed = byName(allNew, "Felis dubia");

    // Accepted parent_id chain, walked to the NEW project's own ids.
    assertThat(newAnimalia.getStatus()).isEqualTo(Status.ACCEPTED);
    assertThat(newAnimalia.getParentId()).isNull();
    assertThat(newFelidae.getParentId()).isEqualTo(newAnimalia.getId());
    assertThat(newFelisCatus.getParentId()).isEqualTo(newFelidae.getId());
    assertThat(newFelisSilvestris.getParentId()).isEqualTo(newFelidae.getId());

    // Pro-parte: exactly ONE usage named "Felis vulgaris", carrying TWO synonym_accepted links.
    List<NameUsage> vulgarisRows =
        allNew.stream().filter(u -> "Felis vulgaris".equals(u.getScientificName())).toList();
    assertThat(vulgarisRows).hasSize(1);
    NameUsage newProParte = vulgarisRows.get(0);
    assertThat(newProParte.getStatus()).isEqualTo(Status.SYNONYM);
    assertThat(newProParte.getParentId()).isNull();
    assertThat(synonymAccepted.findAcceptedFor((int) pid2, newProParte.getId()))
        .containsExactlyInAnyOrder(newFelisCatus.getId(), newFelisSilvestris.getId());

    // UNASSESSED-with-parent: status-inverse critical path -- parent_id stays null, the parentID
    // the export wrote instead became a synonym_accepted link.
    assertThat(newUnassessed.getStatus()).isEqualTo(Status.UNASSESSED);
    assertThat(newUnassessed.getParentId()).isNull();
    assertThat(synonymAccepted.findAcceptedFor((int) pid2, newUnassessed.getId()))
        .containsExactly(newFelisCatus.getId());

    // Basionym resolved to the new plain-synonym id.
    assertThat(newFelisCatus.getBasionymId()).isEqualTo(newPlainSynonym.getId());

    // Reference remap: ref1 cited BOTH as publishedInReferenceId AND inside referenceID[].
    Reference newRef1 = newRefs.stream().filter(r -> ref1.getCitation().equals(r.getCitation()))
        .findFirst().orElseThrow();
    Reference newRef2 = newRefs.stream().filter(r -> ref2.getCitation().equals(r.getCitation()))
        .findFirst().orElseThrow();
    assertThat(newFelisCatus.getPublishedInReferenceId()).isEqualTo(newRef1.getId());
    assertThat(newFelisCatus.getReferenceId()).containsExactlyInAnyOrder(newRef1.getId(), newRef2.getId());

    // taxon_info round-tripped on the accepted usage.
    assertThat(newFelisCatus.getExtinct()).isTrue();
    assertThat(newFelisCatus.getEnvironment())
        .containsExactlyInAnyOrder(Environment.TERRESTRIAL, Environment.FRESHWATER);
    assertThat(newFelisCatus.getTemporalRangeStart()).isEqualTo("Pleistocene");
    assertThat(newFelisCatus.getTemporalRangeEnd()).isEqualTo("Holocene");

    // preserve-ids: every imported name-usage/reference carries an orig:<sourceId> CURIE, where
    // sourceId is the numeric id it had in the SOURCE project (the archive's own ID column).
    assertThat(newFelisCatus.getAlternativeId()).contains("orig:" + felisCatus.getId());
    assertThat(newPlainSynonym.getAlternativeId()).contains("orig:" + plainSynonym.getId());
    assertThat(newAnimalia.getAlternativeId()).contains("orig:" + animalia.getId());
    assertThat(newRef1.getAlternativeId()).contains("orig:" + ref1.getId());
    assertThat(newRef2.getAlternativeId()).contains("orig:" + ref2.getId());

    // Whitespace normalization: the CLB reader collapses whitespace runs and trims on read (see
    // class javadoc) -- the imported remarks must be the NORMALIZED string, not the raw seeded one.
    assertThat(newFelisCatus.getRemarks()).isEqualTo("a double spaced remark");

    // Distribution + TypeMaterial round-tripped against the remapped usage + reference ids.
    DistributionResponse newD = newDist.get(0);
    assertThat(newD.usageId()).isEqualTo(newFelisCatus.getId());
    assertThat(newD.areaId()).isEqualTo("tdwg:AB");
    assertThat(newD.gazetteer()).isEqualTo("tdwg");
    assertThat(newD.establishmentMeans()).isEqualTo("native");
    assertThat(newD.referenceId()).isEqualTo(newRef1.getId());
    assertThat(newD.remarks()).isEqualTo("dist remark");

    TypeMaterialResponse newTmRow = newTm.get(0);
    assertThat(newTmRow.citation()).isEqualTo("Holotype citation");
    assertThat(newTmRow.status()).isEqualTo("holotype");
    assertThat(newTmRow.institutionCode()).isEqualTo("NHM");
    assertThat(newTmRow.catalogNumber()).isEqualTo("NHM12345");
    assertThat(newTmRow.country()).isEqualTo("DE");
    assertThat(newTmRow.locality()).isEqualTo("Somewhere");
    assertThat(newTmRow.sex()).isEqualTo("male");
    assertThat(newTmRow.date()).isEqualTo("2020-01-01");
    assertThat(newTmRow.collector()).isEqualTo("M. Doering");
    assertThat(newTmRow.referenceId()).isEqualTo(newRef2.getId());
    assertThat(newTmRow.latitude()).isEqualTo(52.5);
    assertThat(newTmRow.longitude()).isEqualTo(13.4);
    assertThat(newTmRow.occurrenceId()).isNull();
  }
}
