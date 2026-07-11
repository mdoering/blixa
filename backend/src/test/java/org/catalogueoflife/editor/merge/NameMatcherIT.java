package org.catalogueoflife.editor.merge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.catalogueoflife.editor.merge.dto.Candidate;
import org.catalogueoflife.editor.merge.dto.Category;
import org.catalogueoflife.editor.name.IdSeqMapper;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.name.NameUsageMapper;
import org.catalogueoflife.editor.name.Status;
import org.catalogueoflife.editor.parse.NameParserService;
import org.catalogueoflife.editor.project.Project;
import org.catalogueoflife.editor.project.ProjectMapper;
import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserMapper;
import org.gbif.nameparser.api.NomCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

// Spring IT against the real testcontainers Postgres (see AbstractPostgresIT): seeds one target
// project with a small real classification (Panthera / Panthera leo (Linnaeus, 1758)) and one
// source project with four usages, one per Category NameMatcher.match can produce (a fifth test
// method below covers the remaining category, POSSIBLE), and asserts the full source->target
// mapping in one call -- the trigram fuzzy path (POSSIBLE_FUZZY) needs the real
// name_usage_sciname_trgm GIN index (V3__name_core.sql), which a mapper-mocking unit test can't
// exercise; see NameMatcherTest for the pure canonicalKey/authorCompatible/notho unit coverage.
//
// Runs against the production coldp.merge.name-similarity default (0.7, see application.yml /
// NameMatcher's @Value default) -- no per-test override needed: `similarity('Panthera leo',
// 'Panthera leoo')` is 0.8 (verified against a live Postgres 17), comfortably above 0.7, so the
// misspelling below deterministically clears the threshold (POSSIBLE_FUZZY). The "Panthera onca"
// NEW case's best rank-filtered candidate (species-rank "Panthera leo" -- the fuzzy query is now
// rank-qualified, so the genus-rank "Panthera" row is never even considered) sits at similarity
// 0.5, comfortably below 0.7, so that assertion stays NEW too.
class NameMatcherIT extends AbstractPostgresIT {

  private static final String ENTITY = "name_usage";

  @Autowired ProjectMapper projects;
  @Autowired AppUserMapper users;
  @Autowired NameUsageMapper usages;
  @Autowired IdSeqMapper idSeq;
  @Autowired NameParserService parser;
  @Autowired NameMatcher matcher;

  private int newProject(String title) {
    Project p = new Project();
    p.setTitle(title);
    p.setNomCode(NomCode.ZOOLOGICAL);
    projects.insert(p);
    return p.getId();
  }

  // Creates + inserts a usage through the same parse-then-allocate-then-insert sequence
  // NameUsageService.create uses, so the atomized name-part fields NameMatcher.canonicalKey reads
  // are populated exactly as they would be for a usage created through the normal API.
  private NameUsage newUsage(int projectId, int userId, String scientificName, String authorship, String rank) {
    NameUsage u = new NameUsage();
    u.setProjectId(projectId);
    u.setScientificName(scientificName);
    u.setAuthorship(authorship);
    u.setRank(rank);
    u.setStatus(Status.ACCEPTED);
    u.setModifiedBy(userId);
    parser.parseInto(u, NomCode.ZOOLOGICAL);
    u.setId(idSeq.allocate(projectId, ENTITY));
    usages.insert(u);
    return u;
  }

