package org.catalogueoflife.editor.name.dto;

import jakarta.validation.constraints.NotBlank;

// Body of POST /usages/{id}/web-reference (NameUsageService.addWebReference): the target page's
// URL. The server fetches its <title> (WebPageClient, SSRF-guarded) to seed a new type=webpage
// reference, falling back to the URL itself when no title can be found, then appends the new
// reference's id to the usage's reference_id[].
public record WebReferenceRequest(@NotBlank String url) {}
