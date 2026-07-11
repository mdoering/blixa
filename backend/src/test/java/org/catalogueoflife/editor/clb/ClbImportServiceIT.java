package org.catalogueoflife.editor.clb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import life.catalogue.api.model.Distribution;
import life.catalogue.api.model.Name;
import life.catalogue.api.model.Synonym;
import life.catalogue.api.model.Synonymy;
import life.catalogue.api.model.Taxon;
import life.catalogue.api.model.UsageInfo;
import life.catalogue.api.model.VernacularName;
import life.catalogue.api.vocab.TaxonomicStatus;
import life.catalogue.api.vocab.area.Gazetteer;
import life.catalogue.api.vocab.area.GenericArea;
import org.catalogueoflife.editor.child.DistributionMapper;
import org.catalogueoflife.editor.child.VernacularMapper;
import org.catalogueoflife.editor.child.dto.DistributionResponse;
import org.catalogueoflife.editor.child.dto.VernacularResponse;
import org.catalogueoflife.editor.clb.dto.ClbImportRequest;
import org.catalogueoflife.editor.clb.dto.ClbImportSummary;
import org.catalogueoflife.editor.name.IdSeqMapper;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.name.NameUsageMapper;
import org.catalogueoflife.editor.name.ReferenceMapper;
import org.catalogueoflife.editor.name.Status;
import org.catalogueoflife.editor.name.SynonymAcceptedMapper;
import org.catalogueoflife.editor.project.Project;
import org.catalogueoflife.editor.project.ProjectService;
import org.catalogueoflife.editor.project.dto.CreateProjectRequest;
import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserMapper;
import org.catalogueoflife.editor.validation.IssueMapper;
import org.gbif.nameparser.api.Rank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

// ClbImportService.importFromClb exercised at the service/mapper level (like ColMatchJobIT) -- no
// MockMvc/HTTP needed, ClbImportController is a thin pass-through. The external CLB call
// (ClbImportClient) is entirely mocked; every fixture UsageInfo is built directly from the CLB api
// model's own constructors, exactly like ClbUsageMapperTest.
class ClbImportServiceIT extends AbstractPostgresIT {

  private static final String NAME_USAGE_ENTITY = "name_usage";

  @Autowired ProjectService projectService;
  @Autowired AppUserMapper users;
  @Autowired NameUsageMapper usages;
  @Autowired SynonymAcceptedMapper synonymAccepted;
  @Autowired ReferenceMapper references;
  @Autowired DistributionMapper distributions;
  @Autowired VernacularMapper vernaculars;
  @Autowired IdSeqMapper idSeq;
  @Autowired IssueMapper issueMapper;
  @Autowired ClbImportService service;

  @MockitoBean ClbImportClient clb;

  private int createUser(String username) {
    AppUser u = new AppUser();
    u.setUsername(username);
    users.insert(u);
    return u.getId();
  }

  private int createProject(int userId, String title) {
    Project p = projectService.create(userId, new CreateProjectRequest(title, "zoological"));
    return p.getId();
  }

  private int createFocalUsage(int projectId, int userId) {
    NameUsage u = new NameUsage();
    u.setProjectId(projectId);
    u.setId(idSeq.allocate(projectId, NAME_USAGE_ENTITY));
    u.setStatus(Status.ACCEPTED);
    u.setScientificName("Root");
    u.setRank("family");
    u.setModifiedBy(userId);
    usages.insert(u);
    return u.getId();
  }

  private NameUsage findByName(int projectId, String scientificName) {
    return usages.findAllByProject(projectId).stream()
        .filter(u -> u.getScientificName().equals(scientificName))
        .findFirst().orElseThrow(() -> new AssertionError("not found: " + scientificName));
  }

  private static Name name(String id, String sciName, String authorship, Rank rank) {
    Name n = new Name();
    n.setId(id);
    n.setScientificName(sciName);
    n.setAuthorship(authorship);
    n.setRank(rank);
    return n;
  }

  private static UsageInfo genusInfo(String id, String sciName) {
    Taxon t = new Taxon(name(id + "-N", sciName, null, Rank.GENUS));
    t.setId(id);
    t.setStatus(TaxonomicStatus.ACCEPTED);
    return new UsageInfo(t);
  }

