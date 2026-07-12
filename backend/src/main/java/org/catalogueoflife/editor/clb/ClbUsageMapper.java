package org.catalogueoflife.editor.clb;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import life.catalogue.api.model.CslData;
import life.catalogue.api.model.CslDate;
import life.catalogue.api.model.Distribution;
import life.catalogue.api.model.Media;
import life.catalogue.api.model.Name;
import life.catalogue.api.model.NameUsageBase;
import life.catalogue.api.model.NameUsageRelation;
import life.catalogue.api.model.SpeciesEstimate;
import life.catalogue.api.model.Synonym;
import life.catalogue.api.model.Synonymy;
import life.catalogue.api.model.Taxon;
import life.catalogue.api.model.TaxonProperty;
import life.catalogue.api.model.TypeMaterial;
import life.catalogue.api.model.UsageInfo;
import life.catalogue.api.model.VernacularName;
import life.catalogue.api.vocab.TaxonomicStatus;
import life.catalogue.api.vocab.area.GenericArea;
import life.catalogue.common.date.FuzzyDate;
import org.catalogueoflife.editor.child.dto.DistributionRequest;
import org.catalogueoflife.editor.child.dto.EstimateRequest;
import org.catalogueoflife.editor.child.dto.MediaRequest;
import org.catalogueoflife.editor.child.dto.NameRelationRequest;
import org.catalogueoflife.editor.child.dto.PropertyRequest;
import org.catalogueoflife.editor.child.dto.TypeMaterialRequest;
import org.catalogueoflife.editor.child.dto.VernacularRequest;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.name.Reference;
import org.catalogueoflife.editor.name.Status;

// Pure mapping from the CLB api model (life.catalogue.api.model.UsageInfo, as fetched by
// ClbImportClient.usageInfo) into our own model's shapes -- no DB, no Spring, no I/O, mirroring
// RefMapping's shape (private constructor, static methods only). Task 2 (the actual insert service)
// is the only caller: it supplies whatever project-scoped ids (usage id, reference id) a mapped
// record still needs, using the CLB-id fields every "Mapped*" wrapper below carries alongside its
// Request DTO for exactly that purpose -- see each record's own javadoc.
//
// Field-for-field, this is the ColDP export/import mapping in ImportRunService/ChildColdpWriter run
// in reverse against the CLB api model instead of a ColDP archive: same target DTOs, same status
// inverse, same enum-to-ColDP-string convention (NameUsageColdpWriter.lower: name().toLowerCase()
// with '_' -> ' ', NOT plain toLowerCase() -- e.g. ThreatStatus.CRITICALLY_ENDANGERED must become
// "critically endangered", the exact string ColDP/CLB's own PermissiveEnumSerde.enumValueName uses).
// The one deliberate exception is `rank`, which -- like NameParserService/ParsedNameMapping already
// do for this app's own parsed names -- keeps its underscore (e.g. "section_botany"): see
// ClbMatchClient.CLASSIFICATION_PARAM's identically-cased keys for the same convention elsewhere in
// this codebase.
public final class ClbUsageMapper {

  private ClbUsageMapper() {}

  // --- name/usage -----------------------------------------------------------------------------

