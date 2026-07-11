package org.catalogueoflife.editor.clb;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import life.catalogue.api.model.CSLType;
import life.catalogue.api.model.CslData;
import life.catalogue.api.model.CslDate;
import life.catalogue.api.model.CslName;
import life.catalogue.api.model.Distribution;
import life.catalogue.api.model.Media;
import life.catalogue.api.model.Name;
import life.catalogue.api.model.NameUsageRelation;
import life.catalogue.api.model.SpeciesEstimate;
import life.catalogue.api.model.Synonym;
import life.catalogue.api.model.Synonymy;
import life.catalogue.api.model.Taxon;
import life.catalogue.api.model.TaxonProperty;
import life.catalogue.api.model.TypeMaterial;
import life.catalogue.api.model.UsageInfo;
import life.catalogue.api.model.VernacularName;
import life.catalogue.api.vocab.EstablishmentMeans;
import life.catalogue.api.vocab.EstimateType;
import life.catalogue.api.vocab.Environment;
import life.catalogue.api.vocab.Gender;
import life.catalogue.api.vocab.License;
import life.catalogue.api.vocab.MediaType;
import life.catalogue.api.vocab.NomRelType;
import life.catalogue.api.vocab.NomStatus;
import life.catalogue.api.vocab.Sex;
import life.catalogue.api.vocab.TaxonomicStatus;
import life.catalogue.api.vocab.ThreatStatus;
import life.catalogue.api.vocab.TypeStatus;
import life.catalogue.api.vocab.area.Country;
import life.catalogue.api.vocab.area.Gazetteer;
import life.catalogue.api.vocab.area.GenericArea;
import org.catalogueoflife.editor.clb.ClbUsageMapper.MappedImport;
import org.catalogueoflife.editor.clb.ClbUsageMapper.MappedUsage;
import org.catalogueoflife.editor.name.Reference;
import org.catalogueoflife.editor.name.Status;
import org.gbif.nameparser.api.Rank;
import org.junit.jupiter.api.Test;

// Pure unit tests for ClbUsageMapper (no Spring/DB) -- every fixture is built directly from the CLB
// api model's own constructors/setters, exactly as a real UsageInfo (deserialized by
// ClbImportClient.usageInfo) would be shaped.
class ClbUsageMapperTest {

  private static Name name(String id, String sciName, String authorship, Rank rank) {
    Name n = new Name();
    n.setId(id);
    n.setScientificName(sciName);
    n.setAuthorship(authorship);
    n.setRank(rank);
    return n;
  }

  @Test
  void mapsAtomizedNameAndTaxonFields() {
    Name n = name("N1", "Panthera leo", "(Linnaeus, 1758)", Rank.SPECIES);
    n.setGenus("Panthera");
    n.setSpecificEpithet("leo");
    n.setGender(Gender.MASCULINE);
    n.setNomStatus(NomStatus.ESTABLISHED);
    n.setEtymology("from Greek");
    n.setPublishedInYear(1758);
    n.setPublishedInPage("41");
    n.setPublishedInPageLink("https://example.org/p41");
    n.setPublishedInId("ref-1");

    Taxon t = new Taxon(n);
    t.setId("T1");
    t.setStatus(TaxonomicStatus.ACCEPTED);
    t.setRemarks("taxon remark");
    t.setExtinct(false);
    t.setEnvironments(Set.of(Environment.MARINE, Environment.TERRESTRIAL));
    t.setTemporalRangeStart("Holocene");
    t.setReferenceIds(List.of("ref-2", "ref-3"));

    UsageInfo info = new UsageInfo(t);

    MappedUsage mapped = ClbUsageMapper.toNameUsage(info);
    assertThat(mapped.usage().getScientificName()).isEqualTo("Panthera leo");
    assertThat(mapped.usage().getAuthorship()).isEqualTo("(Linnaeus, 1758)");
    assertThat(mapped.usage().getRank()).isEqualTo("species");
    assertThat(mapped.usage().getGenus()).isEqualTo("Panthera");
    assertThat(mapped.usage().getSpecificEpithet()).isEqualTo("leo");
    assertThat(mapped.usage().getStatus()).isEqualTo(Status.ACCEPTED);
    assertThat(mapped.usage().getGender()).isEqualTo(Gender.MASCULINE);
    assertThat(mapped.usage().getNomStatus()).isEqualTo(NomStatus.ESTABLISHED);
    assertThat(mapped.usage().getEtymology()).isEqualTo("from Greek");
    assertThat(mapped.usage().getPublishedInYear()).isEqualTo(1758);
    assertThat(mapped.usage().getPublishedInPage()).isEqualTo("41");
    assertThat(mapped.usage().getPublishedInPageLink()).isEqualTo("https://example.org/p41");
    assertThat(mapped.usage().getRemarks()).isEqualTo("taxon remark");
    assertThat(mapped.usage().getExtinct()).isFalse();
    assertThat(mapped.usage().getEnvironment()).containsExactlyInAnyOrder(Environment.MARINE, Environment.TERRESTRIAL);
    assertThat(mapped.usage().getTemporalRangeStart()).isEqualTo("Holocene");
    // CLB-id passthrough Task 2 needs to remap referenceIds/publishedInReferenceId once it has
    // inserted the mapped references and knows their new ids.
    assertThat(mapped.clbUsageId()).isEqualTo("T1");
    assertThat(mapped.clbNameId()).isEqualTo("N1");
    assertThat(mapped.clbPublishedInReferenceId()).isEqualTo("ref-1");
    assertThat(mapped.clbReferenceIds()).containsExactly("ref-2", "ref-3");
  }