  // A species usage carrying exactly one synonym + one distribution + the reference the
  // distribution cites -- the fixture shape the design brief's own test description calls for.
  private static UsageInfo speciesInfo(String id, String sciName, TaxonomicStatus status) {
    Taxon t = new Taxon(name(id + "-N", sciName, "L.", Rank.SPECIES));
    t.setId(id);
    t.setStatus(status);
    UsageInfo info = new UsageInfo(t);

    Synonym syn = new Synonym(name(id + "-SYN-N", sciName + " somenymus", null, Rank.SPECIES));
    syn.setId(id + "-SYN");
    info.setSynonyms(new Synonymy());
    info.getSynonyms().getHomotypic().add(syn);

    Distribution d = new Distribution();
    d.setArea(new GenericArea(Gazetteer.ISO, "DE", "Germany"));
    d.setReferenceId(id + "-REF");
    info.setDistributions(List.of(d));

    life.catalogue.api.model.Reference ref = new life.catalogue.api.model.Reference();
    ref.setId(id + "-REF");
    ref.setCitation("citation for " + id);
    info.setReferences(Map.of(id + "-REF", ref));

    return info;
  }

  @Test
  void taxonSubtreeInsertsGenusAndSpeciesUnderFocalWithColProvenance() {
    int userId = createUser("clb-import-a");
    int pid = createProject(userId, "clb-import-a-project");
    int focalId = createFocalUsage(pid, userId);

    // 3LXR is the configured default COL dataset (coldp.col.match-dataset) -- provenance CURIEs
    // for this import must therefore use the "col:" scope, not the raw dataset key.
    String ds = "3LXR";
    when(clb.childrenIds(ds, "GEN")).thenReturn(List.of("SP1", "SP2"));
    when(clb.childrenIds(ds, "SP1")).thenReturn(List.of());
    when(clb.childrenIds(ds, "SP2")).thenReturn(List.of());
    when(clb.usageInfo(ds, "GEN")).thenReturn(genusInfo("GEN", "Testgenus"));
    when(clb.usageInfo(ds, "SP1")).thenReturn(speciesInfo("SP1", "Testgenus unus", TaxonomicStatus.ACCEPTED));
    when(clb.usageInfo(ds, "SP2")).thenReturn(speciesInfo("SP2", "Testgenus duo", TaxonomicStatus.ACCEPTED));

    ClbImportSummary summary = service.importFromClb(userId, pid, focalId,
        new ClbImportRequest(ds, "GEN", ImportMode.TAXON_SUBTREE, null));

    assertThat(summary.nameUsages()).isEqualTo(3);
    assertThat(summary.synonyms()).isEqualTo(2);
    assertThat(summary.references()).isEqualTo(2);
    assertThat(summary.children().get("distribution")).isEqualTo(2);

    NameUsage genus = findByName(pid, "Testgenus");
    assertThat(genus.getParentId()).isEqualTo(focalId);
    assertThat(genus.getAlternativeId()).containsExactly("col:GEN");

    NameUsage sp1 = findByName(pid, "Testgenus unus");
    assertThat(sp1.getParentId()).isEqualTo(genus.getId());
    assertThat(sp1.getAlternativeId()).containsExactly("col:SP1");

    NameUsage sp2 = findByName(pid, "Testgenus duo");
    assertThat(sp2.getParentId()).isEqualTo(genus.getId());

    NameUsage syn1 = findByName(pid, "Testgenus unus somenymus");
    assertThat(synonymAccepted.findAcceptedFor(pid, syn1.getId())).containsExactly(sp1.getId());

    List<DistributionResponse> sp1Dist = distributions.findByUsage(pid, sp1.getId());
    assertThat(sp1Dist).hasSize(1);
    assertThat(sp1Dist.get(0).area()).isEqualTo("Germany");
    assertThat(sp1Dist.get(0).referenceId()).isNotNull();

    // Post-import validation ran (Fix 1): ClbImportService writes through the raw mappers, which
    // carry no per-row ValidationEvent, so without a best-effort revalidateTouched pass at the end
    // of importFromClb these usages would never show up in the Issues panel until an unrelated
    // edit happened to trigger a revalidate. None of the fixtures set a publishedInReferenceId, so
    // MissingPublishedInRule ("missing_published_in") fires for every one of them once revalidated
    // -- for a newly-inserted usage (sp1)...
    assertThat(issueMapper.findByEntity(pid, "name_usage", sp1.getId()))
        .anyMatch(i -> "missing_published_in".equals(i.getRule()));
    // ...and for the PRE-EXISTING focal usage too, proving the focal id (not just the newly
    // inserted ones) is part of the revalidated `touched` set.
    assertThat(issueMapper.findByEntity(pid, "name_usage", focalId))
        .anyMatch(i -> "missing_published_in".equals(i.getRule()));
  }

