package org.catalogueoflife.editor.child.dto;

import java.util.List;

// GET /api/projects/{pid}/usages/{uid}/map response: the focal usage's bare COL id (parsed from
// its alternative_id, or null if it isn't matched to COL) plus every distribution and type-specimen
// point in the focal usage's accepted subtree (including the focal usage itself).
public record MapData(
    String colId,
    List<MapAreaRecord> distributions,
    List<MapPointRecord> typeSpecimens) {}
