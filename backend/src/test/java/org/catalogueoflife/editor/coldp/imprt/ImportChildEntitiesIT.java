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
import org.catalogueoflife.editor.child.DistributionMapper;
import org.catalogueoflife.editor.child.EstimateMapper;
import org.catalogueoflife.editor.child.MediaMapper;
import org.catalogueoflife.editor.child.NameRelationMapper;
import org.catalogueoflife.editor.child.PropertyMapper;
import org.catalogueoflife.editor.child.TypeMaterialMapper;
import org.catalogueoflife.editor.child.VernacularMapper;
import org.catalogueoflife.editor.child.dto.DistributionResponse;
import org.catalogueoflife.editor.child.dto.EstimateResponse;
import org.catalogueoflife.editor.child.dto.MediaResponse;
import org.catalogueoflife.editor.child.dto.NameRelationResponse;
import org.catalogueoflife.editor.child.dto.PropertyResponse;
import org.catalogueoflife.editor.child.dto.TypeMaterialResponse;
import org.catalogueoflife.editor.child.dto.VernacularResponse;
import org.catalogueoflife.editor.coldp.io.ColdpMetadata;
import org.catalogueoflife.editor.coldp.io.ColdpMetadata.ColdpMetadataDto;
import org.catalogueoflife.editor.coldp.io.ColdpTsv;
import org.catalogueoflife.editor.coldp.io.ColdpZip;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.name.NameUsageMapper;
import org.catalogueoflife.editor.name.Reference;
import org.catalogueoflife.editor.name.ReferenceMapper;
import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserService;
import org.catalogueoflife.editor.validation.IssueMapper;
import org.catalogueoflife.editor.validation.dto.IssueResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// Task 5's end-to-end proof: ImportRunService.loadChildEntities (via self.run -> loadTransactional,
// called AFTER loadNameUsages) loads all 7 taxon/name child-entity files, inverting
// ChildColdpWriter's *Row methods field-for-field, remapping taxonID/nameID through ctx.usageIds and
// referenceID through ctx.refIds -- and once the load transaction commits, run() explicitly calls
// ValidationService.revalidateProject, the ONLY thing that ever validates an imported project (every
// insert in this whole job goes through raw mappers, never NameUsageService/ReferenceService, so
// nothing here ever publishes a ValidationEvent -- see ValidationTrigger).
//
// The fixture: 2 accepted usages ("2" Felis catus, "3" Felis silvestris, both children of "1"
// Animalia) plus a synonym ("4" Felis domesticus, synonym of "2") -- Felis catus ("2") is the target
// of every taxon/name-keyed child row; Felis silvestris ("3") is NameRelation's relatedNameID
// endpoint. Neither usage carries a nameReferenceID, so MissingPublishedInRule fires for all three --
// proof revalidateProject actually ran, not just that the load finished. A second Distribution row
// with a dangling taxonID ("9999", never defined) proves a child row referencing an unknown usage is
// skipped with an ImportIssue rather than failing the whole import. A third Distribution row with
// taxonID "4" (the synonym) proves the 5 taxon-scoped loaders uphold the "ACCEPTED usages only"
// invariant AbstractChildEntityService.requireAcceptedUsage enforces everywhere else in the app --
// resolvable but non-accepted is skipped with its own ImportIssue too, same as a dangling reference.
@AutoConfigureMockMvc
class ImportChildEntitiesIT extends AbstractPostgresIT {

  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ObjectMapper json;
  @Autowired NameUsageMapper usages;
  @Autowired ReferenceMapper references;
  @Autowired TypeMaterialMapper typeMaterials;
  @Autowired DistributionMapper distributions;
  @Autowired VernacularMapper vernaculars;
  @Autowired MediaMapper media;
  @Autowired EstimateMapper estimates;
  @Autowired NameRelationMapper nameRelations;
  @Autowired PropertyMapper properties;
  @Autowired IssueMapper issues;

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
    ColdpMetadata.write(dir, new ColdpMetadataDto("Child Entities Checklist", null, null, null, null, null));

    Map<ColdpTerm, String> ref1 = row(ColdpTerm.ID, "r1", ColdpTerm.citation, "Type Reference 2020");
    ColdpTsv.writeFile(dir, ColdpTerm.Reference, List.of(ref1));

