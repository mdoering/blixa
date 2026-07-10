package org.catalogueoflife.editor.coldp.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import life.catalogue.api.model.VerbatimRecord;
import life.catalogue.coldp.ColdpTerm;
import life.catalogue.csv.ColdpReader;
import org.catalogueoflife.editor.child.DistributionService;
import org.catalogueoflife.editor.child.EstimateService;
import org.catalogueoflife.editor.child.MediaService;
import org.catalogueoflife.editor.child.NameRelationService;
import org.catalogueoflife.editor.child.PropertyService;
import org.catalogueoflife.editor.child.TypeMaterialService;
import org.catalogueoflife.editor.child.VernacularService;
import org.catalogueoflife.editor.child.dto.DistributionRequest;
import org.catalogueoflife.editor.child.dto.EstimateRequest;
import org.catalogueoflife.editor.child.dto.MediaRequest;
import org.catalogueoflife.editor.child.dto.NameRelationRequest;
import org.catalogueoflife.editor.child.dto.PropertyRequest;
import org.catalogueoflife.editor.child.dto.TypeMaterialRequest;
import org.catalogueoflife.editor.child.dto.VernacularRequest;
import org.catalogueoflife.editor.coldp.io.ColdpZip;
import org.catalogueoflife.editor.name.IdSeqMapper;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.name.NameUsageMapper;
import org.catalogueoflife.editor.name.Status;
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

// Task 4: the 7 taxon/name child-entity files (ChildColdpWriter, wired into ColdpWriter.write).
// Fixture: one ACCEPTED usage carrying one of each child entity (Distribution/TypeMaterial/
// Vernacular/Media/Estimate/TaxonProperty attach to it directly) plus a second ACCEPTED usage that
// only exists as the target of a NameRelation from the first -- proving relatedNameID resolves to a
// usage other than the one the relation itself hangs off. Fixtures go through the real child
// services (DistributionService.create etc, like ReferenceExportIT uses ReferenceService.create),
// not direct mapper inserts, so this also exercises the create path end-to-end into the export.
class ChildExportIT extends AbstractPostgresIT {

  private static final String USAGE_ENTITY = "name_usage";

  @Autowired ProjectMapper projects;
  @Autowired ProjectMemberMapper members;
  @Autowired AppUserMapper users;
  @Autowired NameUsageMapper nameUsages;
  @Autowired IdSeqMapper idSeq;
  @Autowired DistributionService distributionService;
  @Autowired TypeMaterialService typeMaterialService;
  @Autowired VernacularService vernacularService;
  @Autowired MediaService mediaService;
  @Autowired EstimateService estimateService;
  @Autowired PropertyService propertyService;
  @Autowired NameRelationService nameRelationService;
  @Autowired ColdpWriter writer;

  private int createUser(String username) {
    AppUser u = new AppUser();
    u.setUsername(username);
    users.insert(u);
    return u.getId();
  }

  // Child-entity services authorize via ProjectService.requireRole (requireEditor), which needs an
  // actual project_member row, not just a project row (see ReferenceExportIT.createProject).
  private int createProject(String title, int userId) {
    Project p = new Project();
    p.setTitle(title);
    p.setNomCode(NomCode.ZOOLOGICAL);
    projects.insert(p);
    members.upsert(new ProjectMember(p.getId(), userId, Role.OWNER.dbValue()));
    return p.getId();
  }

  private NameUsage createAcceptedUsage(int projectId, String scientificName, int userId) {
    NameUsage u = new NameUsage();
    u.setProjectId(projectId);
    u.setId(idSeq.allocate(projectId, USAGE_ENTITY));
    u.setStatus(Status.ACCEPTED);
    u.setScientificName(scientificName);
    u.setRank("species");
    u.setModifiedBy(userId);
    nameUsages.insert(u);
    return u;
  }

