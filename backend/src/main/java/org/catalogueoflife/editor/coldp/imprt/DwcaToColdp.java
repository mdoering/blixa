package org.catalogueoflife.editor.coldp.imprt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import life.catalogue.coldp.ColdpTerm;
import org.catalogueoflife.editor.coldp.io.ColdpMetadata;
import org.catalogueoflife.editor.coldp.io.ColdpMetadata.ColdpMetadataDto;
import org.catalogueoflife.editor.coldp.io.ColdpTsv;
import org.gbif.dwc.Archive;
import org.gbif.dwc.DwcFiles;
import org.gbif.dwc.record.Record;
import org.gbif.dwc.record.StarRecord;
import org.gbif.dwc.terms.DcTerm;
import org.gbif.dwc.terms.DwcTerm;
import org.gbif.dwc.terms.GbifTerm;
import org.gbif.dwc.terms.IucnTerm;
import org.gbif.dwc.terms.Term;
import org.gbif.utils.file.ClosableIterator;
import org.springframework.stereotype.Component;

// Converts an extracted Darwin Core Archive (Taxon core) into a ColDP staging archive the existing
// import pipeline reads: a combined NameUsage.tsv (accepted taxa with parentID = classification
// parent; synonyms with parentID = their accepted taxon's ID, which the importer turns into a
// synonym_accepted link) plus VernacularName.tsv / Distribution.tsv for the mapped extensions, and a
// minimal metadata.yaml. The importer re-parses scientificName, so no atomized name parts are
// written. See docs/superpowers/specs/2026-07-24-dwca-import-design.md.
@Component
public class DwcaToColdp {

  // The atomic Linnaean rank columns used for the flat-classification fallback, coarsest first. A
  // row with no parent/accepted link is hung under a chain synthesized from whichever of these it
  // carries (see Classifier).
  private record RankCol(String rank, Term term) {}

  private static final List<RankCol> RANK_COLS = List.of(
      new RankCol("kingdom", DwcTerm.kingdom),
      new RankCol("phylum", DwcTerm.phylum),
      new RankCol("class", DwcTerm.class_),
      new RankCol("order", DwcTerm.order),
      new RankCol("family", DwcTerm.family),
      new RankCol("subfamily", DwcTerm.subfamily),
      new RankCol("genus", DwcTerm.genus),
      new RankCol("subgenus", DwcTerm.subgenus));

  public int convert(Path archiveDir, Path outDir, String title) throws IOException {
    Archive archive = DwcFiles.fromLocation(archiveDir);
    Term coreType = archive.getCore().getRowType();
    if (!DwcTerm.Taxon.equals(coreType)) {
      throw new IllegalArgumentException(
          "DwC-A core must be a Taxon core, but was " + (coreType == null ? "unknown" : coreType.simpleName()));
    }
    boolean hasVernacular = archive.getExtension(GbifTerm.VernacularName) != null;
    boolean hasDistribution = archive.getExtension(GbifTerm.Distribution) != null;
    boolean hasProfile = archive.getExtension(GbifTerm.SpeciesProfile) != null;

    List<Map<ColdpTerm, String>> usageRows = new ArrayList<>();
    List<Map<ColdpTerm, String>> vernacularRows = new ArrayList<>();
    List<Map<ColdpTerm, String>> distributionRows = new ArrayList<>();
    Classifier classifier = new Classifier();

    // ClosableIterator.close() throws a checked Exception, so it can't ride a try-with-resources on a
    // method that only throws IOException -- close explicitly in a finally, swallowing a close error
    // (the useful data is already read by then).
    ClosableIterator<StarRecord> it = archive.iterator();
    try {
      while (it.hasNext()) {
        StarRecord star = it.next();
        Record core = star.core();
        Map<ColdpTerm, String> row = coreRow(core, classifier);
        String taxonId = row.get(ColdpTerm.ID);
        if (taxonId == null || taxonId.isBlank()) {
          continue; // a Taxon row with no id can't be referenced or linked -- skip it
        }
        usageRows.add(row);

        if (hasProfile) {
          applySpeciesProfile(row, star.extension(GbifTerm.SpeciesProfile));
        }
        if (hasVernacular) {
          for (Record r : star.extension(GbifTerm.VernacularName)) {
            vernacularRows.add(vernacularRow(taxonId, r));
          }
        }
        if (hasDistribution) {
          for (Record r : star.extension(GbifTerm.Distribution)) {
            distributionRows.add(distributionRow(taxonId, r));
          }
        }
      }
    } finally {
      try {
        it.close();
      } catch (Exception ignore) {
        // best-effort close of the archive iterator
      }
    }
    // Synthesized higher taxa (flat fallback) are appended as ordinary accepted rows; the importer
    // allocates fresh project ids for them like any other row.
    usageRows.addAll(classifier.syntheticRows());

    Files.createDirectories(outDir);
    ColdpTsv.writeFile(outDir, ColdpTerm.NameUsage, usageRows);
    if (!vernacularRows.isEmpty()) {
      ColdpTsv.writeFile(outDir, ColdpTerm.VernacularName, vernacularRows);
    }
    if (!distributionRows.isEmpty()) {
      ColdpTsv.writeFile(outDir, ColdpTerm.Distribution, distributionRows);
    }
    String metaTitle = title == null || title.isBlank() ? null : title;
    ColdpMetadata.write(outDir, new ColdpMetadataDto(metaTitle, null, null, null, null, null));
    return usageRows.size();
  }

