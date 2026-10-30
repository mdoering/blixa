package org.catalogueoflife.editor.name.homotypy.dto;

// One name of a RelationConflictPair: the usage id, its formatted name and its status.
public record PairName(int id, String label, String status) {}
