package org.catalogueoflife.editor.name.dto;

// Body of POST /usages/{id}/promote (NameUsageService.promote): turn a SYNONYM/MISAPPLIED usage
// into an accepted name placed at `parentId` (null = a new root). All of its synonym_accepted
// links are dropped. `version` is the promoted node's optimistic lock.
public record PromoteRequest(Integer parentId, int version) {}
