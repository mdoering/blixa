package org.catalogueoflife.editor.clb.dto;

/**
 * Light response of {@code GET /api/clb/{datasetKey}/resolve/{taxonId}}: just enough to show the
 * user what a pasted CLB/portal URL (see {@link org.catalogueoflife.editor.clb.ClbTaxonUrl}) points
 * at before they commit to importing it -- the full {@code UsageInfo} the same lookup fetches under
 * the hood is only actually needed once the import itself runs.
 */
public record ClbResolvedTaxon(String datasetKey, String taxonId, String scientificName, String rank) {}