  @Test
  void matchAssignsExpectedCategoryPerSourceUsage() {
    AppUser u = new AppUser();
    u.setUsername("name-matcher-it");
    users.insert(u);
    int userId = u.getId();

    int targetProjectId = newProject("mergeTargetPanthera");
    int sourceProjectId = newProject("mergeSourcePanthera");

    // Target: a small real classification -- the genus plus its type species, authored.
    newUsage(targetProjectId, userId, "Panthera", null, "genus");
    NameUsage targetLeo = newUsage(targetProjectId, userId, "Panthera leo", "(Linnaeus, 1758)", "species");

    // (1) exact same canonical key + compatible author -> MATCHED.
    NameUsage srcMatched = newUsage(sourceProjectId, userId, "Panthera leo", "(Linnaeus, 1758)", "species");
    // (2) same canonical key, incompatible author -> POSSIBLE_HOMONYM.
    NameUsage srcHomonym = newUsage(sourceProjectId, userId, "Panthera leo", "Smith, 1900", "species");
    // (3) a misspelling with no exact-key candidate but a close trigram match -> POSSIBLE_FUZZY.
    NameUsage srcFuzzy = newUsage(sourceProjectId, userId, "Panthera leoo", null, "species");
    // (4) a genuinely new name, no exact or fuzzy candidate -> NEW.
    NameUsage srcNew = newUsage(sourceProjectId, userId, "Panthera onca", "Linnaeus, 1758", "species");

    List<Candidate> result = matcher.match(sourceProjectId, targetProjectId);
    assertThat(result).hasSize(4);

    Map<String, Candidate> bySourceId = result.stream()
        .collect(Collectors.toMap(Candidate::sourceId, c -> c));

    Candidate matched = bySourceId.get(String.valueOf(srcMatched.getId()));
    assertThat(matched.category()).isEqualTo(Category.MATCHED);
    assertThat(matched.targetId()).isEqualTo(String.valueOf(targetLeo.getId()));
    assertThat(matched.score()).isEqualTo(1.0);

    Candidate homonym = bySourceId.get(String.valueOf(srcHomonym.getId()));
    assertThat(homonym.category()).isEqualTo(Category.POSSIBLE_HOMONYM);
    assertThat(homonym.targetId()).isEqualTo(String.valueOf(targetLeo.getId()));

    Candidate fuzzy = bySourceId.get(String.valueOf(srcFuzzy.getId()));
    assertThat(fuzzy.category()).isEqualTo(Category.POSSIBLE_FUZZY);
    assertThat(fuzzy.targetId()).isEqualTo(String.valueOf(targetLeo.getId()));
    // similarity('Panthera leo', 'Panthera leoo') via pg_trgm -- see the class comment.
    assertThat(fuzzy.score()).isEqualTo(0.8, org.assertj.core.data.Offset.offset(0.001));

    Candidate brandNew = bySourceId.get(String.valueOf(srcNew.getId()));
    assertThat(brandNew.category()).isEqualTo(Category.NEW);
    assertThat(brandNew.targetId()).isNull();
    assertThat(brandNew.score()).isNull();
  }

  @Test
  void matchAssignsPossibleWhenMultipleTargetCandidatesAreAuthorCompatible() {
    // The remaining Category not covered by matchAssignsExpectedCategoryPerSourceUsage above:
    // POSSIBLE fires when >= 2 same-canonical-key target usages are each author-compatible with
    // the source (see NameMatcher.matchOne) -- i.e. the target itself already has an ambiguous,
    // curator-facing duplicate. Seeded here as two literally identical target usages ("Panthera
    // leo" (Linnaeus, 1758) twice) -- name_usage has no uniqueness constraint on
    // (scientific_name, authorship) that would block this (its only UNIQUE was (project_id,
    // coldp_id), and that whole column was dropped in V14__drop_coldp_id.sql), so this is a
    // realistic "genuine intra-target duplicate" scenario, not a schema workaround.
    AppUser u = new AppUser();
    u.setUsername("name-matcher-it-possible");
    users.insert(u);
    int userId = u.getId();

    int targetProjectId = newProject("mergeTargetPossible");
    int sourceProjectId = newProject("mergeSourcePossible");

    NameUsage target1 = newUsage(targetProjectId, userId, "Panthera leo", "(Linnaeus, 1758)", "species");
    NameUsage target2 = newUsage(targetProjectId, userId, "Panthera leo", "(Linnaeus, 1758)", "species");

    NameUsage src = newUsage(sourceProjectId, userId, "Panthera leo", "(Linnaeus, 1758)", "species");

    List<Candidate> result = matcher.match(sourceProjectId, targetProjectId);
    assertThat(result).hasSize(1);

    Candidate possible = result.get(0);
    assertThat(possible.sourceId()).isEqualTo(String.valueOf(src.getId()));
    assertThat(possible.category()).isEqualTo(Category.POSSIBLE);
    // A suggestion only (the lowest-id compatible candidate) -- either target is a valid pick.
    assertThat(possible.targetId())
        .isIn(String.valueOf(target1.getId()), String.valueOf(target2.getId()));
    assertThat(possible.score()).isNull();
  }
}
