package org.catalogueoflife.editor.clb.dto;

import java.util.List;
import java.util.Map;

/**
 * Result of {@code ClbImportService.importFromClb}: how many rows of each kind were actually
 * inserted, plus any per-record problems encountered along the way (a dangling CLB
 * cross-reference, a taxon-scoped child entity skipped because its owning usage isn't ACCEPTED,
 * ...). These are never fatal to the rest of the import -- see ClbImportService's own javadoc for
 * why this whole call runs with no enclosing transaction, committing as it goes; an issue
 * collected here is the non-fatal counterpart to that -- a genuinely fatal problem (bad request,
 * upstream CLB failure) instead throws and this summary is never returned at all.
 *
 * <p>{@code children} is keyed by the same 7 tokens {@link ClbImportRequest#entityTypes()} uses
 * ({@code "vernacular"}, {@code "distribution"}, {@code "typeMaterial"}, {@code "media"}, {@code
 * "estimate"}, {@code "property"}, {@code "nameRelation"}), every key always present (0 when
 * nothing of that kind was inserted or selected) so a caller can render a fixed-shape summary
 * without null-checking each entry.
 */
public record ClbImportSummary(
    int nameUsages, int synonyms, int references, Map<String, Integer> children, List<ClbImportIssue> issues) {

  public record ClbImportIssue(String entity, String sourceId, String message) {}
}
