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
import org.catalogueoflife.editor.child.TypeMaterialService;
import org.catalogueoflife.editor.child.dto.DistributionRequest;
import org.catalogueoflife.editor.child.dto.TypeMaterialRequest;
import org.catalogueoflife.editor.coldp.io.ColdpMetadata;
import org.catalogueoflife.editor.coldp.io.ColdpZip;
import org.catalogueoflife.editor.name.IdSeqMapper;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.name.NameUsageMapper;
import org.catalogueoflife.editor.name.Reference;
import org.catalogueoflife.editor.name.ReferenceService;
import org.catalogueoflife.editor.name.Status;
import org.catalogueoflife.editor.name.SynonymAcceptedMapper;
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

// Task 5's round-trip-out IT: exercises the WHOLE export (ColdpWriter.write, i.e. every writer added
// by Tasks 1-4) against one small-but-representative project in a single pass, rather than each
// writer in isolation like NameUsageExportIT/ReferenceExportIT/ChildExportIT do. Fixture mirrors the
// Felidae sample: a kingdom->species classification, a plain synonym, a pro-parte synonym (linked to
// BOTH accepted usages), and -- the one branch Task 2's own IT never covered -- an UNASSESSED usage
// with NO synonym_accepted link at all, proving the empty-parentID branch of
// NameUsageColdpWriter.synonymRows fires for a real UNASSESSED status too, not just a
// SYNONYM/MISAPPLIED one. Plus one reference, one distribution and one type material on the species.
// Drives ColdpWriter.write directly (like the Task 2-4 ITs), not the HTTP job (already covered
// end-to-end by ExportRunApiIT) -- this test's job is format coverage across ALL entity kinds at
// once, not the async plumbing.
class ExportRoundTripIT extends AbstractPostgresIT {

  private static final String USAGE_ENTITY = "name_usage";

  @Autowired ProjectMapper projects;
  @Autowired ProjectMemberMapper members;
  @Autowired AppUserMapper users;
  @Autowired NameUsageMapper nameUsages;
  @Autowired SynonymAcceptedMapper synonymAccepted;
  @Autowired IdSeqMapper idSeq;
  @Autowired ReferenceService referenceService;
  @Autowired DistributionService distributionService;
  @Autowired TypeMaterialService typeMaterialService;
  @Autowired ColdpWriter writer;

  private int createUser(String username) {
    AppUser u = new AppUser();
    u.setUsername(username);
    users.insert(u);
    return u.getId();
  }

