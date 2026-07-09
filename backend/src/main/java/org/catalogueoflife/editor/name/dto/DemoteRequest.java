package org.catalogueoflife.editor.name.dto;

import jakarta.validation.constraints.NotNull;

// Body of POST /usages/{id}/demote (NameUsageService.demote): turn an ACCEPTED usage into a
// synonym/misapplied name of `acceptedId`. `status` is SYNONYM or MISAPPLIED (tolerant string,
// parsed server-side). `childrenTo` is required only when the node has accepted children
// ("new-accepted" | "former-parent"); `synonymsTo` only when the node itself has synonyms
// ("new-accepted" | "unassessed"). `version` is the moved node's optimistic lock.
public record DemoteRequest(
    @NotNull Integer acceptedId,
    @NotNull String status,
    String childrenTo,
    String synonymsTo,
    int version) {}