  @Test
  void rankKeepsUnderscoreUnlikeOtherEnums() {
    // Our own model's `rank` string keeps the raw enum name (lower-cased, underscore intact) -- see
    // NameParserService/ParsedNameMapping's identical convention -- unlike every other vocab field
    // below, which uses ColDP's space-separated convention.
    Taxon t = new Taxon(name("N1", "Foo", null, Rank.SECTION_BOTANY));
    t.setId("T1");
    t.setStatus(TaxonomicStatus.ACCEPTED);
    MappedUsage mapped = ClbUsageMapper.toNameUsage(new UsageInfo(t));
    assertThat(mapped.usage().getRank()).isEqualTo("section_botany");
  }

  @Test
  void statusInverseAcceptedAndProvisionallyAccepted() {
    assertThat(ClbUsageMapper.toStatus(TaxonomicStatus.ACCEPTED)).isEqualTo(Status.ACCEPTED);
    assertThat(ClbUsageMapper.toStatus(TaxonomicStatus.PROVISIONALLY_ACCEPTED)).isEqualTo(Status.UNASSESSED);
    assertThat(ClbUsageMapper.toStatus(TaxonomicStatus.SYNONYM)).isEqualTo(Status.SYNONYM);
    assertThat(ClbUsageMapper.toStatus(TaxonomicStatus.AMBIGUOUS_SYNONYM)).isEqualTo(Status.SYNONYM);
    assertThat(ClbUsageMapper.toStatus(TaxonomicStatus.MISAPPLIED)).isEqualTo(Status.MISAPPLIED);
  }

  @Test
  void flattensSynonymsHomotypicHeterotypicAndMisapplied() {
    Taxon t = new Taxon(name("N1", "Panthera leo", null, Rank.SPECIES));
    t.setId("T1");
    t.setStatus(TaxonomicStatus.ACCEPTED);
    UsageInfo info = new UsageInfo(t);

    Synonym homotypic = new Synonym(name("N2", "Felis leo", "Linnaeus, 1758", Rank.SPECIES));
    homotypic.setId("S1");
    Synonym heterotypic = new Synonym(name("N3", "Leo leo", "Frisch, 1775", Rank.SPECIES));
    heterotypic.setId("S2");
    Synonym misapplied = new Synonym(name("N4", "Panthera leo", "auct. non Linnaeus", Rank.SPECIES));
    misapplied.setId("S3");
    misapplied.setStatus(TaxonomicStatus.MISAPPLIED);

    info.setSynonyms(new Synonymy());
    info.getSynonyms().getHomotypic().add(homotypic);
    info.getSynonyms().getHeterotypic().add(heterotypic);
    info.getSynonyms().getMisapplied().add(misapplied);
    // heterotypicGroups is the SAME heterotypic synonyms re-presented grouped by name -- must NOT be
    // double-counted by toSynonyms, which only walks homotypic+heterotypic+misapplied.
    info.getSynonyms().getHeterotypicGroups().add(List.of(heterotypic));

    List<MappedUsage> synonyms = ClbUsageMapper.toSynonyms(info);
    assertThat(synonyms).hasSize(3);
    assertThat(synonyms).filteredOn(s -> s.clbUsageId().equals("S1")).singleElement()
        .satisfies(s -> assertThat(s.usage().getStatus()).isEqualTo(Status.SYNONYM));
    assertThat(synonyms).filteredOn(s -> s.clbUsageId().equals("S2")).singleElement()
        .satisfies(s -> assertThat(s.usage().getStatus()).isEqualTo(Status.SYNONYM));
    assertThat(synonyms).filteredOn(s -> s.clbUsageId().equals("S3")).singleElement()
        .satisfies(s -> assertThat(s.usage().getStatus()).isEqualTo(Status.MISAPPLIED));
  }

