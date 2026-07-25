package org.catalogueoflife.editor.name.dto;

// Body of PUT /usages/{id}/genus (NameUsageService.updateGenusId): pin (or clear) a binomial's
// nomenclatural genus link. `genusId` null clears the link; when present it must reference a genus
// usage in this project. `version` is the usage's optimistic lock (boxed so a missing value fails
// validation rather than defaulting to 0, mirroring the other narrow write requests).
public record GenusLinkRequest(Integer genusId, Integer version) {}