  // The child/reference services authorize via ProjectService.requireRole, which needs an actual
  // project_member row (see ChildExportIT/ReferenceExportIT.createProject), not just a project row.
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
    nameUsages.insert(u);
    return u;
  }

  @Test
  void roundTripsAWholeProjectExport(@TempDir Path tmp) throws Exception {
    int userId = createUser("export-roundtrip-owner");
    int pid = createProject("export-roundtrip", userId);

    NameUsage root = createUsage(pid, "Felidae", "family", Status.ACCEPTED, null, userId);
    NameUsage species = createUsage(pid, "Felis catus", "species", Status.ACCEPTED, root.getId(), userId);
    NameUsage plainSynonym = createUsage(pid, "Felis domesticus", "species", Status.SYNONYM, null, userId);
    NameUsage proParteSynonym = createUsage(pid, "Felis vulgaris", "species", Status.SYNONYM, null, userId);
    // The empty-parentID branch Task 2's own IT never covered: an UNASSESSED usage with NO
    // synonym_accepted link at all (not even a failed/removed one -- simply never linked).
    NameUsage unassessedNoLink = createUsage(pid, "Felis dubia", "species", Status.UNASSESSED, null, userId);

    synonymAccepted.link(pid, plainSynonym.getId(), species.getId(), null);
    // Pro-parte: two accepted links for the same synonym usage -> +1 extra NameUsage.tsv row.
    synonymAccepted.link(pid, proParteSynonym.getId(), species.getId(), null);
    synonymAccepted.link(pid, proParteSynonym.getId(), root.getId(), null);

    Reference reference = referenceService.create(userId, pid, new CreateReferenceRequest(
        "Linnaeus, C. 1758. Systema Naturae.", "book", "C. Linnaeus", null,
        "Systema Naturae", null, "1758", null, null, null, null, null, null, null, null, null,
        null));

    var distribution = distributionService.create(userId, pid, species.getId(),
        new DistributionRequest(null, "tdwg:AB", "tdwg", "native", null, null, null, null));

    var typeMaterial = typeMaterialService.create(userId, pid, species.getId(),
        new TypeMaterialRequest("Holotype citation", "holotype", "NHM", "NHM12345", null,
            "Somewhere", "DE", "M. Doering", "2020-01-01", "male", null, null, null,
            52.5, 13.4, null));

    Path targetZip = tmp.resolve("export.zip");
    ColdpWriter.Counts counts = writer.write(pid, targetZip);

    Path extractDir = tmp.resolve("extracted");
    try (InputStream in = Files.newInputStream(targetZip)) {
      ColdpZip.extractToTemp(in, extractDir);
    }
    // ColdpReader is neither Closeable nor AutoCloseable -- no try-with-resources (see ColdpTsvIT).
    ColdpReader reader = ColdpReader.from(extractDir);

    // metadata.yaml
    assertThat(extractDir.resolve("metadata.yaml")).exists();
    ColdpMetadata.ColdpMetadataDto metadata = ColdpMetadata.read(extractDir);
    assertThat(metadata.title()).isEqualTo("export-roundtrip");

    // NameUsage.tsv: 5 usages, +1 extra row for the pro-parte synonym's second accepted link = 6.
    assertThat(reader.hasSchema(ColdpTerm.NameUsage)).isTrue();
    List<VerbatimRecord> usageRecs = reader.stream(ColdpTerm.NameUsage).toList();
    assertThat(usageRecs).hasSize(6);
    assertThat(counts.nameUsageCount()).isEqualTo(6);

    List<VerbatimRecord> proParteRecs = usageRecs.stream()
        .filter(r -> "Felis vulgaris".equals(r.get(ColdpTerm.scientificName)))
        .toList();
    assertThat(proParteRecs).hasSize(2);

    List<VerbatimRecord> unassessedRecs = usageRecs.stream()
        .filter(r -> String.valueOf(unassessedNoLink.getId()).equals(r.get(ColdpTerm.ID)))
        .toList();
    assertThat(unassessedRecs).hasSize(1);
    VerbatimRecord unassessedRec = unassessedRecs.get(0);
    // CsvReader.clean() turns an empty cell into a true null (see NameUsageExportIT).
    assertThat(unassessedRec.get(ColdpTerm.parentID)).isNull();
    assertThat(unassessedRec.get(ColdpTerm.status)).isEqualTo("provisionally accepted");
    assertThat(unassessedRec.get(ColdpTerm.scientificName)).isEqualTo("Felis dubia");

    // Reference.tsv
    assertThat(reader.hasSchema(ColdpTerm.Reference)).isTrue();
    List<VerbatimRecord> refRecs = reader.stream(ColdpTerm.Reference).toList();
    assertThat(refRecs).hasSize(1);
    VerbatimRecord refRec = refRecs.get(0);
    assertThat(refRec.get(ColdpTerm.ID)).isEqualTo(String.valueOf(reference.getId()));
    assertThat(refRec.get(ColdpTerm.citation)).isEqualTo(reference.getCitation());
    assertThat(counts.referenceCount()).isEqualTo(1);

    // Distribution.tsv: FK = the species id.
    assertThat(reader.hasSchema(ColdpTerm.Distribution)).isTrue();
    List<VerbatimRecord> distRecs = reader.stream(ColdpTerm.Distribution).toList();
    assertThat(distRecs).hasSize(1);
    VerbatimRecord distRec = distRecs.get(0);
    assertThat(distRec.get(ColdpTerm.taxonID)).isEqualTo(String.valueOf(species.getId()));
    assertThat(distRec.get(ColdpTerm.areaID)).isEqualTo("tdwg:AB");

    // TypeMaterial.tsv: FK = the species id.
    assertThat(reader.hasSchema(ColdpTerm.TypeMaterial)).isTrue();
    List<VerbatimRecord> tmRecs = reader.stream(ColdpTerm.TypeMaterial).toList();
    assertThat(tmRecs).hasSize(1);
    VerbatimRecord tmRec = tmRecs.get(0);
    assertThat(tmRec.get(ColdpTerm.ID)).isEqualTo(String.valueOf(typeMaterial.id()));
    assertThat(tmRec.get(ColdpTerm.nameID)).isEqualTo(String.valueOf(species.getId()));
    assertThat(tmRec.get(ColdpTerm.institutionCode)).isEqualTo("NHM");
  }
}
