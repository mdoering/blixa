package org.catalogueoflife.editor.name.homotypy.dto;

// A survivor choice for a conflict: an accepted name the cluster resolves to, with its accepted
// subtree size (for the suggestion + display). Survivor candidates are never themselves demoted --
// only cluster MEMBERS (see ConflictMember) can be losers -- so this carries no version.
public record AcceptedCandidate(int id, String formattedName, int descendantCount) {}
