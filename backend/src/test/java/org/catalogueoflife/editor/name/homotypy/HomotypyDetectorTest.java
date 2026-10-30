package org.catalogueoflife.editor.name.homotypy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.catalogueoflife.editor.child.dto.NameRelationResponse;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.name.Status;
import org.catalogueoflife.editor.name.homotypy.dto.HomotypyProposal;
import org.catalogueoflife.editor.name.homotypy.dto.ProposedGroup;
import org.catalogueoflife.editor.name.homotypy.dto.ProposedRelation;
import org.gbif.nameparser.api.NomCode;
import org.junit.jupiter.api.Test;

class HomotypyDetectorTest {

  private static final NomCode ZOO = NomCode.ZOOLOGICAL;

  private final HomotypyDetector detector = new HomotypyDetector();

  /** A species usage with atomized fields, as the parser stores them. */
  private static NameUsage usage(int id, Status status, String genus, String sp,
      String combAuthor, String combYear, String basAuthor, String basYear) {
    NameUsage u = new NameUsage();
    u.setId(id);
    u.setProjectId(1);
    u.setStatus(status);
    u.setRank("species");
    u.setGenus(genus);
    u.setSpecificEpithet(sp);
    u.setScientificName(genus + " " + sp);
    u.setCombinationAuthorship(combAuthor);
    u.setCombinationAuthorshipYear(combYear);
    u.setBasionymAuthorship(basAuthor);
    u.setBasionymAuthorshipYear(basYear);
    StringBuilder a = new StringBuilder();
    if (basAuthor != null) {
      a.append('(').append(basAuthor).append(basYear == null ? "" : ", " + basYear).append(')');
    }
    if (combAuthor != null) {
      if (!a.isEmpty()) a.append(' ');
      a.append(combAuthor).append(combYear == null ? "" : ", " + combYear);
    }
    u.setAuthorship(a.isEmpty() ? null : a.toString());
    return u;
  }

  private static NameUsage original(int id, Status status, String genus, String sp, String author, String year) {
    return usage(id, status, genus, sp, author, year, null, null);
  }

  private static NameUsage recombination(int id, Status status, String genus, String sp, String basAuthor, String basYear) {
    return usage(id, status, genus, sp, null, null, basAuthor, basYear);
  }

  private static NameRelationResponse rel(int from, int to, String type) {
    return new NameRelationResponse(null, from, null, to, null, type, null, null, null, null);
  }

  private static ExistingRelations existing(NameRelationResponse... rels) {
    return ExistingRelations.of(List.of(rels));
  }

  private static List<ProposedRelation> relations(HomotypyProposal p) {
    return p.groups().stream().flatMap(g -> g.relations().stream()).toList();
  }

  private static ProposedGroup groupOf(HomotypyProposal p, int memberId) {
    return p.groups().stream().filter(g -> g.memberUsageIds().contains(memberId)).findFirst()
        .orElseThrow(() -> new AssertionError("no group holds " + memberId));
  }

  private static boolean relates(ProposedRelation r, int a, int b) {
    return r.usageId() == a && r.relatedUsageId() == b || r.usageId() == b && r.relatedUsageId() == a;
  }

  @Test
  void abbreviatedAuthorGroups() {
    NameUsage aus = original(1, Status.ACCEPTED, "Aus", "bus", "L.", null);
    NameUsage cus = recombination(2, Status.ACCEPTED, "Cus", "bus", "Linnaeus", null);

    HomotypyProposal p = detector.group(List.of(aus, cus), Map.of(), ExistingRelations.EMPTY, ZOO);

    ProposedGroup g = groupOf(p, 1);
    assertThat(g.basionymUsageId()).isEqualTo(1);
    assertThat(g.memberUsageIds()).containsExactlyInAnyOrder(1, 2);
    assertThat(g.relations()).containsExactly(new ProposedRelation(2, 1, "basionym", false));
  }