  @Test
  void childrenOnlyInsertsSpeciesButNotGenusUsingRawDatasetKeyAsScope() {
    int userId = createUser("clb-import-b");
    int pid = createProject(userId, "clb-import-b-project");
    int focalId = createFocalUsage(pid, userId);

    // A non-COL dataset key: provenance must fall back to the raw key itself, not "col:".
    String ds = "9XYZ";
    when(clb.childrenIds(ds, "GEN")).thenReturn(List.of("SP1", "SP2"));
    when(clb.childrenIds(ds, "SP1")).thenReturn(List.of());
    when(clb.childrenIds(ds, "SP2")).thenReturn(List.of());
    when(clb.usageInfo(ds, "SP1")).thenReturn(speciesInfo("SP1", "Otherus unus", TaxonomicStatus.ACCEPTED));
    when(clb.usageInfo(ds, "SP2")).thenReturn(speciesInfo("SP2", "Otherus duo", TaxonomicStatus.ACCEPTED));

    ClbImportSummary summary = service.importFromClb(userId, pid, focalId,
        new ClbImportRequest(ds, "GEN", ImportMode.CHILDREN_ONLY, null));

    assertThat(summary.nameUsages()).isEqualTo(2);
    // the genus source itself is never fetched/inserted in CHILDREN_ONLY mode.
    verify(clb, never()).usageInfo(ds, "GEN");

    assertThat(usages.findAllByProject(pid).stream().anyMatch(u -> u.getScientificName().equals("Testgenus")))
        .isFalse();

    NameUsage sp1 = findByName(pid, "Otherus unus");
    assertThat(sp1.getParentId()).isEqualTo(focalId);
    // mergeScopedId lower-cases the scope prefix (see NameUsageService.mergeScopedId /
    // ColMatchJobService's identical convention) -- the raw dataset key's casing is preserved
    // nowhere else, only the id portion of the CURIE keeps its original CLB casing.
    assertThat(sp1.getAlternativeId()).containsExactly("9xyz:SP1");
    NameUsage sp2 = findByName(pid, "Otherus duo");
    assertThat(sp2.getParentId()).isEqualTo(focalId);
  }

  @Test
  void updateFocalAttachesOnlySelectedEntityTypesToTheFocalUsage() {
    int userId = createUser("clb-import-c");
    int pid = createProject(userId, "clb-import-c-project");
    int focalId = createFocalUsage(pid, userId);

    String ds = "3LXR";
    UsageInfo src = speciesInfo("SRC", "Focalis testis", TaxonomicStatus.ACCEPTED);
    VernacularName vn = new VernacularName();
    vn.setName("Should Not Appear");
    vn.setLanguage("eng");
    src.setVernacularNames(List.of(vn));
    when(clb.usageInfo(ds, "SRC")).thenReturn(src);

    ClbImportSummary summary = service.importFromClb(userId, pid, focalId,
        new ClbImportRequest(ds, "SRC", ImportMode.UPDATE_FOCAL, Set.of("synonyms", "distribution")));

    // no new accepted usage is created in mode C.
    assertThat(summary.nameUsages()).isEqualTo(0);
    assertThat(summary.synonyms()).isEqualTo(1);
    assertThat(summary.children().get("distribution")).isEqualTo(1);
    assertThat(summary.children().get("vernacular")).isEqualTo(0);

    List<Integer> focalSynonyms = usages.findAllByProject(pid).stream()
        .filter(u -> u.getStatus() != Status.ACCEPTED)
        .map(NameUsage::getId)
        .toList();
    assertThat(focalSynonyms).hasSize(1);
    assertThat(synonymAccepted.findAcceptedFor(pid, focalSynonyms.get(0))).containsExactly(focalId);

    assertThat(distributions.findByUsage(pid, focalId)).hasSize(1);
    // vernacular was NOT in entityTypes -> not attached, despite being present on the source.
    List<VernacularResponse> vernacularsOnFocal = vernaculars.findByUsage(pid, focalId);
    assertThat(vernacularsOnFocal).isEmpty();
  }

