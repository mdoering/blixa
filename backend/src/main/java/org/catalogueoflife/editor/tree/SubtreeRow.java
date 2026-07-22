package org.catalogueoflife.editor.tree;

// One accepted name_usage row of a subtree export (see TreeMapper.subtreeUsages): the fields needed
// to build a TextTree node plus parentId to reconstruct the hierarchy. Rows come back parent-first
// (ordered by recursion depth), so a parent node always exists before its children are processed.
public record SubtreeRow(
    Integer id, Integer parentId, String scientificName, String authorship, String rank) {}
