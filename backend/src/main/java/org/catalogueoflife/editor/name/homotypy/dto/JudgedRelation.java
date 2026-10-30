package org.catalogueoflife.editor.name.homotypy.dto;

import java.util.List;

// One name relation of a RelationConflictPair. `type` is the stored type string as is. `issues` are
// CLB Issue names, plus "DUPLICATE" for a row that repeats the kept row of the same type and
// direction. `remove`: a verdict says the relation should go. `preselected`: ticked for deletion by
// default -- removed relations, and duplicates that carry no reference, page or remarks the kept row
// lacks. A relation without a verdict has no issues and is not preselected.
public record JudgedRelation(int id, int version, String type, int usageId, int relatedUsageId,
    Integer referenceId, String page, String remarks, List<String> issues, boolean remove,
    boolean duplicate, boolean preselected) {}