  /**
   * A mapped usage (the accepted taxon, or one synonym) paired with the raw CLB ids Task 2 needs to
   * finish the job: {@code clbUsageId}/{@code clbNameId} so TypeMaterial/NameRelation (both keyed by
   * CLB nameID, see {@link #toTypeMaterial} / {@link #toNameRelations}) can be attached to whichever
   * usage this is once Task 2 has inserted it and knows its own new id; {@code clbPublishedInReferenceId}
   * / {@code clbReferenceIds} so Task 2 can resolve them against the ids it gets back from inserting
   * {@link #toReferences}. {@code usage.getId()/getProjectId()/getParentId()/getBasionymId()} are left
   * unset -- Task 2's insert allocates the id; NameParserService.parseInto (its own safety net,
   * exactly like ColDP import -- see ImportRunService.insertPrimaryUsage) re-derives the atomized
   * name fields, so this mapper only carries the handful the brief calls out explicitly, not a full
   * breakdown of combinationAuthorship/basionymAuthorship.
   *
   * <p>{@code usage} is {@code null} when the source CLB {@code NameUsageBase} itself has no
   * {@code Name} at all (a malformed/incomplete CLB record) -- {@code clbUsageId} is still populated
   * in that case (it comes straight off the CLB usage, independent of its Name), everything else is
   * {@code null}. Task 2 checks for this and skips the record with a {@code ClbImportIssue} instead
   * of inserting it -- see {@code ClbImportService}'s own null-name guard.
   */
  public record MappedUsage(
      NameUsage usage,
      String clbUsageId,
      String clbNameId,
      String clbPublishedInReferenceId,
      List<String> clbReferenceIds) {}

  /** Maps {@code info.getUsage()} (the taxon itself) -- its real TaxonomicStatus, not a bucket. */
  public static MappedUsage toNameUsage(UsageInfo info) {
    NameUsageBase usage = info.getUsage();
    return toMappedUsage(usage, toStatus(usage.getStatus()));
  }

  /**
   * Flattens {@code info.getSynonyms()} (homotypic + heterotypic + misapplied -- NOT
   * heterotypicGroups, which is the same heterotypic synonyms re-presented grouped by name, see
   * Synonymy's javadoc) into one list. Status is decided by which bucket a synonym came from, not
   * its own {@code TaxonomicStatus} (AMBIGUOUS_SYNONYM included) -- misapplied -> MISAPPLIED,
   * everything else -> SYNONYM, exactly as the brief specifies.
   */
  public static List<MappedUsage> toSynonyms(UsageInfo info) {
    Synonymy syn = info.getSynonyms();
    if (syn == null) {
      return List.of();
    }
    List<MappedUsage> out = new ArrayList<>();
    for (Synonym s : syn.getHomotypic()) {
      out.add(toMappedUsage(s, Status.SYNONYM));
    }
    for (Synonym s : syn.getHeterotypic()) {
      out.add(toMappedUsage(s, Status.SYNONYM));
    }
    for (Synonym s : syn.getMisapplied()) {
      out.add(toMappedUsage(s, Status.MISAPPLIED));
    }
    return out;
  }