  @Test
  void mapsDistributionWithSpaceSeparatedEnumsAndIso2Country() {
    Distribution d = new Distribution();
    d.setArea(new GenericArea(Gazetteer.ISO, "DE", "Germany"));
    d.setEstablishmentMeans(EstablishmentMeans.NATIVE);
    // CRITICALLY_ENDANGERED specifically exercises the underscore -> space convention (NOT a plain
    // toLowerCase(), which would leave "critically_endangered").
    d.setThreatStatus(ThreatStatus.CRITICALLY_ENDANGERED);
    d.setReferenceId("ref-4");
    d.setRemarks("dist remark");

    var mapped = ClbUsageMapper.toDistributionRequest(d);
    assertThat(mapped.request().area()).isEqualTo("Germany");
    assertThat(mapped.request().areaId()).isEqualTo("DE");
    assertThat(mapped.request().gazetteer()).isEqualTo("iso");
    assertThat(mapped.request().establishmentMeans()).isEqualTo("native");
    assertThat(mapped.request().threatStatus()).isEqualTo("critically endangered");
    assertThat(mapped.request().referenceId()).isNull();
    assertThat(mapped.request().remarks()).isEqualTo("dist remark");
    assertThat(mapped.clbReferenceId()).isEqualTo("ref-4");
  }

  @Test
  void mapsVernacular() {
    VernacularName vn = new VernacularName();
    vn.setName("Lion");
    vn.setLanguage("eng");
    vn.setCountry(Country.GERMANY);
    vn.setSex(Sex.FEMALE);
    vn.setPreferred(true);
    vn.setReferenceId("ref-5");
    vn.setRemarks("vern remark");

    var mapped = ClbUsageMapper.toVernacularRequest(vn);
    assertThat(mapped.request().name()).isEqualTo("Lion");
    assertThat(mapped.request().language()).isEqualTo("eng");
    assertThat(mapped.request().country()).isEqualTo("DE");
    assertThat(mapped.request().sex()).isEqualTo("female");
    assertThat(mapped.request().preferred()).isTrue();
    assertThat(mapped.clbReferenceId()).isEqualTo("ref-5");
  }

  @Test
  void mapsMedia() {
    Media m = new Media();
    m.setUrl(URI.create("https://example.org/lion.jpg"));
    m.setType(MediaType.IMAGE);
    m.setTitle("A lion");
    m.setCapturedBy("Jane Photographer");
    m.setLicense(License.CC_BY);
    m.setLink(URI.create("https://example.org/lion"));
    m.setRemarks("media remark");

    var mapped = ClbUsageMapper.toMediaRequest(m);
    assertThat(mapped.url()).isEqualTo("https://example.org/lion.jpg");
    assertThat(mapped.type()).isEqualTo("image");
    assertThat(mapped.title()).isEqualTo("A lion");
    assertThat(mapped.creator()).isEqualTo("Jane Photographer");
    assertThat(mapped.license()).isEqualTo("cc by");
    assertThat(mapped.link()).isEqualTo("https://example.org/lion");
  }

  @Test
  void mapsEstimate() {
    SpeciesEstimate e = new SpeciesEstimate();
    e.setEstimate(5);
    e.setType(EstimateType.SPECIES_LIVING);
    e.setReferenceId("ref-7");
    e.setRemarks("estimate remark");

    var mapped = ClbUsageMapper.toEstimateRequest(e);
    assertThat(mapped.request().estimate()).isEqualTo(5);
    assertThat(mapped.request().type()).isEqualTo("species living");
    assertThat(mapped.clbReferenceId()).isEqualTo("ref-7");
  }

