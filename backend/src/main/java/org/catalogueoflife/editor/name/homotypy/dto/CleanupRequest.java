package org.catalogueoflife.editor.name.homotypy.dto;

import java.util.List;

// The relations an editor ticked in the cleanup review, each with the version the scan showed.
public record CleanupRequest(List<RelationRef> relations) {

  public record RelationRef(Integer id, Integer version) {}
}