  @Test
  void writesAllSevenChildFiles(@TempDir Path tmp) throws Exception {
    int userId = createUser("child-export-owner");
    int pid = createProject("child-export", userId);

    NameUsage usage = createAcceptedUsage(pid, "Aus bus", userId);
    NameUsage related = createAcceptedUsage(pid, "Xus xus", userId);

    var distribution = distributionService.create(userId, pid, usage.getId(),
        new DistributionRequest(null, "tdwg:AB", "tdwg", "native", null, null, null, null));

    var typeMaterial = typeMaterialService.create(userId, pid, usage.getId(),
        new TypeMaterialRequest("Holotype citation", "holotype", "NHM", "NHM12345", null,
            "Somewhere", "DE", "M. Doering", "2020-01-01", "male", null, null, null,
            52.5, 13.4, null));

    var vernacular = vernacularService.create(userId, pid, usage.getId(),
        new VernacularRequest("Blue Aus", "eng", "GB", null, true, null, null, null));

    var media = mediaService.create(userId, pid, usage.getId(),
        new MediaRequest("https://example.org/img.jpg", "image", "Aus bus photo", "M. Doering",
            "cc-by", null, null, null));

    var estimate = estimateService.create(userId, pid, usage.getId(),
        new EstimateRequest(42, "species living", null, null, null));

    var property = propertyService.create(userId, pid, usage.getId(),
        new PropertyRequest("IUCN status", "LC", null, null, null, null));

    var nameRelation = nameRelationService.create(userId, pid, usage.getId(),
        new NameRelationRequest(related.getId(), "basionym", null, null, null, null));

    Path targetZip = tmp.resolve("export.zip");
    writer.write(pid, targetZip);

    Path extractDir = tmp.resolve("extracted");
    try (InputStream in = Files.newInputStream(targetZip)) {
      ColdpZip.extractToTemp(in, extractDir);
    }
    // ColdpReader is neither Closeable nor AutoCloseable -- no try-with-resources (see ColdpTsvIT).
    ColdpReader reader = ColdpReader.from(extractDir);

    assertThat(reader.hasSchema(ColdpTerm.Distribution)).isTrue();
    assertThat(reader.hasSchema(ColdpTerm.TypeMaterial)).isTrue();
    assertThat(reader.hasSchema(ColdpTerm.VernacularName)).isTrue();
    assertThat(reader.hasSchema(ColdpTerm.Media)).isTrue();
    assertThat(reader.hasSchema(ColdpTerm.SpeciesEstimate)).isTrue();
    assertThat(reader.hasSchema(ColdpTerm.NameRelation)).isTrue();
    assertThat(reader.hasSchema(ColdpTerm.TaxonProperty)).isTrue();

    List<VerbatimRecord> distRecs = reader.stream(ColdpTerm.Distribution).toList();
    assertThat(distRecs).hasSize(1);
    VerbatimRecord distRec = distRecs.get(0);
    assertThat(distRec.get(ColdpTerm.taxonID)).isEqualTo(String.valueOf(usage.getId()));
    assertThat(distRec.get(ColdpTerm.areaID)).isEqualTo("tdwg:AB");
    assertThat(distRec.get(ColdpTerm.gazetteer)).isEqualTo("tdwg");
    assertThat(distRec.get(ColdpTerm.establishmentMeans)).isEqualTo("native");

    List<VerbatimRecord> tmRecs = reader.stream(ColdpTerm.TypeMaterial).toList();
    assertThat(tmRecs).hasSize(1);
    VerbatimRecord tmRec = tmRecs.get(0);
    assertThat(tmRec.get(ColdpTerm.ID)).isEqualTo(String.valueOf(typeMaterial.id()));
    assertThat(tmRec.get(ColdpTerm.nameID)).isEqualTo(String.valueOf(usage.getId()));
    assertThat(tmRec.get(ColdpTerm.latitude)).isEqualTo("52.5");
    assertThat(tmRec.get(ColdpTerm.longitude)).isEqualTo("13.4");
    assertThat(tmRec.get(ColdpTerm.institutionCode)).isEqualTo("NHM");
    assertThat(tmRec.get(ColdpTerm.catalogNumber)).isEqualTo("NHM12345");

    List<VerbatimRecord> vernRecs = reader.stream(ColdpTerm.VernacularName).toList();
    assertThat(vernRecs).hasSize(1);
    VerbatimRecord vernRec = vernRecs.get(0);
    assertThat(vernRec.get(ColdpTerm.taxonID)).isEqualTo(String.valueOf(usage.getId()));
    assertThat(vernRec.get(ColdpTerm.name)).isEqualTo("Blue Aus");
    assertThat(vernRec.get(ColdpTerm.language)).isEqualTo("eng");
    assertThat(vernRec.get(ColdpTerm.preferred)).isEqualTo("true");

    List<VerbatimRecord> mediaRecs = reader.stream(ColdpTerm.Media).toList();
    assertThat(mediaRecs).hasSize(1);
    VerbatimRecord mediaRec = mediaRecs.get(0);
    assertThat(mediaRec.get(ColdpTerm.taxonID)).isEqualTo(String.valueOf(usage.getId()));
    assertThat(mediaRec.get(ColdpTerm.url)).isEqualTo("https://example.org/img.jpg");
    assertThat(mediaRec.get(ColdpTerm.type)).isEqualTo("image");

    List<VerbatimRecord> estRecs = reader.stream(ColdpTerm.SpeciesEstimate).toList();
    assertThat(estRecs).hasSize(1);
    VerbatimRecord estRec = estRecs.get(0);
    assertThat(estRec.get(ColdpTerm.taxonID)).isEqualTo(String.valueOf(usage.getId()));
    assertThat(estRec.get(ColdpTerm.estimate)).isEqualTo("42");
    assertThat(estRec.get(ColdpTerm.type)).isEqualTo("species living");

    List<VerbatimRecord> propRecs = reader.stream(ColdpTerm.TaxonProperty).toList();
    assertThat(propRecs).hasSize(1);
    VerbatimRecord propRec = propRecs.get(0);
    assertThat(propRec.get(ColdpTerm.taxonID)).isEqualTo(String.valueOf(usage.getId()));
    assertThat(propRec.get(ColdpTerm.property)).isEqualTo("IUCN status");
    assertThat(propRec.get(ColdpTerm.value)).isEqualTo("LC");

    List<VerbatimRecord> nrRecs = reader.stream(ColdpTerm.NameRelation).toList();
    assertThat(nrRecs).hasSize(1);
    VerbatimRecord nrRec = nrRecs.get(0);
    assertThat(nrRec.get(ColdpTerm.nameID)).isEqualTo(String.valueOf(usage.getId()));
    assertThat(nrRec.get(ColdpTerm.relatedNameID)).isEqualTo(String.valueOf(related.getId()));
    assertThat(nrRec.get(ColdpTerm.type)).isEqualTo("basionym");
  }
}
