package org.catalogueoflife.editor.clb.dto;

import java.util.Set;
import org.catalogueoflife.editor.clb.ImportMode;

/**
 * Body of {@code POST /api/projects/{pid}/usages/{focalId}/clb-import}.
 *
 * <p>{@code entityTypes} selects which of the picked CLB taxon's supplementary infos to bring
 * over: {@code "synonyms"} plus the 7 child-entity kinds in {@link
 * org.catalogueoflife.editor.clb.ClbUsageMapper}'s own vocabulary -- {@code "vernacular"}, {@code
 * "distribution"}, {@code "typeMaterial"}, {@code "media"}, {@code "estimate"}, {@code
 * "property"}, {@code "nameRelation"}.
 *
 * <p>For {@link ImportMode#TAXON_SUBTREE}/{@link ImportMode#CHILDREN_ONLY} a null/empty set means
 * "include everything" -- mirrors the ColDP import's own archive-has-it-all-or-nothing default.
 * For {@link ImportMode#UPDATE_FOCAL} there is deliberately NO such default: attaching
 * supplementary data onto an EXISTING accepted usage is a targeted, opt-in action, so a
 * null/empty set there means "attach nothing" (see {@code ClbImportService.included}).
 */
public record ClbImportRequest(String datasetKey, String sourceTaxonId, ImportMode mode, Set<String> entityTypes) {}
