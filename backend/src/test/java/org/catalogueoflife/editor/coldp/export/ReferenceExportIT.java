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
import org.catalogueoflife.editor.name.Author;
import org.catalogueoflife.editor.name.AuthorMapper;
import org.catalogueoflife.editor.name.IdSeqMapper;
import org.catalogueoflife.editor.name.Reference;
import org.catalogueoflife.editor.name.ReferenceMapper;
import org.catalogueoflife.editor.name.ReferenceService;
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

// Task 3: Reference.tsv + Author.tsv (ColdpWriter.write, via ReferenceColdpWriter/AuthorColdpWriter).
// Reference fixtures go through ReferenceService.create -- the app's real write path -- plus one
// direct ReferenceMapper.update to seed an alternativeID CURIE: no request DTO carries alternativeId
// (see CreateReferenceRequest/UpdateReferenceRequest), so that field can only be seeded the way a
// real re-import would set it, straight on the persisted row. The app has no author-creation
// service/controller (only AuthorMapper exists -- confirmed by inspection, there is no
// AuthorService/AuthorController anywhere under src/main), so the author-present test builds its
// fixture directly via AuthorMapper (mirroring NameUsageExportIT's mapper-level fixtures) to prove
// AuthorColdpWriter itself is correct, and a second test proves Author.tsv is entirely omitted (not
// just empty) when a project has no authors.
class ReferenceExportIT extends AbstractPostgresIT {

  private static final String AUTHOR_ENTITY = "author";

  @Autowired ProjectMapper projects;
  @Autowired ProjectMemberMapper members;
  @Autowired AppUserMapper users;
  @Autowired ReferenceService referenceService;
  @Autowired ReferenceMapper references;
  @Autowired AuthorMapper authors;
  @Autowired IdSeqMapper idSeq;
  @Autowired ColdpWriter writer;

  private int createUser(String username) {
    AppUser u = new AppUser();
    u.setUsername(username);
    users.insert(u);
    return u.getId();
  }

  // ReferenceService.create authorizes via ProjectService.requireRole, which -- unlike
  // NameUsageExportIT's mapper-level fixtures -- needs an actual project_member row, not just a
  // project row: creates the project and makes userId its OWNER in one step.
  private int createProject(String title, int userId) {
    Project p = new Project();
    p.setTitle(title);
    p.setNomCode(NomCode.ZOOLOGICAL);
    projects.insert(p);
    members.upsert(new ProjectMember(p.getId(), userId, Role.OWNER.dbValue()));
    return p.getId();
  }

  @Test
  void writesReferenceTsvAndOmitsAuthorTsvWhenNoAuthors(@TempDir Path tmp) throws Exception {
    int userId = createUser("reference-export-owner");
    int pid = createProject("reference-export", userId);

    Reference plain = referenceService.create(userId, pid, new CreateReferenceRequest(
        "Plain, A. 2019. A plain citation.", "article-journal", "Plain A", null,
        "A plain title", "Journal of Plain Things", "2019", "1", "2", "10-20",
        null, null, null, null, null, null));

    Reference withDoiAndAltId = referenceService.create(userId, pid, new CreateReferenceRequest(
        "Doi, B. 2020. A DOI'd citation.", "article-journal", "Doi B", null,
        "A DOI title", "Journal of DOIs", "2020", "3", "4", "40-50",
        "Springer", "10.1234/abcd", null, null, null, null));
    withDoiAndAltId.setAlternativeId(List.of("col:REF-2"));
    references.update(withDoiAndAltId);

    Path targetZip = tmp.resolve("export.zip");
    ColdpWriter.Counts counts = writer.write(pid, targetZip);
    assertThat(counts.referenceCount()).isEqualTo(2);

    Path extractDir = tmp.resolve("extracted");
    try (InputStream in = Files.newInputStream(targetZip)) {
      ColdpZip.extractToTemp(in, extractDir);
    }
    // ColdpReader is neither Closeable nor AutoCloseable -- no try-with-resources (see ColdpTsvIT).
    ColdpReader reader = ColdpReader.from(extractDir);
    assertThat(reader.hasSchema(ColdpTerm.Reference)).isTrue();
    // No authors were created in this project -- Author.tsv must be entirely absent, not just empty.
    assertThat(reader.hasSchema(ColdpTerm.Author)).isFalse();

    List<VerbatimRecord> recs = reader.stream(ColdpTerm.Reference).toList();
    assertThat(recs).hasSize(2);

    VerbatimRecord plainRec = findOneById(recs, plain.getId());
    assertThat(plainRec.get(ColdpTerm.citation)).isEqualTo(plain.getCitation());
    assertThat(plainRec.get(ColdpTerm.title)).isEqualTo("A plain title");
    assertThat(plainRec.get(ColdpTerm.type)).isEqualTo("article-journal");
    // CsvReader.clean() turns an empty cell into a true null (see NameUsageExportIT).
    assertThat(plainRec.get(ColdpTerm.doi)).isNull();
    assertThat(plainRec.get(ColdpTerm.alternativeID)).isNull();

    VerbatimRecord doiRec = findOneById(recs, withDoiAndAltId.getId());
    assertThat(doiRec.get(ColdpTerm.citation)).isEqualTo(withDoiAndAltId.getCitation());
    assertThat(doiRec.get(ColdpTerm.doi)).isEqualTo("10.1234/abcd");
    assertThat(doiRec.get(ColdpTerm.title)).isEqualTo("A DOI title");
    assertThat(doiRec.get(ColdpTerm.type)).isEqualTo("article-journal");
    assertThat(doiRec.get(ColdpTerm.alternativeID)).isEqualTo("col:REF-2");
  }

