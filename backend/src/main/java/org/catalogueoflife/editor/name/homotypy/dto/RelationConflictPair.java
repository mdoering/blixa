package org.catalogueoflife.editor.name.homotypy.dto;

import java.util.List;

// Two names whose relations the cleanup scan judged: every candidate relation between them, the
// judged ones, the duplicates and, for context, the relations without a verdict. Ordered by id.
public record RelationConflictPair(PairName n1, PairName n2, List<JudgedRelation> relations) {}
