package org.catalogueoflife.editor.name.homotypy.dto;

// A survivor choice for a conflict: an accepted name the cluster resolves to, with its accepted
// subtree size (for the suggestion + display) and its optimistic-lock version (echoed back on apply).
public record AcceptedCandidate(int id, String formattedName, int descendantCount, int version) {}
