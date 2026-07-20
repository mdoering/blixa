package org.catalogueoflife.editor.coldp.export;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import life.catalogue.coldp.ColdpTerm;
import org.catalogueoflife.editor.coldp.io.ColdpTsv;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.name.NameUsageMapper;
import org.catalogueoflife.editor.name.Status;
import org.catalogueoflife.editor.name.SynAccLink;
import org.catalogueoflife.editor.name.SynonymAcceptedMapper;
import org.gbif.nameparser.api.NomCode;
import org.springframework.stereotype.Component;

// Builds the combined NameUsage.tsv (ColdpTerm.NameUsage folds Name+Taxon+Synonym into one file --
// see ColdpTerm.RESOURCES): one row per ACCEPTED usage (parentID = the classification parent), and
// for every SYNONYM/MISAPPLIED/UNASSESSED usage one row PER accepted link -- the first (lowest
// acceptedId) link reuses the usage's own id, each additional link (the pro-parte case: a synonym
// pointing at more than one accepted name) gets a derived "<usageId>-<acceptedId>" id so every row
// still has a unique ID while all of them share the same name fields. A usage with no accepted
// link at all (an orphaned/unassessed synonym) still gets exactly one row, with an empty parentID.
@Component
public class NameUsageColdpWriter {

  private final NameUsageMapper usages;
  private final SynonymAcceptedMapper synonymAccepted;

  public NameUsageColdpWriter(NameUsageMapper usages, SynonymAcceptedMapper synonymAccepted) {
    this.usages = usages;
    this.synonymAccepted = synonymAccepted;
  }

  /** Writes {@code dir/NameUsage.tsv} and returns the number of rows written. */
  public int write(Path dir, int projectId, NomCode nomCode) throws IOException {
    String code = nomCode == null ? null : nomCode.name().toLowerCase(Locale.ROOT);
    Map<Integer, List<Integer>> acceptedBySynonym = groupLinks(synonymAccepted.findAllLinks(projectId));

    List<Map<ColdpTerm, String>> rows = new ArrayList<>();
    for (NameUsage u : usages.findAllByProject(projectId)) {
      if (u.getStatus() == Status.ACCEPTED) {
        rows.add(acceptedRow(u, code));
      } else {
        rows.addAll(synonymRows(u, code, acceptedBySynonym.getOrDefault(u.getId(), List.of())));
      }
    }
    ColdpTsv.writeFile(dir, ColdpTerm.NameUsage, rows);
    return rows.size();
  }

  // findAllLinks is already ordered by synonym_id, accepted_id -- grouping preserves that ascending
  // per-synonym accepted_id order, which is exactly the order synonymRows below relies on (first
  // link = lowest acceptedId = the primary row).
  private static Map<Integer, List<Integer>> groupLinks(List<SynAccLink> links) {
    Map<Integer, List<Integer>> grouped = new LinkedHashMap<>();
    for (SynAccLink link : links) {
      grouped.computeIfAbsent(link.synonymId(), k -> new ArrayList<>()).add(link.acceptedId());
    }
    return grouped;
  }

  private static Map<ColdpTerm, String> acceptedRow(NameUsage u, String code) {
    Map<ColdpTerm, String> row = nameFields(u, code);
    row.put(ColdpTerm.ID, str(u.getId()));
    row.put(ColdpTerm.parentID, str(u.getParentId()));
    row.put(ColdpTerm.extinct, u.getExtinct() == null ? null : String.valueOf(u.getExtinct()));
    row.put(ColdpTerm.environment, joinEnum(u.getEnvironment()));
    row.put(ColdpTerm.temporalRangeStart, u.getTemporalRangeStart());
    row.put(ColdpTerm.temporalRangeEnd, u.getTemporalRangeEnd());
    return row;
  }

  private static List<Map<ColdpTerm, String>> synonymRows(NameUsage u, String code, List<Integer> acceptedIds) {
    if (acceptedIds.isEmpty()) {
      Map<ColdpTerm, String> row = nameFields(u, code);
      row.put(ColdpTerm.ID, str(u.getId()));
      row.put(ColdpTerm.parentID, null);
      return List.of(row);
    }
    List<Map<ColdpTerm, String>> rows = new ArrayList<>(acceptedIds.size());
    boolean first = true;
    for (int acceptedId : acceptedIds) {
      Map<ColdpTerm, String> row = nameFields(u, code);
      row.put(ColdpTerm.ID, first ? str(u.getId()) : u.getId() + "-" + acceptedId);
      row.put(ColdpTerm.parentID, str(acceptedId));
      rows.add(row);
      first = false;
    }
    return rows;
  }

