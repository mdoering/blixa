package org.catalogueoflife.editor.name.dto;

import java.util.List;

// Body of PUT /usages/{id}/identifiers (NameUsageService.setIdentifiers): a full replace of
// name_usage.alternative_id, not a partial patch -- callers must carry over any existing entries
// they want to keep (e.g. a col:<id> entry set by a prior "match to COL" write; see
// NameUsageService.mergeColId). `version` is the usage's optimistic lock and must be boxed
// (not primitive int) so a missing/null value in the request body fails validation/deserialization
// rather than silently defaulting to 0.
public record IdentifiersRequest(List<String> alternativeId, Integer version) {}
