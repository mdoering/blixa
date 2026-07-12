package org.catalogueoflife.editor.merge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.catalogueoflife.editor.merge.dto.Candidate;
import org.catalogueoflife.editor.merge.dto.Category;
import org.catalogueoflife.editor.name.Reference;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

// Spring IT against the real testcontainers Postgres (see AbstractPostgresIT): seeds a target
// project with two references (one carrying a DOI, one citation-only) and a source project with
// four references, one per Category ReferenceMatcher.match can produce -- the trigram fuzzy path
// (POSSIBLE) needs the real reference_citation_trgm GIN index (V3__name_core.sql), which a
// mapper-mocking unit test can't exercise; see ReferenceMatcherTest for the pure normDoi/
// normCitation unit coverage.
//
// Overrides coldp.merge.citation-similarity down from the production default (0.9) to 0.5: the
// fuzzy case below is a single-word typo in an otherwise-identical, fairly long citation, which
// verified similarity() at 0.9365 on real Postgres 17 (already above the untouched 0.9 default) --
// the lower, more-comfortable-margin threshold used here keeps this test robust to minor wording
// changes rather than depending on reliably clearing a narrow band just above 0.9. The NEW case's
// citation shares no words at all with any target citation, so its similarity is close to 0 --
// nowhere near even this lowered 0.5 threshold.
@SpringBootTest(properties = "coldp.merge.citation-similarity=0.5")
class ReferenceMatcherIT extends AbstractPostgresIT {

  @Autowired ProjectMapper projects;
  @Autowired ProjectMemberMapper members;
  @Autowired AppUserMapper users;
  @Autowired ReferenceService referenceService;
  @Autowired ReferenceMatcher matcher;

  private int createUser(String username) {
    AppUser u = new AppUser();
    u.setUsername(username);
    users.insert(u);
    return u.getId();
  }

  // ReferenceService.create authorizes via ProjectService.requireRole, which needs an actual
  // project_member row, not just a project row (mirrors ReferenceExportIT's fixture helper).
  private int createProject(String title, int userId) {
    Project p = new Project();
    p.setTitle(title);
    p.setNomCode(NomCode.ZOOLOGICAL);
    projects.insert(p);
    members.upsert(new ProjectMember(p.getId(), userId, Role.OWNER.dbValue()));
    return p.getId();
  }

  private Reference newReference(int userId, int projectId, String citation, String doi) {
    return referenceService.create(userId, projectId, new CreateReferenceRequest(
        citation, false, null, null, null, null, null, null, null, null, null,
        null, null, doi, null, null, null, null, null));
  }

  @Test
  void matchAssignsExpectedCategoryPerSourceReference() {
    int targetOwner = createUser("reference-matcher-it-target-owner");
    int sourceOwner = createUser("reference-matcher-it-source-owner");

    int targetProjectId = createProject("mergeTargetReferences", targetOwner);
    int sourceProjectId = createProject("mergeSourceReferences", sourceOwner);

    // Target: one DOI-bearing reference, one citation-only reference.
    Reference targetDoi = newReference(targetOwner, targetProjectId,
        "Doe, J. 2020. A DOI title. Journal, 1, 2-3.", "10.1234/abcd");
    Reference targetCitationOnly = newReference(targetOwner, targetProjectId,
        "Smith, A. 2019. A distinctive title about xylophones. Journal, 4, 5-6.", null);

    // (1) same DOI, different prefix (https://doi.org/... vs bare) -> MATCHED to the DOI target.
    // The citation deliberately does NOT match anything, to prove the DOI path -- not an
    // accidental citation hit -- is what produced the match.
    Reference srcSameDoi = newReference(sourceOwner, sourceProjectId,
        "Doe, J. 2020. An unrelated citation string that matches nothing by text.",
        "https://doi.org/10.1234/abcd");

    // (2) no DOI, but the citation is a whitespace/case/trailing-punctuation variant of the
    // citation-only target's citation -> MATCHED to that target.
    Reference srcSameCitation = newReference(sourceOwner, sourceProjectId,
        "  SMITH, A.   2019.  A DISTINCTIVE TITLE ABOUT XYLOPHONES.  Journal, 4, 5-6.,; ", null);

    // (3) no DOI, no exact citation match, but a single-word typo of the citation-only target's
    // citation -> POSSIBLE (fuzzy), targetId set, score < 1.
    Reference srcFuzzy = newReference(sourceOwner, sourceProjectId,
        "Smith, A. 2019. A distinctive title about xylophonez. Journal, 4, 5-6.", null);

    // (4) shares no DOI and no words with either target citation -> NEW.
    Reference srcNew = newReference(sourceOwner, sourceProjectId,
        "Zephyrus, Q. 2099. Something totally unrelated about quantum toast. Nowhere Press.", null);

    List<Candidate> result = matcher.match(sourceProjectId, targetProjectId);
    assertThat(result).hasSize(4);

    Map<String, Candidate> bySourceId = result.stream()
        .collect(Collectors.toMap(Candidate::sourceId, c -> c));

    Candidate matchedByDoi = bySourceId.get(String.valueOf(srcSameDoi.getId()));
    assertThat(matchedByDoi.category()).isEqualTo(Category.MATCHED);
    assertThat(matchedByDoi.targetId()).isEqualTo(String.valueOf(targetDoi.getId()));
    assertThat(matchedByDoi.score()).isEqualTo(1.0);

    Candidate matchedByCitation = bySourceId.get(String.valueOf(srcSameCitation.getId()));
    assertThat(matchedByCitation.category()).isEqualTo(Category.MATCHED);
    assertThat(matchedByCitation.targetId()).isEqualTo(String.valueOf(targetCitationOnly.getId()));
    assertThat(matchedByCitation.score()).isEqualTo(1.0);

    Candidate fuzzy = bySourceId.get(String.valueOf(srcFuzzy.getId()));
    assertThat(fuzzy.category()).isEqualTo(Category.POSSIBLE);
    assertThat(fuzzy.targetId()).isEqualTo(String.valueOf(targetCitationOnly.getId()));
    assertThat(fuzzy.score()).isNotNull();
    // similarity() of the two citations via pg_trgm -- see the class comment.
    assertThat(fuzzy.score()).isEqualTo(0.9365, org.assertj.core.data.Offset.offset(0.001));

    Candidate brandNew = bySourceId.get(String.valueOf(srcNew.getId()));
    assertThat(brandNew.category()).isEqualTo(Category.NEW);
    assertThat(brandNew.targetId()).isNull();
    assertThat(brandNew.score()).isNull();
  }
}