  @Test
  void yearWithinToleranceGroups() {
    NameUsage aus = original(1, Status.ACCEPTED, "Aus", "bus", "Smith", "1850");
    NameUsage cus = recombination(2, Status.ACCEPTED, "Cus", "bus", "Smith", "1851");

    HomotypyProposal p = detector.group(List.of(aus, cus), Map.of(), ExistingRelations.EMPTY, ZOO);

    ProposedGroup g = groupOf(p, 1);
    assertThat(g.memberUsageIds()).containsExactlyInAnyOrder(1, 2);
    assertThat(g.relations()).containsExactly(new ProposedRelation(2, 1, "basionym", false));
  }

  @Test
  void differentAuthorsDoNotGroup() {
    NameUsage aus = original(1, Status.ACCEPTED, "Aus", "bus", "L.", null);
    NameUsage cus = recombination(2, Status.SYNONYM, "Cus", "bus", "Mill.", null);

    HomotypyProposal p = detector.detect(aus, List.of(cus), ExistingRelations.EMPTY, ZOO);

    assertThat(relations(p)).isEmpty();
    assertThat(p.groups()).noneSatisfy(g -> assertThat(g.memberUsageIds()).contains(1, 2));
  }

  @Test
  void originalsInTwoGeneraGroupWhenSynonymyLinksThem() {
    // two unbracketed originals of the same author in different genera, the synonymy links them
    NameUsage aus = original(1, Status.ACCEPTED, "Aus", "bus", "L.", null);
    NameUsage dorcus = original(2, Status.SYNONYM, "Dorcus", "bus", "L.", null);

    HomotypyProposal p = detector.detect(aus, List.of(dorcus), ExistingRelations.EMPTY, ZOO);

    ProposedGroup g = groupOf(p, 1);
    assertThat(g.memberUsageIds()).containsExactlyInAnyOrder(1, 2);
    // the accepted name is preferred as the original, the other original is homotypic with it
    assertThat(g.basionymUsageId()).isEqualTo(1);
    assertThat(g.relations()).containsExactly(new ProposedRelation(2, 1, "homotypic", false));
  }

  @Test
  void originalsInTwoGeneraGroupWhenARelationLinksThem() {
    NameUsage aus = original(1, Status.ACCEPTED, "Aus", "bus", "L.", null);
    NameUsage dorcus = original(2, Status.ACCEPTED, "Dorcus", "bus", "L.", null);

    HomotypyProposal p = detector.group(List.of(aus, dorcus), Map.of(),
        existing(rel(2, 1, "homotypic")), ZOO);

    ProposedGroup g = groupOf(p, 1);
    assertThat(g.memberUsageIds()).containsExactlyInAnyOrder(1, 2);
    assertThat(g.relations()).containsExactly(new ProposedRelation(2, 1, "homotypic", true));
  }

  @Test
  void originalsInTwoGeneraStayApartWhenNothingLinksThem() {
    NameUsage aus = original(1, Status.ACCEPTED, "Aus", "bus", "L.", null);
    NameUsage dorcus = original(2, Status.ACCEPTED, "Dorcus", "bus", "L.", null);

    HomotypyProposal p = detector.group(List.of(aus, dorcus), Map.of(), ExistingRelations.EMPTY, ZOO);

    assertThat(p.groups()).hasSize(2);
    assertThat(p.groups()).noneSatisfy(g -> assertThat(g.memberUsageIds()).contains(1, 2));
    assertThat(relations(p)).isEmpty();
  }

