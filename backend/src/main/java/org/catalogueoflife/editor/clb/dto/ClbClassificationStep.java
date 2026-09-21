package org.catalogueoflife.editor.clb.dto;

/**
 * One higher-rank entry of a CLB classification (root → direct parent), resolved against our tree.
 * {@code matchId}: the existing ACCEPTED usage of ours with the same name + rank (null = not in the
 * project, so it can be created). {@code inPlace}: that match sits directly under the previous
 * resolved ancestor, i.e. our tree agrees with CLB at this step. {@code ambiguous}: several unrelated
 * candidates matched and the first was taken.
 */
public record ClbClassificationStep(String clbId, String rank, String name, String authorship,
    Integer matchId, boolean inPlace, boolean ambiguous) {}
