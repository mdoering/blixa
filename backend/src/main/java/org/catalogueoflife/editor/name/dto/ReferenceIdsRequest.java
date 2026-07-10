package org.catalogueoflife.editor.name.dto;

import java.util.List;

// Body of PUT /usages/{id}/references (NameUsageService.setReferences): a full replace of
// name_usage.reference_id (the usage's taxonomic references), not a partial patch -- callers
// must carry over any existing ids they want to keep. `version` is the usage's optimistic lock
// and must be boxed (not primitive int) so a missing/null value in the request body fails
// validation/deserialization rather than silently defaulting to 0 (mirrors IdentifiersRequest).
public record ReferenceIdsRequest(List<Integer> referenceIds, Integer version) {}
