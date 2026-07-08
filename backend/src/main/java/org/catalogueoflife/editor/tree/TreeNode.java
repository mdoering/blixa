package org.catalogueoflife.editor.tree;

// Projection over the accepted classification tree (name_usage.status = 'ACCEPTED' only; see
// TreeMapper). Carries the raw scientificName/authorship/rank/status/ordinal columns -- the
// client composes the display label, we deliberately do NOT re-run the name-parser per node
// (that would be a per-row re-parse on every tree fetch). childCount is the number of ACCEPTED
// children, computed via a scalar subquery so lazy-loading UIs know whether a node is a leaf
// without a separate round-trip.
public record TreeNode(
    Integer id,
    String scientificName,
    String authorship,
    String rank,
    String status,
    Integer ordinal,
    int childCount) {}