  // Shared by toNameUsage (the taxon) and toSynonyms (each synonym): both are a NameUsageBase with a
  // Name, and every field below is collapsed into our single NameUsage row the same way regardless
  // of which side of the accepted/synonym split it's on -- see NameUsage's own class doc. `remarks`
  // comes from the USAGE (taxon-level), not the Name -- confirmed against synthesizeUsageRow's split-
  // form join, which pulls ColdpTerm.remarks from the Taxon/Synonym row, never the Name row.
  private static MappedUsage toMappedUsage(NameUsageBase usage, Status status) {
    Name n = usage.getName();
    if (n == null) {
      // Malformed/incomplete CLB record (a usage with no name at all) -- Task 2's own null-name
      // guard (ClbImportService's insert loop / insertSynonyms) is what actually skips this with a
      // ClbImportIssue rather than inserting it; returning a MappedUsage whose `usage` is null
      // (instead of throwing here, which would NPE two lines below on n.getScientificName()) is
      // simply how that signal travels back up to Task 2 -- clbUsageId is still populated (it comes
      // straight off the CLB usage itself, independent of its Name) so the caller's issue message
      // can still cite the offending CLB id.
      return new MappedUsage(null, usage.getId(), null, null, usage.getReferenceIds());
    }
    NameUsage u = new NameUsage();
    u.setStatus(status);
    u.setNamePhrase(usage.getNamePhrase());
    u.setRemarks(usage.getRemarks());
    u.setScientificName(n.getScientificName());
    u.setAuthorship(n.getAuthorship());
    u.setRank(n.getRank() == null ? "unranked" : n.getRank().name().toLowerCase(Locale.ROOT));
    u.setUninomial(n.getUninomial());
    u.setGenus(n.getGenus());
    u.setInfragenericEpithet(n.getInfragenericEpithet());
    u.setSpecificEpithet(n.getSpecificEpithet());
    u.setInfraspecificEpithet(n.getInfraspecificEpithet());
    u.setCultivarEpithet(n.getCultivarEpithet());
    // notho/gender/nomStatus are the exact same life.catalogue.api.vocab/org.gbif.nameparser.api
    // enum classes on both this app's NameUsage and CLB's Name -- see NameUsage.java's own imports
    // -- so these are direct object assignments, never a lower()/string round-trip.
    u.setNotho(n.getNotho());
    u.setGender(n.getGender());
    u.setNomStatus(n.getNomStatus());
    u.setEtymology(n.getEtymology());
    u.setPublishedInYear(n.getPublishedInYear());
    u.setPublishedInPage(n.getPublishedInPage());
    u.setPublishedInPageLink(n.getPublishedInPageLink());
    // extinct/environment/temporalRange* live in taxon_info and only ever apply to an ACCEPTED usage
    // (see ImportRunService.insertPrimaryUsage) -- a synonym's Taxon-only fields don't exist to map.
    if (status == Status.ACCEPTED && usage instanceof Taxon t) {
      u.setExtinct(t.isExtinct());
      u.setEnvironment(t.getEnvironments() == null || t.getEnvironments().isEmpty()
          ? null : new ArrayList<>(t.getEnvironments()));
      u.setTemporalRangeStart(t.getTemporalRangeStart());
      u.setTemporalRangeEnd(t.getTemporalRangeEnd());
    }
    return new MappedUsage(u, usage.getId(), n.getId(), n.getPublishedInId(), usage.getReferenceIds());
  }

  // Our 4-value Status collapses CLB's richer TaxonomicStatus (see life.catalogue.api.vocab.
  // TaxonomicStatus): ACCEPTED/SYNONYM/MISAPPLIED map 1:1, PROVISIONALLY_ACCEPTED -> UNASSESSED
  // (the brief's explicit inverse of NameUsageColdpWriter.coldpStatus's UNASSESSED -> "provisionally
  // accepted"), AMBIGUOUS_SYNONYM folds into SYNONYM (our model has no pro-parte-ambiguity concept),
  // and BARE_NAME -- which /taxon/{id}/info can never actually return (a bare name isn't a usage at
  // all) -- falls back to UNASSESSED defensively rather than throwing on an enum this method should
  // never actually see.
  static Status toStatus(TaxonomicStatus s) {
    if (s == null) {
      return Status.UNASSESSED;
    }
    return switch (s) {
      case ACCEPTED -> Status.ACCEPTED;
      case PROVISIONALLY_ACCEPTED -> Status.UNASSESSED;
      case SYNONYM, AMBIGUOUS_SYNONYM -> Status.SYNONYM;
      case MISAPPLIED -> Status.MISAPPLIED;
      case BARE_NAME -> Status.UNASSESSED;
    };
  }

  // --- child entities ---------------------------------------------------------------------------
  // Distribution/VernacularName/Media/TaxonProperty/SpeciesEstimate are "taxa only" per UsageInfo's
  // own field comments (never populated for a synonym), so -- unlike TypeMaterial/NameRelation below
  // -- these five never need a CLB usage/name id carried alongside them: they always belong to the
  // one taxon toNameUsage mapped.

  /** Wraps a mapped {@link DistributionRequest}; {@code referenceId} is left null for Task 2 to
   * resolve from {@code clbReferenceId} against its own inserted-references id map. */
  public record MappedDistribution(DistributionRequest request, String clbReferenceId) {}