  @Test
  void writesAuthorTsvWhenProjectHasAuthors(@TempDir Path tmp) throws Exception {
    int userId = createUser("author-export-owner");
    int pid = createProject("author-export", userId);

    Author a = new Author();
    a.setProjectId(pid);
    a.setId(idSeq.allocate(pid, AUTHOR_ENTITY));
    a.setGiven("Carl");
    a.setFamily("Linnaeus");
    a.setAbbreviationBotany("L.");
    a.setAffiliation("Uppsala University");
    a.setAlternativeId(List.of("col:AUTH-1"));
    a.setModifiedBy(userId);
    authors.insert(a);

    // ColdpReader.validate() requires at least one of Name/NameUsage/Reference mapped (see
    // ColdpReader.validate -> requireOneSchema); this project has no name usages, so give it one
    // reference purely to keep the archive readable -- unrelated to what this test asserts.
    referenceService.create(userId, pid, new CreateReferenceRequest(
        "Filler, C. 2021. A filler reference.", null, null, null, null, null, null, null, null,
        null, null, null, null, null, null, null));

    Path targetZip = tmp.resolve("export.zip");
    writer.write(pid, targetZip);

    Path extractDir = tmp.resolve("extracted");
    try (InputStream in = Files.newInputStream(targetZip)) {
      ColdpZip.extractToTemp(in, extractDir);
    }
    ColdpReader reader = ColdpReader.from(extractDir);
    assertThat(reader.hasSchema(ColdpTerm.Author)).isTrue();

    List<VerbatimRecord> recs = reader.stream(ColdpTerm.Author).toList();
    assertThat(recs).hasSize(1);
    VerbatimRecord rec = recs.get(0);
    assertThat(rec.get(ColdpTerm.ID)).isEqualTo(String.valueOf(a.getId()));
    assertThat(rec.get(ColdpTerm.given)).isEqualTo("Carl");
    assertThat(rec.get(ColdpTerm.family)).isEqualTo("Linnaeus");
    assertThat(rec.get(ColdpTerm.abbreviationBotany)).isEqualTo("L.");
    assertThat(rec.get(ColdpTerm.affiliation)).isEqualTo("Uppsala University");
    assertThat(rec.get(ColdpTerm.alternativeID)).isEqualTo("col:AUTH-1");
  }

  private static VerbatimRecord findOneById(List<VerbatimRecord> recs, int id) {
    List<VerbatimRecord> matches = recs.stream()
        .filter(r -> String.valueOf(id).equals(r.get(ColdpTerm.ID)))
        .toList();
    assertThat(matches).as("rows for ID=" + id).hasSize(1);
    return matches.get(0);
  }
}
