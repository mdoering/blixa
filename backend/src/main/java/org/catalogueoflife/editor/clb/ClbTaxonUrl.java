package org.catalogueoflife.editor.clb;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Parses a pasted ChecklistBank/portal URL into (datasetKey, taxonId) so the "paste a URL" import
// entry point (Task 2/3) doesn't need the user to hunt down the raw dataset key and id themselves.
// Deliberately lenient: any http(s) scheme, any subdomain (api./www./dev./preview./bare), a trailing
// slash, and a query string or #fragment are all tolerated -- this is a paste target, not a strict
// URI validator, so being permissive beats rejecting a URL a human copied straight out of their
// browser's address bar.
public final class ClbTaxonUrl {

  private ClbTaxonUrl() {}

  // The COL release dataset alias every catalogueoflife.org/data/taxon/{id} portal link resolves
  // against -- same default as ClbMatchClient's coldp.col.match-dataset (3LXR), but hard-coded here
  // rather than injected: this is a pure, Spring-free parser (no @Value, no bean), and the portal's
  // taxon pages only ever point at the single published COL checklist, never an arbitrary dataset.
  static final String COL_PORTAL_DATASET = "3LXR";

  // group(1) = dataset key (numeric key or alias, e.g. "3LXR" or "315557"), group(2) = taxon/usage id.
  // "taxon" and "nameusage" are the two CLB paths that identify a single usage by id (see
  // ClbImportClient.usageInfo/searchUsages); both resolve to the exact same (datasetKey, id) pair.
  private static final Pattern CLB_DATASET_USAGE = Pattern.compile(
      "(?i)^https?://(?:[\\w-]+\\.)*checklistbank\\.org/dataset/([^/?#]+)/(?:taxon|nameusage)/([^/?#]+)/?(?:[?#].*)?$");

  // group(1) = taxon id; the portal always serves the current COL release, so the dataset key is
  // fixed to COL_PORTAL_DATASET rather than captured from the URL.
  private static final Pattern COL_PORTAL_TAXON = Pattern.compile(
      "(?i)^https?://(?:[\\w-]+\\.)*catalogueoflife\\.org/data/taxon/([^/?#]+)/?(?:[?#].*)?$");

  public record ClbRef(String datasetKey, String taxonId) {}

  public static Optional<ClbRef> parse(String url) {
    if (url == null) {
      return Optional.empty();
    }
    String s = url.trim();
    if (s.isEmpty()) {
      return Optional.empty();
    }
    Matcher m = CLB_DATASET_USAGE.matcher(s);
    if (m.matches()) {
      return Optional.of(new ClbRef(m.group(1), m.group(2)));
    }
    m = COL_PORTAL_TAXON.matcher(s);
    if (m.matches()) {
      return Optional.of(new ClbRef(COL_PORTAL_DATASET, m.group(1)));
    }
    return Optional.empty();
  }
}
