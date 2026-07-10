package org.catalogueoflife.editor.coldp.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import life.catalogue.api.model.VerbatimRecord;
import life.catalogue.coldp.ColdpTerm;
import life.catalogue.csv.ColdpReader;
import org.catalogueoflife.editor.coldp.io.ColdpZip;
import org.catalogueoflife.editor.name.IdSeqMapper;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.name.NameUsageMapper;
import org.catalogueoflife.editor.name.Status;
import org.catalogueoflife.editor.name.SynonymAcceptedMapper;
import org.catalogueoflife.editor.project.Project;
import org.catalogueoflife.editor.project.ProjectMapper;
import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserMapper;
import org.gbif.nameparser.api.NomCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

// Task 2: the combined NameUsage.tsv (ColdpWriter.write, via NameUsageColdpWriter). Drives
// ColdpWriter.write directly (like NameUsageMapperIT/ColMatchJobIT drive services at the
// mapper/service level, no MockMvc/HTTP needed) against a small fixture: an accepted
// kingdom->species classification, a plain synonym, and a pro-parte synonym linked to BOTH
// accepted usages -- the case that drives the "<synonymId>-<acceptedId>" derived-row id scheme.
class NameUsageExportIT extends AbstractPostgresIT {

  private static final String ENTITY = "name_usage";

  @Autowired ProjectMapper projects;
  @Autowired AppUserMapper users;
  @Autowired NameUsageMapper nameUsages;
  @Autowired SynonymAcceptedMapper synonymAccepted;
  @Autowired IdSeqMapper idSeq;
  @Autowired ColdpWriter writer;

  private int createUser(String username) {
    AppUser u = new AppUser();
    u.setUsername(username);
    users.insert(u);
    return u.getId();
  }

  private int createProject(String title, NomCode nomCode) {
    Project p = new Project();
    p.setTitle(title);
    p.setNomCode(nomCode);
    projects.insert(p);
    return p.getId();
  }

  private NameUsage createUsage(int projectId, String scientificName, String rank, Status status,
      Integer parentId, List<String> alternativeId, int userId) {
    NameUsage u = new NameUsage();
    u.setProjectId(projectId);
    u.setId(idSeq.allocate(projectId, ENTITY));
    u.setStatus(status);
    u.setScientificName(scientificName);
    u.setRank(rank);
    u.setParentId(parentId);
    u.setAlternativeId(alternativeId);
    u.setModifiedBy(userId);
    nameUsages.insert(u);
    return u;
  }

