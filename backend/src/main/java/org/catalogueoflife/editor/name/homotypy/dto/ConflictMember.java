package org.catalogueoflife.editor.name.homotypy.dto;

import java.util.List;

// One clustered name in a conflict, for display. acceptedTargetIds is [self] for an accepted
// member, or the synonym's accepted target ids for a synonym. version is the usage's
// optimistic-lock version (echoed back when an accepted member is submitted as a loser to
// demote(); losers are cluster members, not survivor candidates, so the version lives here now).
// proParte: a synonym with >1 target. dualStatus: the same scientificName appears in the cluster
// both accepted and as a synonym.
public record ConflictMember(int id, String formattedName, String status,
    List<Integer> acceptedTargetIds, int version, boolean proParte, boolean dualStatus) {}
