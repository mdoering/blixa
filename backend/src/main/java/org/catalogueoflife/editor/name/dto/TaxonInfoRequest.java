package org.catalogueoflife.editor.name.dto;

import java.util.List;

// Body of PUT /usages/{id}/taxon-info (NameUsageService.updateTaxonInfo): the taxon-level biology
// attributes that live in taxon_info, kept as a narrow write path (like references/identifiers) so
// the Biology tab can save them without touching the usage's name/nomenclatural fields. A full
// replace of all four -- a null/omitted value CLEARS that field. `version` is the usage's optimistic
// lock and is boxed so a missing value fails validation rather than silently defaulting to 0
// (mirrors ReferenceIdsRequest).
public record TaxonInfoRequest(Boolean extinct, List<String> environment, String temporalRangeStart,
    String temporalRangeEnd, Integer version) {}