  private Map<ColdpTerm, String> coreRow(Record core, Classifier classifier) {
    Map<ColdpTerm, String> row = new EnumMap<>(ColdpTerm.class);
    String id = core.id() != null ? core.id() : core.value(DwcTerm.taxonID);
    put(row, ColdpTerm.ID, id);
    put(row, ColdpTerm.scientificName, core.value(DwcTerm.scientificName));
    put(row, ColdpTerm.authorship, core.value(DwcTerm.scientificNameAuthorship));
    put(row, ColdpTerm.rank, core.value(DwcTerm.taxonRank));
    put(row, ColdpTerm.nameStatus, core.value(DwcTerm.nomenclaturalStatus));
    put(row, ColdpTerm.publishedInYear, core.value(DwcTerm.namePublishedInYear));
    put(row, ColdpTerm.remarks, core.value(DwcTerm.taxonRemarks));

    String coldpStatus = normalizeStatus(core.value(DwcTerm.taxonomicStatus));
    put(row, ColdpTerm.status, coldpStatus);
    boolean accepted = coldpStatus == null || coldpStatus.equals("accepted")
        || coldpStatus.equals("provisionally accepted");

    // parentID: an accepted row hangs off parentNameUsageID; a synonym/misapplied off its
    // acceptedNameUsageID (the importer turns a non-accepted row's parentID into a synonym link).
    String parentLink = accepted
        ? core.value(DwcTerm.parentNameUsageID)
        : firstNonBlank(core.value(DwcTerm.acceptedNameUsageID), core.value(DwcTerm.parentNameUsageID));
    if (isBlank(parentLink) && accepted) {
      // Flat fallback: no explicit parent -> synthesize a classification chain from the rank columns.
      parentLink = classifier.flatParent(core, core.value(DwcTerm.taxonRank));
    }
    put(row, ColdpTerm.parentID, parentLink);
    return row;
  }

  // DwC taxonomicStatus is free-ish; fold it into the ColDP vocab ColdpParse.parseStatus accepts.
  // A blank/unknown value returns null -> the importer defaults the usage to UNASSESSED.
  static String normalizeStatus(String dwc) {
    if (isBlank(dwc)) {
      return null;
    }
    String s = dwc.trim().toLowerCase(Locale.ROOT).replace('_', ' ');
    if (s.contains("misapplied")) {
      return "misapplied";
    }
    if (s.contains("synonym")) {
      return "synonym";
    }
    if (s.equals("accepted") || s.equals("valid")) {
      return "accepted";
    }
    if (s.contains("provisional") || s.contains("doubtful") || s.contains("unassessed")
        || s.contains("interim")) {
      return "provisionally accepted";
    }
    return null;
  }

  private Map<ColdpTerm, String> vernacularRow(String taxonId, Record r) {
    Map<ColdpTerm, String> row = new EnumMap<>(ColdpTerm.class);
    put(row, ColdpTerm.taxonID, taxonId);
    put(row, ColdpTerm.name, r.value(DwcTerm.vernacularName));
    put(row, ColdpTerm.language, r.value(DcTerm.language));
    put(row, ColdpTerm.country, r.value(DwcTerm.countryCode));
    put(row, ColdpTerm.sex, r.value(DwcTerm.sex));
    put(row, ColdpTerm.preferred, r.value(GbifTerm.isPreferredName));
    return row;
  }

