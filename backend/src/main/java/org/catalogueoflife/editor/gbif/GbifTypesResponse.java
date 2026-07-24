package org.catalogueoflife.editor.gbif;

import java.util.List;

// Response of GET /usages/{id}/gbif-types: the type-specimen candidates for a usage, plus context.
//  - name: the usage's scientific name (what was resolved).
//  - colId: the COL taxon id used for the GBIF query, or null when the usage couldn't be matched to
//    COL (candidates is then empty and the UI shows a "no COL match" note).
//  - truncated: GBIF had more type specimens than the fetch cap (the UI notes it).
public record GbifTypesResponse(
    String name,
    String colId,
    boolean truncated,
    List<GbifTypeCandidate> candidates) {}
