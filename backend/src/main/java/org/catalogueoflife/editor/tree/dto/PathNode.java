package org.catalogueoflife.editor.tree.dto;

// One ancestor-path entry (TreeMapper.findPath), root-first. Deliberately minimal -- just enough
// for a breadcrumb -- unlike TreeNode it carries no childCount/status/ordinal.
public record PathNode(Integer id, String scientificName, String rank) {}