  private Map<ColdpTerm, String> distributionRow(String taxonId, Record r) {
    Map<ColdpTerm, String> row = new EnumMap<>(ColdpTerm.class);
    put(row, ColdpTerm.taxonID, taxonId);
    put(row, ColdpTerm.area, firstNonBlank(r.value(DwcTerm.locality), r.value(DwcTerm.countryCode)));
    put(row, ColdpTerm.areaID, r.value(DwcTerm.locationID));
    put(row, ColdpTerm.establishmentMeans, r.value(DwcTerm.establishmentMeans));
    put(row, ColdpTerm.threatStatus, r.value(IucnTerm.threatStatus));
    put(row, ColdpTerm.remarks, r.value(DwcTerm.occurrenceStatus));
    return row;
  }

  // The Species Profile folds onto the core NameUsage row (taxon_info fields): isExtinct -> extinct,
  // the is<Environment> booleans -> the comma-separated environment column the importer CSV-splits.
  private void applySpeciesProfile(Map<ColdpTerm, String> row, List<Record> profiles) {
    if (profiles == null || profiles.isEmpty()) {
      return;
    }
    Record p = profiles.get(0);
    if (isTrue(p.value(GbifTerm.isExtinct))) {
      put(row, ColdpTerm.extinct, "true");
    }
    List<String> envs = new ArrayList<>();
    if (isTrue(p.value(GbifTerm.isMarine))) {
      envs.add("marine");
    }
    if (isTrue(p.value(GbifTerm.isFreshwater))) {
      envs.add("freshwater");
    }
    if (isTrue(p.value(GbifTerm.isTerrestrial))) {
      envs.add("terrestrial");
    }
    if (!envs.isEmpty()) {
      put(row, ColdpTerm.environment, String.join(",", envs));
    }
  }

  // Builds and dedupes the synthesized higher-taxon rows for flat archives.
  private static final class Classifier {
    private final Map<String, String> pathToId = new LinkedHashMap<>();
    private final List<Map<ColdpTerm, String>> synthetic = new ArrayList<>();
    private int seq = 0;

    // The parentID for a row with no explicit link: chain the rank columns STRICTLY above the row's
    // own rank into deduped synthetic nodes and return the deepest. Dedup is by the full ancestral
    // path, so all rows under "Animalia|...|Felidae" share one Felidae node while a homonymous genus
    // in another family stays distinct. Returns null when the row carries no usable higher columns.
    String flatParent(Record core, String ownRank) {
      int ownIdx = rankColIndex(ownRank); // -1 when the row's rank is species/below or unknown
      String parentId = null;
      StringBuilder path = new StringBuilder();
      for (int i = 0; i < RANK_COLS.size(); i++) {
        if (ownIdx >= 0 && i >= ownIdx) {
          break; // don't synthesize the row's own rank or anything below it -- the row IS that node
        }
        RankCol rc = RANK_COLS.get(i);
        String name = core.value(rc.term());
        if (isBlank(name)) {
          continue;
        }
        path.append('|').append(rc.rank()).append('=').append(name.trim());
        String key = path.toString();
        String id = pathToId.get(key);
        if (id == null) {
          id = "__dwca_synth_" + (++seq);
          pathToId.put(key, id);
          Map<ColdpTerm, String> row = new EnumMap<>(ColdpTerm.class);
          row.put(ColdpTerm.ID, id);
          row.put(ColdpTerm.parentID, parentId);
          row.put(ColdpTerm.status, "accepted");
          row.put(ColdpTerm.rank, rc.rank());
          row.put(ColdpTerm.scientificName, name.trim());
          synthetic.add(row);
        }
        parentId = id;
      }
      return parentId;
    }

    List<Map<ColdpTerm, String>> syntheticRows() {
      return synthetic;
    }
  }

  private static int rankColIndex(String rank) {
    if (isBlank(rank)) {
      return -1;
    }
    String r = rank.trim().toLowerCase(Locale.ROOT);
    for (int i = 0; i < RANK_COLS.size(); i++) {
      if (RANK_COLS.get(i).rank().equals(r)) {
        return i;
      }
    }
    return -1;
  }

  private static void put(Map<ColdpTerm, String> row, ColdpTerm term, String value) {
    if (!isBlank(value)) {
      row.put(term, value.trim());
    }
  }

  private static String firstNonBlank(String a, String b) {
    return !isBlank(a) ? a : (!isBlank(b) ? b : null);
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  private static boolean isTrue(String s) {
    if (isBlank(s)) {
      return false;
    }
    String v = s.trim().toLowerCase(Locale.ROOT);
    return v.equals("true") || v.equals("1") || v.equals("yes") || v.equals("y");
  }
}