    Map<ColdpTerm, String> animalia = row(
        ColdpTerm.ID, "1", ColdpTerm.scientificName, "Animalia", ColdpTerm.rank, "kingdom",
        ColdpTerm.status, "accepted", ColdpTerm.code, "zoological");
    Map<ColdpTerm, String> felisCatus = row(
        ColdpTerm.ID, "2", ColdpTerm.scientificName, "Felis catus", ColdpTerm.authorship, "Linnaeus, 1758",
        ColdpTerm.rank, "species", ColdpTerm.status, "accepted", ColdpTerm.parentID, "1",
        ColdpTerm.code, "zoological");
    Map<ColdpTerm, String> felisSilvestris = row(
        ColdpTerm.ID, "3", ColdpTerm.scientificName, "Felis silvestris", ColdpTerm.authorship, "Schreber, 1777",
        ColdpTerm.rank, "species", ColdpTerm.status, "accepted", ColdpTerm.parentID, "1",
        ColdpTerm.code, "zoological");
    Map<ColdpTerm, String> felisDomesticus = row(
        ColdpTerm.ID, "4", ColdpTerm.scientificName, "Felis domesticus", ColdpTerm.authorship, "Erxleben, 1777",
        ColdpTerm.rank, "species", ColdpTerm.status, "synonym", ColdpTerm.parentID, "2",
        ColdpTerm.code, "zoological");
    ColdpTsv.writeFile(dir, ColdpTerm.NameUsage,
        List.of(animalia, felisCatus, felisSilvestris, felisDomesticus));

    Map<ColdpTerm, String> typeMaterial = row(
        ColdpTerm.ID, "tm1", ColdpTerm.nameID, "2", ColdpTerm.citation, "Type specimen citation",
        ColdpTerm.status, "holotype", ColdpTerm.referenceID, "r1", ColdpTerm.country, "US",
        ColdpTerm.locality, "Somewhere", ColdpTerm.latitude, "12.34", ColdpTerm.longitude, "-56.78",
        ColdpTerm.sex, "male", ColdpTerm.date, "2020-01-01", ColdpTerm.collector, "J. Doe",
        ColdpTerm.institutionCode, "NHM", ColdpTerm.catalogNumber, "12345",
        ColdpTerm.link, "https://example.org/type", ColdpTerm.remarks, "a type specimen");
    ColdpTsv.writeFile(dir, ColdpTerm.TypeMaterial, List.of(typeMaterial));

    Map<ColdpTerm, String> distribution = row(
        ColdpTerm.taxonID, "2", ColdpTerm.area, "California", ColdpTerm.areaID, "US-CA",
        ColdpTerm.gazetteer, "TDWG", ColdpTerm.establishmentMeans, "native",
        ColdpTerm.threatStatus, "least concern", ColdpTerm.referenceID, "r1",
        ColdpTerm.remarks, "distribution remark");
    // Dangling taxonID: never defined in NameUsage.tsv -- must be skipped with an ImportIssue, not
    // fail the whole import.
    Map<ColdpTerm, String> danglingDistribution = row(
        ColdpTerm.taxonID, "9999", ColdpTerm.area, "Nowhere");
    // Synonym taxonID: "4" (Felis domesticus) is a SYNONYM of "2", not accepted -- Distribution is
    // one of the 5 taxon-scoped entities that only ever apply to accepted usages
    // (AbstractChildEntityService.requireAcceptedUsage), so this row must be skipped with an
    // ImportIssue too, exactly like the dangling-taxonID row above, rather than silently creating a
    // distribution row against a synonym.
    Map<ColdpTerm, String> synonymDistribution = row(
        ColdpTerm.taxonID, "4", ColdpTerm.area, "Synonym Land");
    ColdpTsv.writeFile(dir, ColdpTerm.Distribution,
        List.of(distribution, danglingDistribution, synonymDistribution));

    Map<ColdpTerm, String> vernacular = row(
        ColdpTerm.taxonID, "2", ColdpTerm.name, "Domestic Cat", ColdpTerm.language, "eng",
        ColdpTerm.country, "US", ColdpTerm.preferred, "true", ColdpTerm.referenceID, "r1",
        ColdpTerm.remarks, "common name");
    ColdpTsv.writeFile(dir, ColdpTerm.VernacularName, List.of(vernacular));

    Map<ColdpTerm, String> mediaRow = row(
        ColdpTerm.taxonID, "2", ColdpTerm.url, "https://example.org/photo.jpg", ColdpTerm.type, "image",
        ColdpTerm.title, "A photo", ColdpTerm.creator, "Jane Doe", ColdpTerm.license, "CC-BY-4.0",
        ColdpTerm.link, "https://example.org/photo-page", ColdpTerm.remarks, "media remark");
    ColdpTsv.writeFile(dir, ColdpTerm.Media, List.of(mediaRow));

    Map<ColdpTerm, String> estimate = row(
        ColdpTerm.taxonID, "2", ColdpTerm.estimate, "42", ColdpTerm.type, "species",
        ColdpTerm.referenceID, "r1", ColdpTerm.remarks, "estimate remark");
    ColdpTsv.writeFile(dir, ColdpTerm.SpeciesEstimate, List.of(estimate));

