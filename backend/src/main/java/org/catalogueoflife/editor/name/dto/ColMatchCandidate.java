package org.catalogueoflife.editor.name.dto;

// One candidate match from the COL name matcher (see ClbMatchClient.match / ColMatchService.match):
// the best match first (colId = the matched CLB usage's id, matchType = the CLB response's overall
// "type", e.g. EXACT), followed by each of its alternatives[] entries (matchType = "ALTERNATIVE").
// `classification` joins the matched node's classification[].name root-first with " > " so the
// match UI can disambiguate homonyms (same name/rank, different higher classification).
public record ColMatchCandidate(String colId, String name, String authorship, String rank,
    String status, String matchType, String classification) {}