  public static MappedDistribution toDistributionRequest(Distribution d) {
    String area = null;
    String areaId = null;
    String gazetteer = null;
    GenericArea a = d.getArea();
    if (a != null) {
      area = a.getName();
      areaId = a.getId();
      gazetteer = lower(a.getGazetteer());
    }
    DistributionRequest r = new DistributionRequest(area, areaId, gazetteer,
        lower(d.getEstablishmentMeans()), lower(d.getThreatStatus()), null, d.getRemarks(), null);
    return new MappedDistribution(r, d.getReferenceId());
  }

  public record MappedVernacular(VernacularRequest request, String clbReferenceId) {}

  public static MappedVernacular toVernacularRequest(VernacularName vn) {
    VernacularRequest r = new VernacularRequest(vn.getName(), vn.getLanguage(),
        vn.getCountry() == null ? null : vn.getCountry().getIso2LetterCode(),
        lower(vn.getSex()), vn.isPreferred(), null, vn.getRemarks(), null);
    return new MappedVernacular(r, vn.getReferenceId());
  }

  // Media has no reference linkage in our own model at all (MediaRequest carries no referenceId
  // field), so -- unlike its four siblings above/below -- this needs no wrapper: Media.getReferenceId(),
  // .getFormat(), .getThumbnail() and .getCaptured() are simply dropped, matching our model's
  // simplification (see V10__child_entities.sql's media table).
  public static MediaRequest toMediaRequest(Media m) {
    return new MediaRequest(
        m.getUrl() == null ? null : m.getUrl().toString(),
        lower(m.getType()),
        m.getTitle(),
        m.getCapturedBy(),
        lower(m.getLicense()),
        m.getLink() == null ? null : m.getLink().toString(),
        m.getRemarks(),
        null);
  }

  public record MappedEstimate(EstimateRequest request, String clbReferenceId) {}

  public static MappedEstimate toEstimateRequest(SpeciesEstimate e) {
    EstimateRequest r = new EstimateRequest(e.getEstimate(), lower(e.getType()), null, e.getRemarks(), null);
    return new MappedEstimate(r, e.getReferenceId());
  }

  public record MappedProperty(PropertyRequest request, String clbReferenceId) {}

  public static MappedProperty toPropertyRequest(TaxonProperty tp) {
    PropertyRequest r = new PropertyRequest(tp.getProperty(), tp.getValue(), tp.getPage(), null, tp.getRemarks(), null);
    return new MappedProperty(r, tp.getReferenceId());
  }

  // TypeMaterial and NameRelation key off the NAME, and apply to ANY usage status (see
  // ImportRunService.loadChildEntities' javadoc) -- so unlike the five "taxa only" mappers above,
  // these two need the owning CLB name/usage id carried alongside the request, since either the
  // accepted taxon's OR any synonym's name can own one.

  public record MappedTypeMaterial(TypeMaterialRequest request, String clbReferenceId) {}

  public static MappedTypeMaterial toTypeMaterialRequest(TypeMaterial tm) {
    TypeMaterialRequest r = new TypeMaterialRequest(
        tm.getCitation(),
        lower(tm.getStatus()),
        tm.getInstitutionCode(),
        tm.getCatalogNumber(),
        null, // occurrenceId: CLB TypeMaterial carries no such field either -- see ChildColdpWriter.typeMaterialRow
        tm.getLocality(),
        tm.getCountry() == null ? null : tm.getCountry().getIso2LetterCode(),
        tm.getCollector(),
        tm.getDate(),
        lower(tm.getSex()),
        null,
        tm.getLink() == null ? null : tm.getLink().toString(),
        tm.getRemarks(),
        parseDouble(tm.getLatitude()),
        parseDouble(tm.getLongitude()),
        null);
    return new MappedTypeMaterial(r, tm.getReferenceId());
  }

  /** {@code UsageInfo.getTypeMaterial()} is already keyed by CLB nameID; this just maps each value list. */
  public static Map<String, List<MappedTypeMaterial>> toTypeMaterial(UsageInfo info) {
    Map<String, List<TypeMaterial>> byName = info.getTypeMaterial();
    if (byName == null || byName.isEmpty()) {
      return Map.of();
    }
    Map<String, List<MappedTypeMaterial>> out = new LinkedHashMap<>();
    for (var e : byName.entrySet()) {
      out.put(e.getKey(), e.getValue().stream().map(ClbUsageMapper::toTypeMaterialRequest).toList());
    }
    return out;
  }