    Map<ColdpTerm, String> nameRelation = row(
        ColdpTerm.nameID, "2", ColdpTerm.relatedNameID, "3", ColdpTerm.type, "basionym",
        ColdpTerm.referenceID, "r1", ColdpTerm.page, "10", ColdpTerm.remarks, "name relation remark");
    ColdpTsv.writeFile(dir, ColdpTerm.NameRelation, List.of(nameRelation));

    Map<ColdpTerm, String> property = row(
        ColdpTerm.taxonID, "2", ColdpTerm.property, "habitat", ColdpTerm.value, "forest",
        ColdpTerm.page, "5", ColdpTerm.referenceID, "r1", ColdpTerm.remarks, "property remark");
    ColdpTsv.writeFile(dir, ColdpTerm.TaxonProperty, List.of(property));

    Path zip = dir.resolveSibling(dir.getFileName() + ".zip");
    ColdpZip.zipFolder(dir, zip);
    return Files.readAllBytes(zip);
  }

  @Test
  @WithMockUser(username = "importChildEntitiesOwner")
  void importsAllSevenChildEntityFilesSkipsDanglingRowsAndRevalidatesTheProject(@TempDir Path tmp)
      throws Exception {
    ensureUser("importChildEntitiesOwner");

    Path dir = tmp.resolve("archive");
    Files.createDirectories(dir);
    byte[] zipBytes = buildArchive(dir);
    MockMultipartFile file = new MockMultipartFile("file", "childentities.zip", "application/zip", zipBytes);

    String startBody = mvc.perform(multipart("/api/projects/import").file(file).with(csrf()))
        .andExpect(status().isAccepted())
        .andReturn().getResponse().getContentAsString();
    long runId = json.readTree(startBody).get("id").asLong();

    JsonNode done = pollUntilTerminal(runId);
    assertThat(done.get("status").asString()).isEqualTo("DONE");
    assertThat(done.get("error").isNull()).isTrue();
    int projectId = done.get("projectId").asInt();

    List<NameUsage> allUsages = usages.findAllByProject(projectId);
    int felisCatusId = byName(allUsages, "Felis catus").getId();
    int felisSilvestrisId = byName(allUsages, "Felis silvestris").getId();
    int felisDomesticusId = byName(allUsages, "Felis domesticus").getId();

    List<Reference> refs = references.findAllByProject(projectId);
    int typeRefId = refs.stream().filter(r -> "Type Reference 2020".equals(r.getCitation()))
        .findFirst().orElseThrow().getId();

    // TypeMaterial: keyed off nameID (see ChildColdpWriter.typeMaterialRow), remapped referenceID,
    // parsed lat/lon, occurrenceId untouched (no ColdpTerm column for it on this entity).
    List<TypeMaterialResponse> tms = typeMaterials.findByUsage(projectId, felisCatusId);
    assertThat(tms).hasSize(1);
    TypeMaterialResponse tm = tms.get(0);
    assertThat(tm.citation()).isEqualTo("Type specimen citation");
    assertThat(tm.status()).isEqualTo("holotype");
    assertThat(tm.referenceId()).isEqualTo(typeRefId);
    assertThat(tm.country()).isEqualTo("US");
    assertThat(tm.locality()).isEqualTo("Somewhere");
    assertThat(tm.latitude()).isEqualTo(12.34);
    assertThat(tm.longitude()).isEqualTo(-56.78);
    assertThat(tm.sex()).isEqualTo("male");
    assertThat(tm.date()).isEqualTo("2020-01-01");
    assertThat(tm.collector()).isEqualTo("J. Doe");
    assertThat(tm.institutionCode()).isEqualTo("NHM");
    assertThat(tm.catalogNumber()).isEqualTo("12345");
    assertThat(tm.link()).isEqualTo("https://example.org/type");
    assertThat(tm.remarks()).isEqualTo("a type specimen");
    assertThat(tm.occurrenceId()).isNull();

    // Distribution: the valid row landed against the remapped usage; the dangling-taxonID row and
    // the synonym-taxonID row were both skipped (project-wide list has exactly one row, not three).
    List<DistributionResponse> dist = distributions.findByProject(projectId);
    assertThat(dist).hasSize(1);
    DistributionResponse d = dist.get(0);
    assertThat(d.usageId()).isEqualTo(felisCatusId);
    assertThat(d.area()).isEqualTo("California");
    assertThat(d.areaId()).isEqualTo("US-CA");
    assertThat(d.gazetteer()).isEqualTo("TDWG");
    assertThat(d.establishmentMeans()).isEqualTo("native");
    assertThat(d.threatStatus()).isEqualTo("least concern");
    assertThat(d.referenceId()).isEqualTo(typeRefId);
    assertThat(d.remarks()).isEqualTo("distribution remark");

    // The synonym-taxonID row created no distribution row against Felis domesticus (a SYNONYM, not
    // accepted) either -- same invariant as requireAcceptedUsage, upheld by import too.
    assertThat(distributions.findByUsage(projectId, felisDomesticusId)).isEmpty();

    JsonNode runIssues = done.get("issues");
    assertThat(runIssues).isNotNull();
    assertThat(runIssues.isArray()).isTrue();
    boolean danglingIssueFound = false;
    boolean notAcceptedIssueFound = false;
    for (JsonNode issue : runIssues) {
      if (!"distribution".equals(issue.get("entity").asString())) {
        continue;
      }
      String message = issue.get("message").asString();
      if (message.contains("9999")) {
        danglingIssueFound = true;
      }
      if (message.contains("not accepted")) {
        notAcceptedIssueFound = true;
      }
    }
    assertThat(danglingIssueFound).as("dangling taxonID distribution row surfaced as an issue").isTrue();
    assertThat(notAcceptedIssueFound)
        .as("synonym taxonID distribution row surfaced as a not-accepted issue").isTrue();

    // VernacularName.
    List<VernacularResponse> verns = vernaculars.findByUsage(projectId, felisCatusId);
    assertThat(verns).hasSize(1);
    VernacularResponse v = verns.get(0);
    assertThat(v.name()).isEqualTo("Domestic Cat");
    assertThat(v.language()).isEqualTo("eng");
    assertThat(v.country()).isEqualTo("US");
    assertThat(v.preferred()).isTrue();
    assertThat(v.referenceId()).isEqualTo(typeRefId);
    assertThat(v.remarks()).isEqualTo("common name");

    // Media.
    List<MediaResponse> medias = media.findByUsage(projectId, felisCatusId);
    assertThat(medias).hasSize(1);
    MediaResponse m = medias.get(0);
    assertThat(m.url()).isEqualTo("https://example.org/photo.jpg");
    assertThat(m.type()).isEqualTo("image");
    assertThat(m.title()).isEqualTo("A photo");
    assertThat(m.creator()).isEqualTo("Jane Doe");
    assertThat(m.license()).isEqualTo("CC-BY-4.0");
    assertThat(m.link()).isEqualTo("https://example.org/photo-page");
    assertThat(m.remarks()).isEqualTo("media remark");

    // SpeciesEstimate.
    List<EstimateResponse> ests = estimates.findByUsage(projectId, felisCatusId);
    assertThat(ests).hasSize(1);
    EstimateResponse e = ests.get(0);
    assertThat(e.estimate()).isEqualTo(42);
    assertThat(e.type()).isEqualTo("species");
    assertThat(e.referenceId()).isEqualTo(typeRefId);
    assertThat(e.remarks()).isEqualTo("estimate remark");

    // NameRelation: both endpoints are NAMES (nameID/relatedNameID), remapped through ctx.usageIds.
    List<NameRelationResponse> rels = nameRelations.findByUsage(projectId, felisCatusId);
    assertThat(rels).hasSize(1);
    NameRelationResponse nr = rels.get(0);
    assertThat(nr.relatedUsageId()).isEqualTo(felisSilvestrisId);
    assertThat(nr.type()).isEqualTo("basionym");
    assertThat(nr.referenceId()).isEqualTo(typeRefId);
    assertThat(nr.page()).isEqualTo("10");
    assertThat(nr.remarks()).isEqualTo("name relation remark");

    // TaxonProperty.
    List<PropertyResponse> props = properties.findByUsage(projectId, felisCatusId);
    assertThat(props).hasSize(1);
    PropertyResponse p = props.get(0);
    assertThat(p.property()).isEqualTo("habitat");
    assertThat(p.value()).isEqualTo("forest");
    assertThat(p.page()).isEqualTo("5");
    assertThat(p.referenceId()).isEqualTo(typeRefId);
    assertThat(p.remarks()).isEqualTo("property remark");

    // Validation ran: run()'s post-commit validationService.revalidateProject actually executed --
    // none of the three usages carry a nameReferenceID, so MissingPublishedInRule ("missing_published_in")
    // fires for every one of them.
    List<IssueResponse> validationIssues = issues.findByProject(projectId, null, null, null, null, 100, 0);
    assertThat(validationIssues).isNotEmpty();
    assertThat(validationIssues).anyMatch(i -> "missing_published_in".equals(i.rule()));
  }

  private static NameUsage byName(List<NameUsage> all, String scientificName) {
    return all.stream().filter(u -> scientificName.equals(u.getScientificName())).findFirst()
        .orElseThrow(() -> new AssertionError("no usage found for scientificName=" + scientificName));
  }
}