  @Test
  void subtreeLargerThanCapIsRejectedBeforeFetchingAnyUsageInfo() {
    int userId = createUser("clb-import-cap");
    int pid = createProject(userId, "clb-import-cap-project");
    int focalId = createFocalUsage(pid, userId);

    String ds = "3LXR";
    lenient().when(clb.childrenIds(anyString(), anyString())).thenReturn(List.of());
    List<String> tooMany = IntStream.range(0, 501).mapToObj(i -> "C" + i).toList();
    when(clb.childrenIds(ds, "BIG")).thenReturn(tooMany);

    assertThatThrownBy(() -> service.importFromClb(userId, pid, focalId,
        new ClbImportRequest(ds, "BIG", ImportMode.CHILDREN_ONLY, null)))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST))
        .hasMessageContaining("too large");

    verify(clb, never()).usageInfo(anyString(), anyString());
  }

  @Test
  void diamondReachableChildIsGatheredAndInsertedOnlyOnce() {
    int userId = createUser("clb-import-cycle");
    int pid = createProject(userId, "clb-import-cycle-project");
    int focalId = createFocalUsage(pid, userId);

    // A malformed/cyclic-ish CLB response: GEN -> [SP1, SP2], and BOTH SP1 and SP2 list the SAME
    // child DUP. Without gather()'s `visited` guard (Fix 3), DUP would be enqueued -- and
    // therefore inserted -- twice, only ever caught (if at all) by the unrelated size cap.
    String ds = "3LXR";
    when(clb.childrenIds(ds, "GEN")).thenReturn(List.of("SP1", "SP2"));
    when(clb.childrenIds(ds, "SP1")).thenReturn(List.of("DUP"));
    when(clb.childrenIds(ds, "SP2")).thenReturn(List.of("DUP"));
    when(clb.childrenIds(ds, "DUP")).thenReturn(List.of());
    when(clb.usageInfo(ds, "GEN")).thenReturn(genusInfo("GEN", "Diamondus"));
    when(clb.usageInfo(ds, "SP1")).thenReturn(genusInfo("SP1", "Diamondus sub1"));
    when(clb.usageInfo(ds, "SP2")).thenReturn(genusInfo("SP2", "Diamondus sub2"));
    when(clb.usageInfo(ds, "DUP")).thenReturn(genusInfo("DUP", "Diamondus dup"));

    ClbImportSummary summary = service.importFromClb(userId, pid, focalId,
        new ClbImportRequest(ds, "GEN", ImportMode.TAXON_SUBTREE, null));

    // GEN, SP1, SP2, DUP -- DUP counted (and inserted) only once despite being reachable via both
    // SP1 and SP2, instead of the 5 a double-insert would produce.
    assertThat(summary.nameUsages()).isEqualTo(4);
    verify(clb, times(1)).usageInfo(ds, "DUP");
    assertThat(usages.findAllByProject(pid).stream()
        .filter(u -> u.getScientificName().equals("Diamondus dup")).count()).isEqualTo(1);
  }

  @Test
  void taxonScopedChildIsSkippedForANonAcceptedUsage() {
    int userId = createUser("clb-import-guard");
    int pid = createProject(userId, "clb-import-guard-project");
    int focalId = createFocalUsage(pid, userId);

    String ds = "3LXR";
    when(clb.childrenIds(ds, "GEN")).thenReturn(List.of("SP1"));
    when(clb.childrenIds(ds, "SP1")).thenReturn(List.of());
    when(clb.usageInfo(ds, "GEN")).thenReturn(genusInfo("GEN", "Guardus"));
    // PROVISIONALLY_ACCEPTED collapses to our UNASSESSED status -- its distribution must be
    // skipped even though CLB itself still calls this a "taxon".
    when(clb.usageInfo(ds, "SP1"))
        .thenReturn(speciesInfo("SP1", "Guardus unus", TaxonomicStatus.PROVISIONALLY_ACCEPTED));

    ClbImportSummary summary = service.importFromClb(userId, pid, focalId,
        new ClbImportRequest(ds, "GEN", ImportMode.TAXON_SUBTREE, null));

    assertThat(summary.nameUsages()).isEqualTo(2);
    // the provisional species' own distribution never landed anywhere.
    assertThat(summary.children().get("distribution")).isEqualTo(0);
    assertThat(summary.issues()).anySatisfy(i -> assertThat(i.message()).contains("not accepted"));

    NameUsage sp1 = findByName(pid, "Guardus unus");
    assertThat(sp1.getStatus()).isEqualTo(Status.UNASSESSED);
    assertThat(distributions.findByUsage(pid, sp1.getId())).isEmpty();
    // the synonym itself is unaffected by the guard (only the 5 taxon-scoped kinds are gated).
    assertThat(usages.findAllByProject(pid).stream()
        .anyMatch(u -> u.getScientificName().equals("Guardus unus somenymus"))).isTrue();
  }
}