  @Test
  void writesCombinedNameUsageTsvWithProParteDerivedIds(@TempDir Path tmp) throws Exception {
    int userId = createUser("name-usage-export-owner");
    int pid = createProject("name-usage-export", NomCode.ZOOLOGICAL);

    NameUsage root = createUsage(pid, "Animalia", "kingdom", Status.ACCEPTED, null, null, userId);
    NameUsage species = createUsage(pid, "Aus bus", "species", Status.ACCEPTED, root.getId(), null, userId);
    NameUsage xus = createUsage(pid, "Xus bus", "species", Status.SYNONYM, null, null, userId);
    NameUsage dus = createUsage(pid, "Dus bus", "species", Status.SYNONYM, null,
        List.of("col:XYZ"), userId);

    synonymAccepted.link(pid, xus.getId(), species.getId(), null);
    // pro parte: Dus bus is a synonym of BOTH the species and the root.
    synonymAccepted.link(pid, dus.getId(), species.getId(), null);
    synonymAccepted.link(pid, dus.getId(), root.getId(), null);

    Path targetZip = tmp.resolve("export.zip");
    ColdpWriter.Counts counts = writer.write(pid, targetZip);

    Path extractDir = tmp.resolve("extracted");
    try (InputStream in = Files.newInputStream(targetZip)) {
      ColdpZip.extractToTemp(in, extractDir);
    }
    // ColdpReader is neither Closeable nor AutoCloseable -- no try-with-resources (see ColdpTsvIT).
    ColdpReader reader = ColdpReader.from(extractDir);
    assertThat(reader.hasSchema(ColdpTerm.NameUsage)).isTrue();
    List<VerbatimRecord> recs = reader.stream(ColdpTerm.NameUsage).toList();

    // 2 accepted rows + 1 plain synonym row + 2 pro-parte rows for Dus bus = 5.
    assertThat(recs).hasSize(5);
    assertThat(counts.nameUsageCount()).isEqualTo(5);

    String expectedCode = "zoological";

    VerbatimRecord rootRec = findOneByScientificName(recs, "Animalia");
    assertThat(rootRec.get(ColdpTerm.status)).isEqualTo("accepted");
    assertThat(rootRec.get(ColdpTerm.ID)).isEqualTo(String.valueOf(root.getId()));
    // CsvReader.clean() turns an empty cell into a true null (see VerbatimRecord.get javadoc).
    assertThat(rootRec.get(ColdpTerm.parentID)).isNull();
    assertThat(rootRec.get(ColdpTerm.code)).isEqualTo(expectedCode);

    VerbatimRecord speciesRec = findOneByScientificName(recs, "Aus bus");
    assertThat(speciesRec.get(ColdpTerm.status)).isEqualTo("accepted");
    assertThat(speciesRec.get(ColdpTerm.ID)).isEqualTo(String.valueOf(species.getId()));
    assertThat(speciesRec.get(ColdpTerm.parentID)).isEqualTo(String.valueOf(root.getId()));

    VerbatimRecord xusRec = findOneByScientificName(recs, "Xus bus");
    assertThat(xusRec.get(ColdpTerm.status)).isEqualTo("synonym");
    assertThat(xusRec.get(ColdpTerm.ID)).isEqualTo(String.valueOf(xus.getId()));
    assertThat(xusRec.get(ColdpTerm.parentID)).isEqualTo(String.valueOf(species.getId()));

    List<VerbatimRecord> dusRecs = recs.stream()
        .filter(r -> "Dus bus".equals(r.get(ColdpTerm.scientificName)))
        .toList();
    assertThat(dusRecs).hasSize(2);

    int lowerAccId = Math.min(species.getId(), root.getId());
    int higherAccId = Math.max(species.getId(), root.getId());

    VerbatimRecord dusPrimary = dusRecs.stream()
        .filter(r -> String.valueOf(dus.getId()).equals(r.get(ColdpTerm.ID)))
        .findFirst().orElseThrow(() -> new AssertionError("no primary Dus bus row found in " + dusRecs));
    assertThat(dusPrimary.get(ColdpTerm.parentID)).isEqualTo(String.valueOf(lowerAccId));
    assertThat(dusPrimary.get(ColdpTerm.status)).isEqualTo("synonym");
    assertThat(dusPrimary.get(ColdpTerm.alternativeID)).isEqualTo("col:XYZ");

    String derivedId = dus.getId() + "-" + higherAccId;
    VerbatimRecord dusDerived = dusRecs.stream()
        .filter(r -> derivedId.equals(r.get(ColdpTerm.ID)))
        .findFirst().orElseThrow(() -> new AssertionError("no derived Dus bus row found in " + dusRecs));
    assertThat(dusDerived.get(ColdpTerm.parentID)).isEqualTo(String.valueOf(higherAccId));
    assertThat(dusDerived.get(ColdpTerm.status)).isEqualTo("synonym");
  }

  private static VerbatimRecord findOneByScientificName(List<VerbatimRecord> recs, String name) {
    List<VerbatimRecord> matches = recs.stream()
        .filter(r -> name.equals(r.get(ColdpTerm.scientificName)))
        .toList();
    assertThat(matches).as("rows for scientificName=" + name).hasSize(1);
    return matches.get(0);
  }
}
