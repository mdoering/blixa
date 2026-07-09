package org.catalogueoflife.editor.name.dto;

import java.util.List;

// Body of POST /usages/{id}/promote (NameUsageService.promote): turn a SYNONYM/MISAPPLIED usage
// into an accepted name placed at `parentId` (null = a new root). All of its synonym_accepted
// links are dropped. `version` is the promoted node's optimistic lock.
//
// `keepAcceptedIds` handles a pro parte synonym: for each accepted id listed (which must currently
// be one of this usage's accepted targets), a NEW synonym usage — a copy of this name — is created
// and linked to it, so that accepted name keeps the synonym even though this usage is now accepted.
// Null/empty means the plain "remove all relations" promotion.
public record PromoteRequest(Integer parentId, List<Integer> keepAcceptedIds, int version) {}