  // The name-level columns shared by every row a usage produces (the primary row and, for a
  // pro-parte synonym, every derived row alike) -- ID/parentID and the accepted-only taxon_info
  // fields (extinct/environment/temporalRange*, added only in acceptedRow) are set by the caller.
  private static Map<ColdpTerm, String> nameFields(NameUsage u, String code) {
    Map<ColdpTerm, String> row = new LinkedHashMap<>();
    row.put(ColdpTerm.alternativeID, join(u.getAlternativeId()));
    row.put(ColdpTerm.status, coldpStatus(u.getStatus()));
    row.put(ColdpTerm.scientificName, u.getScientificName());
    row.put(ColdpTerm.authorship, u.getAuthorship());
    row.put(ColdpTerm.rank, u.getRank());
    row.put(ColdpTerm.notho, lower(u.getNotho()));
    row.put(ColdpTerm.uninomial, u.getUninomial());
    row.put(ColdpTerm.genericName, u.getGenus());
    row.put(ColdpTerm.infragenericEpithet, u.getInfragenericEpithet());
    row.put(ColdpTerm.specificEpithet, u.getSpecificEpithet());
    row.put(ColdpTerm.infraspecificEpithet, u.getInfraspecificEpithet());
    row.put(ColdpTerm.cultivarEpithet, u.getCultivarEpithet());
    row.put(ColdpTerm.combinationAuthorship, u.getCombinationAuthorship());
    row.put(ColdpTerm.combinationExAuthorship, u.getCombinationExAuthorship());
    row.put(ColdpTerm.combinationAuthorshipYear, u.getCombinationAuthorshipYear());
    row.put(ColdpTerm.basionymAuthorship, u.getBasionymAuthorship());
    row.put(ColdpTerm.basionymExAuthorship, u.getBasionymExAuthorship());
    row.put(ColdpTerm.basionymAuthorshipYear, u.getBasionymAuthorshipYear());
    row.put(ColdpTerm.namePhrase, u.getNamePhrase());
    row.put(ColdpTerm.nameReferenceID, str(u.getPublishedInReferenceId()));
    row.put(ColdpTerm.namePublishedInYear, str(u.getPublishedInYear()));
    row.put(ColdpTerm.namePublishedInPage, u.getPublishedInPage());
    row.put(ColdpTerm.namePublishedInPageLink, u.getPublishedInPageLink());
    row.put(ColdpTerm.gender, lower(u.getGender()));
    row.put(ColdpTerm.etymology, u.getEtymology());
    row.put(ColdpTerm.code, code);
    row.put(ColdpTerm.nameStatus, lower(u.getNomStatus()));
    row.put(ColdpTerm.referenceID, joinInts(u.getReferenceId()));
    row.put(ColdpTerm.ordinal, str(u.getOrdinal()));
    row.put(ColdpTerm.remarks, u.getRemarks());
    return row;
  }

  // Our 4-value Status collapses ColDP's richer taxonomic-status vocab (see life.catalogue.api.
  // vocab.TaxonomicStatus): ACCEPTED/SYNONYM/MISAPPLIED map 1:1, and UNASSESSED (a synonym/usage
  // with no -- or not yet confirmed -- accepted link) maps to "provisionally accepted", the closest
  // existing ColDP value for "treated as usable but not fully confirmed".
  private static String coldpStatus(Status status) {
    if (status == null) {
      return null;
    }
    return switch (status) {
      case ACCEPTED -> "accepted";
      case SYNONYM -> "synonym";
      case MISAPPLIED -> "misapplied";
      case UNASSESSED -> "provisionally accepted";
    };
  }

  // Every descriptive vocab field on NameUsage (gender/nomStatus/notho/environment) is stored as an
  // upper-case, underscore-separated enum name (see VocabParsing.parse); ColDP's own convention for
  // the same values is lower-case with spaces (e.g. NomStatus.getLabel(null) uses exactly this
  // transform) -- this is the inverse of VocabParsing.
  private static String lower(Enum<?> e) {
    return e == null ? null : e.name().toLowerCase(Locale.ROOT).replace('_', ' ');
  }

  private static String str(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private static String join(List<String> values) {
    return (values == null || values.isEmpty()) ? null : String.join(",", values);
  }

  private static String joinInts(List<Integer> values) {
    return (values == null || values.isEmpty()) ? null
        : values.stream().map(String::valueOf).collect(Collectors.joining(","));
  }

  private static String joinEnum(List<? extends Enum<?>> values) {
    return (values == null || values.isEmpty()) ? null
        : values.stream().map(NameUsageColdpWriter::lower).collect(Collectors.joining(","));
  }
}