  @Test
  void mapsProperty() {
    TaxonProperty tp = new TaxonProperty();
    tp.setProperty("IUCN status");
    tp.setValue("VU");
    tp.setPage("12");
    tp.setReferenceId("ref-6");
    tp.setRemarks("prop remark");

    var mapped = ClbUsageMapper.toPropertyRequest(tp);
    assertThat(mapped.request().property()).isEqualTo("IUCN status");
    assertThat(mapped.request().value()).isEqualTo("VU");
    assertThat(mapped.request().page()).isEqualTo("12");
    assertThat(mapped.clbReferenceId()).isEqualTo("ref-6");
  }

  @Test
  void mapsTypeMaterialIncludingUnparseableCoordinates() {
    TypeMaterial tm = new TypeMaterial();
    tm.setNameId("N1");
    tm.setCitation("citation text");
    tm.setStatus(TypeStatus.HOLOTYPE);
    tm.setInstitutionCode("NHM");
    tm.setCatalogNumber("12345");
    tm.setCountry(Country.GERMANY);
    tm.setLocality("Berlin");
    tm.setCollector("A. Collector");
    tm.setDate("1900-01-01");
    tm.setSex(Sex.MALE);
    tm.setReferenceId("ref-8");
    tm.setLink(URI.create("https://example.org/type"));
    tm.setRemarks("type remark");
    tm.setLatitude("52.5");
    tm.setLongitude("not-a-number");

    var mapped = ClbUsageMapper.toTypeMaterialRequest(tm);
    assertThat(mapped.request().citation()).isEqualTo("citation text");
    assertThat(mapped.request().status()).isEqualTo("holotype");
    assertThat(mapped.request().institutionCode()).isEqualTo("NHM");
    assertThat(mapped.request().catalogNumber()).isEqualTo("12345");
    assertThat(mapped.request().country()).isEqualTo("DE");
    assertThat(mapped.request().sex()).isEqualTo("male");
    assertThat(mapped.request().occurrenceId()).isNull();
    assertThat(mapped.request().latitude()).isEqualTo(52.5);
    assertThat(mapped.request().longitude()).isNull(); // unparseable -> null, not an exception
    assertThat(mapped.clbReferenceId()).isEqualTo("ref-8");
  }

  @Test
  void mapsNameRelationUsingResolvedUsageIds() {
    NameUsageRelation rel = new NameUsageRelation();
    rel.setType(NomRelType.BASIONYM);
    rel.setUsageId("T1");
    rel.setRelatedUsageId("T-basionym");
    rel.setReferenceId("ref-9");
    rel.setRemarks("rel remark");

    var mapped = ClbUsageMapper.toNameRelationRequest(rel);
    assertThat(mapped.request().type()).isEqualTo("basionym");
    assertThat(mapped.request().relatedUsageId()).isNull(); // CLB id, remapped by Task 2
    assertThat(mapped.clbUsageId()).isEqualTo("T1");
    assertThat(mapped.clbRelatedUsageId()).isEqualTo("T-basionym");
    assertThat(mapped.clbReferenceId()).isEqualTo("ref-9");
  }

  @Test
  void mapsReferenceWithFullCslData() {
    life.catalogue.api.model.Reference clbRef = new life.catalogue.api.model.Reference();
    clbRef.setId("ref-1");
    clbRef.setCitation("Linnaeus 1758");
    clbRef.setRemarks("ref remark");
    CslData csl = new CslData();
    csl.setType(CSLType.ARTICLE_JOURNAL);
    csl.setAuthor(new CslName[] {new CslName("Carl", "Linnaeus")});
    csl.setEditor(new CslName[] {new CslName("Some", "Editor")});
    csl.setTitle("Systema Naturae");
    csl.setContainerTitle("Syst. Nat.");
    csl.setIssued(new CslDate(1758, 1, 1));
    csl.setVolume("1");
    csl.setIssue("10");
    csl.setPage("41");
    csl.setPublisher("Laurentius Salvius");
    csl.setDOI("10.1/xyz");
    csl.setISBN("978-1234567890");
    csl.setISSN("1234-5678");
    csl.setURL("https://example.org/systema");
    csl.setAccessed(new CslDate("2026"));
    clbRef.setCsl(csl);

    Reference r = ClbUsageMapper.toReference(clbRef);
    assertThat(r.getCitation()).isEqualTo("Linnaeus 1758");
    assertThat(r.getRemarks()).isEqualTo("ref remark");
    assertThat(r.getType()).isEqualTo("article-journal");
    assertThat(r.getAuthor()).isEqualTo("Linnaeus,Carl");
    assertThat(r.getEditor()).isEqualTo("Editor,Some");
    assertThat(r.getTitle()).isEqualTo("Systema Naturae");
    assertThat(r.getContainerTitle()).isEqualTo("Syst. Nat.");
    assertThat(r.getIssued()).isEqualTo("1758-01-01");
    assertThat(r.getVolume()).isEqualTo("1");
    assertThat(r.getIssue()).isEqualTo("10");
    assertThat(r.getPage()).isEqualTo("41");
    assertThat(r.getPublisher()).isEqualTo("Laurentius Salvius");
    assertThat(r.getDoi()).isEqualTo("10.1/xyz");
    assertThat(r.getIsbn()).isEqualTo("978-1234567890");
    assertThat(r.getIssn()).isEqualTo("1234-5678");
    assertThat(r.getLink()).isEqualTo("https://example.org/systema");
    assertThat(r.getAccessed()).isEqualTo("2026");
  }