  @Test
  void knownMisspellingNeverBasionym() {
    // Aus ba is the known misspelling of Aus bus (spelling correction Aus bus -> Aus ba). Both are
    // accepted, so without the evidence "Aus ba" would win the label tie-break.
    // ("buss" from the brief does not share a bucket with "bus": normalizeEpithet gives "bus" vs "b".)
    NameUsage aus = original(1, Status.ACCEPTED, "Aus", "bus", "L.", null);
    NameUsage misspelt = original(2, Status.ACCEPTED, "Aus", "ba", "L.", null);
    NameUsage cus = recombination(3, Status.ACCEPTED, "Cus", "bus", "L.", null);

    HomotypyProposal p = detector.group(List.of(misspelt, cus, aus), Map.of(),
        existing(rel(1, 2, "spelling correction")), ZOO);

    ProposedGroup g = groupOf(p, 1);
    assertThat(g.basionymUsageId()).isEqualTo(1);
    assertThat(g.memberUsageIds()).containsExactlyInAnyOrder(1, 2, 3);
    assertThat(g.relations()).containsExactlyInAnyOrder(
        new ProposedRelation(3, 1, "basionym", false),
        new ProposedRelation(1, 2, "spelling correction", true));
  }

  @Test
  void noRelationBetweenLinkedOrHomonymNames() {
    NameUsage aus = original(1, Status.ACCEPTED, "Aus", "bus", "L.", null);
    NameUsage cus = recombination(2, Status.SYNONYM, "Cus", "bus", "L.", null);
    NameUsage dorcus = recombination(3, Status.SYNONYM, "Dorcus", "bus", "L.", null);
    NameUsage eus = recombination(4, Status.SYNONYM, "Eus", "bus", "L.", null);
    // Cus -> Aus is stored; Dorcus is homotypic with Cus, so Dorcus and Aus are linked through Cus.
    // Eus is stored as a later homonym of Aus.
    ExistingRelations ex = existing(rel(2, 1, "basionym"), rel(3, 2, "homotypic"), rel(4, 1, "later homonym"));

    HomotypyProposal p = detector.detect(aus, List.of(cus, dorcus, eus), ex, ZOO);

    assertThat(groupOf(p, 1).memberUsageIds()).containsExactlyInAnyOrder(1, 2, 3, 4);
    List<ProposedRelation> rels = relations(p);
    assertThat(rels).containsExactly(new ProposedRelation(2, 1, "basionym", true));
    assertThat(rels).noneMatch(r -> relates(r, 3, 1));
    assertThat(rels).noneMatch(r -> relates(r, 4, 1));
  }

  @Test
  void misappliedExcluded() {
    NameUsage aus = original(1, Status.ACCEPTED, "Aus", "bus", "L.", null);
    NameUsage misapplied = original(9, Status.MISAPPLIED, "Aus", "bus", "auct.", null);

    HomotypyProposal p = detector.detect(aus, List.of(misapplied), ExistingRelations.EMPTY, ZOO);

    assertThat(p.groups()).noneSatisfy(g -> assertThat(g.memberUsageIds()).contains(9));
  }

  @Test
  void existingRelationMarkedExists() {
    NameUsage aus = original(1, Status.ACCEPTED, "Aus", "bus", "L.", null);
    NameUsage cus = recombination(2, Status.SYNONYM, "Cus", "bus", "L.", null);

    HomotypyProposal p = detector.detect(aus, List.of(cus), existing(rel(2, 1, "BASIONYM")), ZOO);

    assertThat(relations(p)).containsExactly(new ProposedRelation(2, 1, "basionym", true));
  }

  @Test
  void unknownCodeAndUnparsedNamesDoNotBreakGrouping() {
    NameUsage poa = original(1, Status.ACCEPTED, "Poa", "annua", "L.", "1753");
    NameUsage ochlopoa = usage(2, Status.SYNONYM, "Ochlopoa", "annua", "H.Scholz", null, "L.", null);
    // unparsed: no rank, no atomized parts, no authorship
    NameUsage unparsed = new NameUsage();
    unparsed.setId(3);
    unparsed.setStatus(Status.SYNONYM);
    unparsed.setScientificName("Poa annua sensu lato");
    // atomized but without authorship
    NameUsage noAuthor = original(4, Status.SYNONYM, "Poa", "annua", null, null);

    HomotypyProposal p = detector.detect(poa, List.of(ochlopoa, unparsed, noAuthor), ExistingRelations.EMPTY, null);

    assertThat(relations(p)).containsExactly(new ProposedRelation(2, 1, "basionym", false));
  }
}
