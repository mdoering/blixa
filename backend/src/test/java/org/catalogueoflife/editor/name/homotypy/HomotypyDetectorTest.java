package org.catalogueoflife.editor.name.homotypy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.name.Status;
import org.catalogueoflife.editor.name.homotypy.dto.HomotypyProposal;
import org.junit.jupiter.api.Test;

class HomotypyDetectorTest {

  private final HomotypyDetector detector = new HomotypyDetector();

  private NameUsage usage(int id, Status status, String genus, String sp, String infra,
      String combAuthor, String combYear, String basAuthor, String basYear) {
    NameUsage u = new NameUsage();
    u.setId(id);
    u.setProjectId(1);
    u.setStatus(status);
    u.setGenus(genus);
    u.setSpecificEpithet(sp);
    u.setInfraspecificEpithet(infra);
    u.setCombinationAuthorship(combAuthor);
    u.setCombinationAuthorshipYear(combYear);
    u.setBasionymAuthorship(basAuthor);
    u.setBasionymAuthorshipYear(basYear);
    return u;
  }

  @Test
  void recombinationGroupsToAcceptedBasionym() {
    // Poa annua L. (basionym) + Ochlopoa annua (L.) H.Scholz (recombination)
    NameUsage accepted = usage(1, Status.ACCEPTED, "Poa", "annua", null, "L.", "1753", null, null);
    NameUsage recomb = usage(2, Status.SYNONYM, "Ochlopoa", "annua", null, "H.Scholz", null, "L.", null);

    HomotypyProposal p = detector.detect(accepted, List.of(recomb), Set.of());

    // one group with basionym=1, one basionym relation 2 -> 1
    assertThat(p.groups()).anySatisfy(g -> {
      assertThat(g.basionymUsageId()).isEqualTo(1);
      assertThat(g.relations()).anySatisfy(r -> {
        assertThat(r.usageId()).isEqualTo(2);
        assertThat(r.relatedUsageId()).isEqualTo(1);
        assertThat(r.type()).isEqualTo("basionym");
        assertThat(r.alreadyExists()).isFalse();
      });
    });
  }

  @Test
  void differentEpithetsStayInSeparateGroups() {
    NameUsage accepted = usage(1, Status.ACCEPTED, "Poa", "annua", null, "L.", "1753", null, null);
    NameUsage aira = usage(3, Status.SYNONYM, "Aira", "pumila", null, "Pursh", "1814", null, null);
    NameUsage catabrosa = usage(4, Status.SYNONYM, "Catabrosa", "pumila", null, "Roem. & Schult.", null, "Pursh", null);

    HomotypyProposal p = detector.detect(accepted, List.of(aira, catabrosa), Set.of());

    // Aira pumila is a basionym with Catabrosa pumila as its recombination; not merged with Poa annua
    assertThat(p.groups()).anySatisfy(g -> {
      assertThat(g.basionymUsageId()).isEqualTo(3);
      assertThat(g.memberUsageIds()).containsExactlyInAnyOrder(3, 4);
    });
    assertThat(p.groups()).noneSatisfy(g -> assertThat(g.memberUsageIds()).contains(1, 3));
  }

  @Test
  void missingYearStillMatches() {
    NameUsage accepted = usage(1, Status.ACCEPTED, "Poa", "annua", null, "L.", "1753", null, null);
    NameUsage recomb = usage(2, Status.SYNONYM, "Ochlopoa", "annua", null, "H.Scholz", null, "L.", null);
    HomotypyProposal p = detector.detect(accepted, List.of(recomb), Set.of());
    assertThat(p.groups()).anySatisfy(g -> assertThat(g.memberUsageIds()).contains(1, 2));
  }

  @Test
  void existingRelationIsFlagged() {
    NameUsage accepted = usage(1, Status.ACCEPTED, "Poa", "annua", null, "L.", "1753", null, null);
    NameUsage recomb = usage(2, Status.SYNONYM, "Ochlopoa", "annua", null, "H.Scholz", null, "L.", null);
    HomotypyProposal p = detector.detect(accepted, List.of(recomb), Set.of("2:1:basionym"));
    assertThat(p.groups())
        .flatExtracting(g -> g.relations())
        .allSatisfy(r -> assertThat(r.alreadyExists()).isTrue());
  }

  @Test
  void multipleRecombinationsAllGroupTogether() {
    // Poa annua L. (accepted basionym) + two independent recombinations, both authored by L.,
    // both without a year of their own (null year is compatible with the anchor's 1753).
    NameUsage accepted = usage(1, Status.ACCEPTED, "Poa", "annua", null, "L.", "1753", null, null);
    NameUsage recomb1 = usage(2, Status.SYNONYM, "Ochlopoa", "annua", null, "H.Scholz", null, "L.", null);
    NameUsage recomb2 = usage(3, Status.SYNONYM, "Ochlopoa", "annua", null, "Tzvelev", null, "L.", null);

    HomotypyProposal p = detector.detect(accepted, List.of(recomb1, recomb2), Set.of());

    assertThat(p.groups()).anySatisfy(g -> {
      assertThat(g.basionymUsageId()).isEqualTo(1);
      assertThat(g.memberUsageIds()).containsExactlyInAnyOrder(1, 2, 3);
      assertThat(g.relations()).anySatisfy(r -> {
        assertThat(r.usageId()).isEqualTo(2);
        assertThat(r.relatedUsageId()).isEqualTo(1);
        assertThat(r.type()).isEqualTo("basionym");
      });
      assertThat(g.relations()).anySatisfy(r -> {
        assertThat(r.usageId()).isEqualTo(3);
        assertThat(r.relatedUsageId()).isEqualTo(1);
        assertThat(r.type()).isEqualTo("basionym");
      });
    });
  }

  @Test
  void misappliedIsExcluded() {
    NameUsage accepted = usage(1, Status.ACCEPTED, "Poa", "annua", null, "L.", "1753", null, null);
    NameUsage misapplied = usage(9, Status.MISAPPLIED, "Poa", "annua", null, "auct.", null, null, null);
    HomotypyProposal p = detector.detect(accepted, List.of(misapplied), Set.of());
    assertThat(p.groups()).noneSatisfy(g -> assertThat(g.memberUsageIds()).contains(9));
  }

  @Test
  void groupClustersFlatListLikeDetect() {
    // group() over [accepted, recomb] gives the same single-group result as detect()
    NameUsage accepted = usage(1, Status.ACCEPTED, "Poa", "annua", null, "L.", "1753", null, null);
    NameUsage recomb = usage(2, Status.SYNONYM, "Ochlopoa", "annua", null, "H.Scholz", null, "L.", null);
    HomotypyProposal viaGroup = detector.group(java.util.List.of(accepted, recomb), java.util.Set.of());
    assertThat(viaGroup.groups()).anySatisfy(g -> {
      assertThat(g.basionymUsageId()).isEqualTo(1);
      assertThat(g.memberUsageIds()).containsExactlyInAnyOrder(1, 2);
      assertThat(g.relations()).anySatisfy(r -> {
        assertThat(r.usageId()).isEqualTo(2);
        assertThat(r.relatedUsageId()).isEqualTo(1);
        assertThat(r.type()).isEqualTo("basionym");
      });
    });
  }
}