  @Test
  void mapsReferenceWithoutCslFallsBackToCitationOnly() {
    life.catalogue.api.model.Reference clbRef = new life.catalogue.api.model.Reference();
    clbRef.setId("ref-x");
    clbRef.setCitation("just a citation string");

    Reference r = ClbUsageMapper.toReference(clbRef);
    assertThat(r.getCitation()).isEqualTo("just a citation string");
    assertThat(r.getType()).isNull();
    assertThat(r.getAuthor()).isNull();
  }

  @Test
  void toCreateRequestAssemblesTheWholeBundle() {
    Name n = name("N1", "Panthera leo", "(Linnaeus, 1758)", Rank.SPECIES);
    n.setPublishedInId("ref-1");
    Taxon t = new Taxon(n);
    t.setId("T1");
    t.setStatus(TaxonomicStatus.ACCEPTED);
    t.setReferenceIds(List.of("ref-2"));
    UsageInfo info = new UsageInfo(t);

    Synonym syn = new Synonym(name("N2", "Felis leo", null, Rank.SPECIES));
    syn.setId("S1");
    info.setSynonyms(new Synonymy());
    info.getSynonyms().getHomotypic().add(syn);

    Distribution d = new Distribution();
    d.setArea(new GenericArea("free text area"));
    info.setDistributions(List.of(d));

    VernacularName vn = new VernacularName();
    vn.setName("Lion");
    info.setVernacularNames(List.of(vn));

    Media m = new Media();
    m.setTitle("photo");
    info.setMedia(List.of(m));

    SpeciesEstimate est = new SpeciesEstimate();
    est.setEstimate(1);
    info.setEstimates(List.of(est));

    TaxonProperty tp = new TaxonProperty();
    tp.setProperty("p");
    tp.setValue("v");
    info.setProperties(List.of(tp));

    TypeMaterial tm = new TypeMaterial();
    tm.setNameId("N1");
    tm.setCitation("type citation");
    info.setTypeMaterial(Map.of("N1", List.of(tm)));

    NameUsageRelation rel = new NameUsageRelation();
    rel.setType(NomRelType.BASIONYM);
    rel.setUsageId("T1");
    rel.setRelatedUsageId("T-other");
    info.setNameRelations(List.of(rel));

    life.catalogue.api.model.Reference ref1 = new life.catalogue.api.model.Reference();
    ref1.setId("ref-1");
    ref1.setCitation("published in ref");
    life.catalogue.api.model.Reference ref2 = new life.catalogue.api.model.Reference();
    ref2.setId("ref-2");
    ref2.setCitation("taxonomic ref");
    info.setReferences(Map.of("ref-1", ref1, "ref-2", ref2));

    MappedImport bundle = ClbUsageMapper.toCreateRequest(info);
    assertThat(bundle.usage().usage().getScientificName()).isEqualTo("Panthera leo");
    assertThat(bundle.synonyms()).hasSize(1);
    assertThat(bundle.distributions()).hasSize(1);
    assertThat(bundle.vernaculars()).hasSize(1);
    assertThat(bundle.media()).hasSize(1);
    assertThat(bundle.estimates()).hasSize(1);
    assertThat(bundle.properties()).hasSize(1);
    assertThat(bundle.typeMaterialByNameId()).containsOnlyKeys("N1");
    assertThat(bundle.typeMaterialByNameId().get("N1")).hasSize(1);
    assertThat(bundle.nameRelations()).hasSize(1);
    assertThat(bundle.references()).containsOnlyKeys("ref-1", "ref-2");
    assertThat(bundle.references().get("ref-1").getCitation()).isEqualTo("published in ref");
  }
}