  public record MappedNameRelation(
      NameRelationRequest request, String clbUsageId, String clbRelatedUsageId, String clbReferenceId) {}

  // NameUsageRelation (not the plainer NameRelation) is what UsageInfo.getNameRelations() actually
  // returns -- it additionally resolves usageId/relatedUsageId (not just the underlying nameId/
  // relatedNameId), which is exactly the usage-id linkage our own model's relatedUsageId needs, so no
  // extra name->usage resolution is required here. NameRelationRequest has no CLB analogue for
  // `page` -- CLB's NameRelation carries no page field -- so it's always left null.
  public static MappedNameRelation toNameRelationRequest(NameUsageRelation rel) {
    NameRelationRequest r = new NameRelationRequest(null, lower(rel.getType()), null, null, rel.getRemarks(), null);
    return new MappedNameRelation(r, rel.getUsageId(), rel.getRelatedUsageId(), rel.getReferenceId());
  }

  public static List<MappedNameRelation> toNameRelations(UsageInfo info) {
    List<NameUsageRelation> rels = info.getNameRelations();
    return rels == null ? List.of() : rels.stream().map(ClbUsageMapper::toNameRelationRequest).toList();
  }

  // --- references -------------------------------------------------------------------------------

  /**
   * Maps a single CLB Reference into our flat model. CLB's citation/CSL-JSON split (see
   * life.catalogue.api.model.Reference/CslData) is the same shape ColdpExtendedExport.write(Reference)
   * flattens for ColDP's own Reference.tsv -- this mirrors that field-by-field: {@code citation} comes
   * straight off the CLB reference (already the generated-or-verbatim citation string), everything
   * else comes from {@code getCsl()} when present (a reference CLB itself never parsed carries no CSL
   * data, only a citation).
   */
  public static Reference toReference(life.catalogue.api.model.Reference clbRef) {
    Reference r = new Reference();
    r.setCitation(clbRef.getCitation());
    r.setRemarks(clbRef.getRemarks());
    CslData csl = clbRef.getCsl();
    if (csl != null) {
      r.setType(csl.getType() == null ? null : csl.getType().toString());
      r.setAuthor(csl.getAuthor() == null ? null : Arrays.asList(csl.getAuthor()));
      r.setEditor(csl.getEditor() == null ? null : Arrays.asList(csl.getEditor()));
      r.setTitle(csl.getTitle());
      r.setContainerTitle(csl.getContainerTitle());
      r.setIssued(cslDateString(csl.getIssued()));
      r.setVolume(csl.getVolume());
      r.setIssue(csl.getIssue());
      r.setPage(csl.getPage());
      r.setPublisher(csl.getPublisher());
      r.setDoi(csl.getDOI());
      r.setIsbn(csl.getISBN());
      r.setIssn(csl.getISSN());
      r.setLink(csl.getURL());
      r.setAccessed(cslDateString(csl.getAccessed()));
    }
    return r;
  }

  /** {@code UsageInfo.getReferences()} (CLB ref id -> CLB Reference), mapped value-wise. */
  public static Map<String, Reference> toReferences(UsageInfo info) {
    Map<String, life.catalogue.api.model.Reference> refs = info.getReferences();
    if (refs == null || refs.isEmpty()) {
      return Map.of();
    }
    Map<String, Reference> out = new LinkedHashMap<>();
    for (var e : refs.entrySet()) {
      out.put(e.getKey(), toReference(e.getValue()));
    }
    return out;
  }

