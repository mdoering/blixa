package org.catalogueoflife.editor.name.dto;

// Body of POST /references/import-csl-json -- a CSL-JSON document (array of items, or a single item
// object) as a raw string, parsed + mapped by ReferenceImportService.importCslJson.
public record CslJsonRequest(String cslJson) {}
