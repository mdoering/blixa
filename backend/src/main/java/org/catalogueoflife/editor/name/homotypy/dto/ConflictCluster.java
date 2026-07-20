package org.catalogueoflife.editor.name.homotypy.dto;

import java.util.List;

// A homotypic cluster resolving to >1 distinct accepted name. `accepted` are the survivor choices;
// `members` every clustered name; `relations` the detector's proposed homotypic relations to persist
// on consolidation; `hasExceptions` when any member is pro-parte or dual-status.
public record ConflictCluster(List<AcceptedCandidate> accepted, List<ConflictMember> members,
    Integer suggestedSurvivorId, boolean hasExceptions, List<ProposedRelation> relations) {}