  // Mirrors TermWriter.set(Term, CslDate)'s exact fallback order: a full/partial date-parts triple
  // wins (formatted via the same life.catalogue.common.date.FuzzyDate.of(int[]).toISO() CLB's own
  // ColDP exporter uses), else the literal, else the raw string.
  private static String cslDateString(CslDate d) {
    if (d == null) {
      return null;
    }
    if (d.getDateParts() != null && d.getDateParts().length > 0 && d.getDateParts()[0].length > 0) {
      return FuzzyDate.of(d.getDateParts()[0]).toISO();
    }
    return d.getLiteral() != null ? d.getLiteral() : d.getRaw();
  }

  // --- the one-call entry point -------------------------------------------------------------------

  /**
   * Everything {@code UsageInfo} carries, mapped: the taxon itself, its flattened synonyms, all
   * "taxa only" child entities, TypeMaterial/NameRelation keyed by CLB nameID (see their own
   * javadoc above), and every reference this bundle touches. DB-free: Task 2 supplies the actual
   * insert order, id allocation and CLB-id -> new-id remapping (referenceId, relatedUsageId,
   * parentId for children spread across the taxon vs. its synonyms).
   */
  public record MappedImport(
      MappedUsage usage,
      List<MappedUsage> synonyms,
      List<MappedDistribution> distributions,
      List<MappedVernacular> vernaculars,
      List<MediaRequest> media,
      List<MappedEstimate> estimates,
      List<MappedProperty> properties,
      Map<String, List<MappedTypeMaterial>> typeMaterialByNameId,
      List<MappedNameRelation> nameRelations,
      Map<String, Reference> references) {}

  public static MappedImport toCreateRequest(UsageInfo info) {
    List<Distribution> distributions = info.getDistributions();
    List<VernacularName> vernaculars = info.getVernacularNames();
    List<Media> media = info.getMedia();
    List<SpeciesEstimate> estimates = info.getEstimates();
    List<TaxonProperty> properties = info.getProperties();
    return new MappedImport(
        toNameUsage(info),
        toSynonyms(info),
        distributions == null ? List.of() : distributions.stream().map(ClbUsageMapper::toDistributionRequest).toList(),
        vernaculars == null ? List.of() : vernaculars.stream().map(ClbUsageMapper::toVernacularRequest).toList(),
        media == null ? List.of() : media.stream().map(ClbUsageMapper::toMediaRequest).toList(),
        estimates == null ? List.of() : estimates.stream().map(ClbUsageMapper::toEstimateRequest).toList(),
        properties == null ? List.of() : properties.stream().map(ClbUsageMapper::toPropertyRequest).toList(),
        toTypeMaterial(info),
        toNameRelations(info),
        toReferences(info));
  }

  // --- shared vocab helpers -----------------------------------------------------------------------

  // The ColDP vocab convention this whole codebase already uses on export (see
  // NameUsageColdpWriter.lower, and CLB's own PermissiveEnumSerde.enumValueName, which every CLB
  // JSON response's enum-typed fields are themselves serialized with): lower-case, with '_' replaced
  // by a space -- e.g. ThreatStatus.CRITICALLY_ENDANGERED -> "critically endangered", NOT the
  // underscore-preserving "critically_endangered" a plain toLowerCase() would leave behind. Unlike
  // toMappedUsage's nomStatus/gender/notho (kept as the actual enum objects, since our own model's
  // fields share those exact classes with CLB's), every child-entity vocab field below is a plain
  // String on our model, so this string form is the only representation to map to.
  private static String lower(Enum<?> e) {
    return e == null ? null : e.name().toLowerCase(Locale.ROOT).replace('_', ' ');
  }

  // TypeMaterial.latitude/longitude are free-text Strings on the CLB side (unlike our own model's
  // Double columns) -- an unparseable value (or one CLB itself never parsed) becomes null rather
  // than failing the whole mapping, exactly like ColdpParse.doubleOrNull's ColDP-import counterpart.
  private static Double parseDouble(String s) {
    if (s == null || s.isBlank()) {
      return null;
    }
    try {
      return Double.valueOf(s.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
